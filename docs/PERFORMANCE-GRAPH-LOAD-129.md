# Large-graph execution through real workers — iteration 129

The preceding goal turn made progress: iteration 128's smaller publication round trip was measured,
kept, deployed and qualified. This iteration addresses a gap in the scale evidence. The retained
20,128-order / 80,513-document graph had mostly been measured through synchronous CLI draining, with
its last acceptance run on an older binary. Recreating that graph would add setup cost without
answering how the current worker loop behaves.

## Scope and method

`GraphLoadProbe` samples actual stored order links with seed 1729. It uses the running `Server`
background workers, with no manual drain during measured phases. The existing 128-order and
20,128-order synthetic accounts are exercised in small/large/large/small order. User-account documents
are not selected by the launcher. No graph migration or reinitialization runs in AWS.

Each fresh JVM uses the deployed host-links runtime manifest, eight workers, a 2 GiB maximum heap,
256 sessions, a 512 MiB estimated cache target and at most 32 uses per session. Sixteen sequential
warmups precede 64 sequential payments and 64 payments submitted by eight concurrent callers.
Both graphs have a historical fixture population of three payments per order. This probe enumerates
all root order links and validates the selected payment paths; it does not recount every payment.

Sequential completion waits for the root's exact next total after each call. Concurrent completion
starts before task submission and ends after all calls return and the root reaches the exact final
total. Each submitted call separately measures runtime and leaf publication latency; its executor
queue wait is excluded from that per-call latency but included in phase completion. Direct Core calls
exclude public HTTP, browser travel and the HTTP admission gate. Results are finite batch completion
rates, not sustained-load throughput or per-command concurrent completion latencies.

Before each phase, the probe records every selected payment/order value. After timing ends, it checks
all their expected deltas, their durable propagation acknowledgements and the agreement total. It
replays an actual command and verifies the identical durable receipt and no root-version change.
Target enumeration, lookup and baseline reads are outside timing and warm index/head caches. Session
loads and store calls are measured inside each phase. Concurrent phase totals overlap and must not
be added as a per-command timing breakdown.

## Qualification correction

The first AWS attempt failed because the first probe constructed an edition-3-only catalog. The
stored trees contain edition-2 documents, and the ordinary service supports editions 1, 2 and 3.
The failed command ended before the probe's first payment could commit and restored the main service.
Its command status, tool/source and output are retained. This was a probe configuration defect, not
a failure of the deployed multi-edition service. No data migration was used to hide it.

The corrected probe loads the same manifest as Main and records the manifest hash, supported editions
and sampled order/payment editions. Local regression preparation creates an edition-2 graph and
explicitly migrates only its root to edition 3 before running both phases; AWS never uses this local
preparation path. Fresh 8-order/256-order fixtures and the mixed-edition fixture all pass. All 25
launcher/control-script tests pass, including bounded dimensions, synthetic-account restrictions,
exact runtime pins and restoration on failure. The runtime and library artifacts remain unchanged
from deployed iteration 128, retaining their 374-host/2343-library test evidence.

## Completed AWS results

| Population / run | Sequential runtime median | Sequential leaf response median | Sequential root completion median | Concurrent 64-payment completion | Concurrent batch rate |
| --- | ---: | ---: | ---: | ---: | ---: |

| 128 orders / 1 | 5.40 ms | 36.37 ms | 137.45 ms | 2.837 s | 22.56/s |
| 20,128 orders / 2 | 5.05 ms | 33.60 ms | 128.68 ms | 3.095 s | 20.68/s |
| 20,128 orders / 3 | 4.63 ms | 34.41 ms | 132.83 ms | 2.859 s | 22.38/s |
| 128 orders / 4 | 4.74 ms | 33.32 ms | 129.26 ms | 2.747 s | 23.30/s |

All 512 measured payments, 64 warmup payments and 12 receipt replays pass. Every measured phase
checks all touched values and acknowledgements. The root remains edition 3; sampled payment
checkpoints remain edition 2. Gas is retained per call (782–785 in these samples); the underlying
stored inputs evolve, so this is not a cross-fork gas-equality experiment.

Sequential completion shows no extra size penalty in these samples. Concurrent larger-graph batch
completion is modestly slower. Its active working set is also different: 64 distinct orders and
payments per phase versus 49/51 orders and 59 payments in the smaller graph. No causal claim about
resident graph size alone follows from that comparison.

The large concurrent phases restore 130 sessions and evict 71 cached sessions, versus 80 restores
and zero evictions on the small graph. Inclusive restoration time sums to about 3.0–3.1 seconds for
the large concurrent phases; those parallel durations cannot be subtracted from batch latency.
Every measured phase makes zero immutable content/contentAll reads because inventory and target
selection warmed those paths. This does not prove constant cold lookup cost. Large concurrent phases
make 192 commits with zero conditional retries. Small concurrent phases make 204 commits with eight
conditional retries each, consistent with overlapping source updates and separate-ack fallback;
all receipt and total checks still pass.

The second job produced three passing process results, then its summary failed: an SDK shutdown
abort diagnostic followed the valid JSON result. The launcher now finds exactly one passing record
and rejects missing or ambiguous output; the process exit code must also be successful. A regression
test covers trailing diagnostics. The remaining fourth fork was run alone with the exact same host,
library and qualification JAR, rather than repeating the first three mutations. Every final sample
retains its originating command ID and S3 key. Both interrupted campaigns restored the ordinary
service; the completion-only job succeeded and verified exact host-links health.

Decision: **KEEP the qualification tooling and evidence**. Runtime/source artifacts are unchanged;
there is no new deployment or runtime speedup claimed in iteration 129. The production release remains
128. This campaign closes the gap between older synchronous large-tree tests and the current worker
loop, while leaving sustained load, multi-machine recovery and production capacity unproven.

The next controlled experiment should hold the active set constant across both graph sizes and then
profile session restoration under contention. Current counters make that a concrete question; they
do not yet identify the exact CPU/allocation/lock bottleneck. Shared-root durable commits remain a
serialization point.

Runtime: `1eab9c53bf85ce89edbd7843ac467e8b270bfc941672045cd294682dd63c3e86`.

Qualification tool: `c0eb9100ccffa1111ac25f36961e7a5c3d2afc3563bf0d2c7bbf3cc67a8025e7`.
