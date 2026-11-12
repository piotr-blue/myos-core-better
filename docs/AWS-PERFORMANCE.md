# AWS document-local measurements

Measured 2026-11-05 (Asia/Bangkok) on the isolated `myos-better-20261105` deployment in `eu-west-1`:
`c7i.large`, two vCPUs, 4 GiB RAM, Corretto 25, DynamoDB on-demand, two propagation workers. These are
engineering measurements of this implementation and synthetic authored workflow, not a production SLA.

## Payment propagation across graph sizes

Each order has three independent payments. A payment records 42, emits an event, its order adds 42 and
emits an event, and the agreement adds 42. All effects execute authored Contracts/BEX. Every measured
operation waits for and checks the exact agreement total. No unrelated order is executed. No full
expanded graph export is included in the processing interval.

Each row is a separate JVM, 128 warm-up calls followed by 128 measured calls, fixed random seed.
The active-order column distinguishes a small reused working set from uniform selection over the
whole graph. Target-ID lookup happens before timing. Leaf execution excludes restore, checkpoint
serialization and storage. Leaf commit includes those costs. End-to-end includes durable propagation
and the benchmark's completion read. The server may also deliver durable work.

| Orders | Documents | Active orders | Leaf execution median, ms | Leaf commit median, ms | Root completion median, ms | Root completion p95, ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 5 | 1 | 2.70 | 24.55 | 182.97 | 211.62 |
| 128 | 513 | 128 | 2.26 | 31.18 | 194.30 | 221.39 |
| 20,000 | 80,001 | 8 | 2.82 | 25.34 | 193.33 | 213.51 |
| 128 | 513 | 8 | 2.82 | 25.78 | 191.73 | 214.62 |
| 20,000 | 80,001 | 20,000 | 2.71 | 33.32 | 205.49 | 230.38 |

With the same eight-order working set, 128 and 20,000 stored orders measured 191.73 and 193.33 ms
median completion respectively; leaf execution was 2.82 ms in both. This sample does not show
processing cost proportional to the number of siblings. Uniform selection incurs more cache misses:
leaf commits measured 31.18 ms at 128 orders and 33.32 ms at 20,000. Storage and propagation still
dominate elapsed time; further I/O optimization is underway.

Release SHA-256: `60cb1d98cff168b067625d8a0a049850d75b6df7d216cd726779a19016173277`.
The immutable JSONL samples are in the experiment artifact bucket under `reports/60cb1d98cff1-*`.
Local copies and the summary are in the ignored `reports/` directory. This release predates the compact
exact-version command commitment and storage-call instrumentation added in the next iteration.

## Initial large construction run

The earlier release created all 20,000 orders and 60,000 payments using `addOrder` and initialization
handlers. The agreement's initialization counter reached exactly 20,000. Sequential setup took
3,119 seconds in the resumed qualification run, including idempotent replay of its earlier setup
prefix. Creating 80,001 documents necessarily takes work proportional to their count. This is not a
measurement of one operation's scaling with the existing graph.

Its 128 random measured payment calls (after 32 warm-up calls) gave median execution 1.83 ms, leaf
commit 31.88 ms and root completion 200.24 ms; every aggregate assertion passed. Do not compare its
execution median directly with the table as an isolated regression: the release, warm-up history and
initial JVM workload differ. Memory during large construction stayed around 1.1–1.2 GiB used on the
4 GiB instance; that is an operating-system observation, not a heap-retention proof.

At 8,927 orders, the root state occupied 8,388 gzip bytes and 123 checkpoint records. A one-order local
probe occupied 8,062 bytes and 121 records. Child links live in immutable indexed pages; the root body
does not contain every sibling. Explicit expansion still costs work proportional to the data read.

## Limits and remaining qualification

A single parent updated by every payment remains a serialization point. This report does not establish
maximum throughput across many tenants, tail latency under sustained contention, disaster recovery,
shared-document attachment, arbitrary cycles, or full original MyOS API parity. Full graph proofs and
cross-document atomic rollback are deliberately outside the document-local protocol. The canonical
Contracts API remains available separately.

## Instrumented baseline before batched reads

Release `cffc9097d5bc7e7d800d916a152a951badbc984a1eaa4a16371a0d9525717b1d` repeated the
20,000-order / eight-active-order probe, 128 warm-up and 128 measured operations. Median leaf
execution was 3.24 ms, leaf commit 25.77 ms and root completion 192.70 ms. Across the measured calls,
the benchmark process observed:

| Storage operation | Calls per measured operation | Total wall time per operation, ms |
| --- | ---: | ---: |
| Individual record reads | 21.02 | 64.76 |
| Conditional transactions | 6.01 | 93.84 |
| Reverse-parent queries | 3.00 | 10.33 |
| Immutable page publication calls, including empty batches | 3.01 | 11.63 |
| Queue index queries | 0 | 0 |

There were nine retryable transaction failures and no thrown storage failures. These counters belong
to this process; the separate service may race to deliver the same durable work. Times measure the
whole store method, including encoding/decoding; they are not DynamoDB server-only latency. The
result supports reducing I/O round trips without weakening conditional publication. It does not
support blaming a scan of the 20,000 sibling orders: no immutable-page read or queue scan occurred
inside these warm measured intervals.

The batched-read candidate (`159ed79b17c5…`) passed the same probe: 2.81 ms leaf execution,
23.19 ms leaf commit and 182.05 ms median completion. Each operation made nine single reads plus six
batch calls, three parent queries and six transactions. Combined read-method time fell from 64.76 to
54.41 ms per operation. Four transactions returned retryable failures, with no thrown storage errors.
This release also contains the schema-projection correctness fix; the structural round-trip count,
not a perfectly isolated wall-time attribution, supports keeping the batching change.

## Real published clauses and local storage diagnosis

The pinned Hello/Activation example also passed through the private HTTP API against DynamoDB:
initialization, consent, a validated action, rejected input without a state change, event retention
and original-input audit. On release `159ed79b17c5…`, its first Hello action took 694.88 ms including
44.99 ms of kernel execution; consent took 893.76 ms. These are cold application actions, not the
warmed synthetic payment numbers above. The immutable report is `reports/hello-published-clause.json`
in the experiment bucket.

A separate local HTTP comparison used 32 warm-up and 64 measured Hello calls with exact-version
binding. The baseline rewrote a roughly 993 KB expanded projection for each message change.
Bounded optional projection storage with exact-version session reuse changed update median/p95
from 118.27/198.88 ms to 12.21/17.19 ms. Full local read median changed from 36.32 to 73.79 ms; the
read tradeoff is explicit, and metadata-only reads are now available. An earlier candidate that
cold-restored every read regressed to 713.84 ms and was replaced before deployment.

Cold restoration was then measured independently on the same 161,240-byte, 99-record Hello
checkpoint (five warm-ups, fifteen samples). Replacing repeated inventory merges with persistent-map
root reuse reduced median restore from 567.05 to 77.22 ms. Installing the already verified Dispatch
publication directly reduced it further to 59.40 ms. Every supplied untrusted record still undergoes
verification, including forged inline/core/cyclic cases. These local measurements do not yet establish
new AWS clause latency; the compatible rollout and remeasurement follow them.


Release `b6483be89da5…` repeated the 80,001-document, eight-active-order probe after the compact
projection and verified-evidence reuse changes: leaf execution 2.81 ms, leaf commit 24.11 ms, root
completion 187.61 ms. All aggregate checks passed. Its 3% difference from 182.05 ms is within the
variation of these single-run AWS comparisons; the synthetic small documents do not exercise the
large-schema projection optimization.

The real published Hello run on the same release passed consent, accepted/rejected actions, state,
events and audit checks. First consent took 204.95 ms (134.51 ms execution); Hello took 100.01 ms
(40.98 ms execution); rejection took 51.30 ms and retained the prior version. The earlier release
measured 893.76 / 694.88 / 124.96 ms respectively. Initialization execution remained about 1.2 seconds
in these cold JVM examples, so this is not a claim that every clause lifecycle stage is now fast.
The immutable report is `reports/b6483be89da5-hello-published-clause.json`; compact summaries avoid
copying megabytes of expanded schema into the source repository.


## Independent accounts and CPU saturation

A bounded client created 16 accounts, eight orders per account and two payments per order (400
initial documents), then submitted 32 random payments per account concurrently. Setup and target
lookup were excluded from the measured phase; there was no warm-up exclusion. Completion requires
all 16 root totals to equal 1,344. All 512 operations and aggregates passed.

| Release/configuration | Completed payments/s | Completion, s | Leaf commit median/p95, ms | Leaf execution median, ms |
| --- | ---: | ---: | ---: | ---: |
| b6483be89da5, two workers | 12.82 | 39.93 | 214.39 / 347.11 | 4.67 |
| d711071a186b, fair scheduler, two workers | 10.84 | 47.22 | 286.86 / 494.96 | 7.98 |
| d711071a186b, fair scheduler, eight workers | 19.69 | 26.00 | 577.34 / 974.77 | 16.34 |

These short runs include cache pressure and queueing. More workers improved completion throughput
while increasing leaf latency; they do not establish a production default or a fairness overhead
percentage. A separate profiled eight-worker run achieved 18.91 payments/s. JFR observed average JVM
CPU about 95% of the two-vCPU machine, 97% total host CPU and 59 young collections over roughly 40
seconds including setup. Restore appeared in 762 sampled stacks, business dispatch in 325; these are
inclusive sampling categories, not mutually exclusive percentages. Native socket-wait samples are
not counted as CPU business work. Repeated cold session restoration is a material target.

The profiled report and JFR are under
`reports/throughput-d711071a186b-8w-16a-8o-32ops-7f70a055.*` in the private artifact bucket.
Do not compare its times as an uninstrumented production benchmark.

## Recovery qualification

With the service stopped, a separate JVM committed one order and a payment, verified the root still
had zero initialization count and total, and exited with durable work pending. Restart recovered
both parent reactions: initialization count one, total 42, root version four. Retrying the leaf
command after restart did not advance the root or duplicate the payment. Completion took about
3.49 seconds including service startup. Account:
`recovery-b75784a5-014e-44cc-b07c-a33baa62c558`.

A deliberately invalid projection configuration then failed activation. A separate SSM check
verified the expected immutable release symlink, exact healthy service-unit contents and restored
eight-worker health. This qualifies this activation failure path; it does not simulate an AWS zone
failure or guarantee zero-downtime deployment.

### Bounded cache sizing

On release `8c77707873e3…`, eight workers with 256 session entries / 512 MiB completed the same
16-account / 512-payment workload in 21.56 seconds (23.75 payments/s), with 406/674 ms leaf commit
median/p95. All root totals matched. Measured session loads fell from 1,131 to 591, capacity evictions
from 1,046 to 335 and aggregate restore wall time from 13.98 to 6.62 seconds. Active sessions remain
pinned, so estimated cache bytes are an eviction target, not a hard process memory bound. The
32-mutation history limit is unchanged. This single run supports the deployment choice, not an SLA.


## Latest isolated checkpoints

The running worker allocation is now eight; earlier tables retain their original configurations.
On `a9880858a831…`, a fresh isolated payment probe over 20,064 orders / 80,257 documents measured
2.033 ms leaf runtime, 20.077 ms leaf commit and 142.901 ms completion. It used 128 warm-ups,
128 measured calls and eight active orders. Interleaved additions completed in 175.828 ms on the
small graph and 187.041 ms on the large graph, adding 64 orders to each. The stored large graph
now contains 20,128 orders / 80,513 documents.

TLS-enabled profiles of the 16-account workload observed 942 DynamoDB handshakes with URLConnection
versus six with Apache 5. Warmed throughput was 31.69 versus 39.17 payments/s; first post-deployment
runs were 24.67 versus 30.22. Warm-up matters. The later parentless-root path completed 41.19/s in
a warmed profile, but its first run was slower than the preceding baseline (30.27 versus 34.00/s).
Retain the measured reduction in storage operations without claiming a universal timing percentage.

Two independent JVMs passed 128 concurrent submissions of 64 unique payment commands: identical
receipts, exact leaf/order/root totals and identical historical expanded views across both processes.
The full 80,513-document read now returns a bounded HTTP 413; child pagination returned all 20,128
orders in 202 pages while a payment changed, preserving the original parent and child versions.
See `OVERNIGHT-REPORT-2026-11-05.md` for current releases, limits and remaining qualification.


Release `987ebe478f48…` adds metered text blocks, bounded workflow reads and verified child-reference
copying. Its existing 20,128-order / 80,513-document probe (eight active orders, 128 warm-ups, 128
samples) measured 1.972 ms leaf runtime, 20.342 ms durable commit and 145.667 ms completion. Every
aggregate matched. This remains comparable to the preceding 142.901 ms run, not evidence of a new
throughput improvement. Separate cold-session checks passed refusal/rollback and initialized-child
copy/restore. Exact metadata and reports are retained under the isolated release prefix.
