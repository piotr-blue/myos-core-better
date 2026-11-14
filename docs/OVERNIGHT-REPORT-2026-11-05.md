> Historical November qualification report. Release numbers and performance claims below apply
> to the recorded experiments, not the current service. Start with the maintained
> [architecture](ARCHITECTURE.md) and [test strategy](TESTING.md). Referenced external run artifacts
> are identified explicitly when they are not part of this repository.

# Implementation report — 2026-11-05

The alternative library and durable host work, including an AWS agreement tree containing 80,513
documents. The tested size-dependent payment slowdown is fixed. Full MyOS/native-service parity and
production qualification remain unfinished. This report describes the current checkpoint; the
library's [iteration log](../../blue-java-better/docs/ITERATIONS-2026-11.md) retains every historical
experiment and keep/revert decision.

## Current code and deployment

- Deployed runtime: **134**, with **383 host tests** passing. The library is unchanged from 132
  (2364 tests passed there). API Keys now provides the original Core-style create/list/revoke flow
  and account-scoped bearer access. Its deployed browser check passed, including revocation and
  cookie-free document calls. [The parity map](CONSOLE-PARITY.md) tracks the remaining requested
  full bootstrap, supplied Timelines, matching detail views and clause tutorial. Direct exact
  serialization preserves checkpoint bytes and every checked identity/event/gas/projection. Local
  checkpoint export allocation is 30–41% lower and export time 10–20% lower; whole-transition timings
  are mixed, so no end-to-end speedup is claimed. See [the qualification](PERFORMANCE-EXACT-SERIALIZATION-132.md).
- The host retains release 128's combined embedding-page/document publication, whose separate paired
  AWS recovery comparison improved about 18% on average with extra transactional write capacity.
  See [that measured decision](PERFORMANCE-CONTENT-PUBLICATION-128.md).
- Both restoration experiments from 131 remain reverted. Five boundary tests and the fixed-checkpoint
  probe remain; neither a representation change nor a verification shortcut is deployed. See
  [the rejected candidates](PERFORMANCE-CHECKPOINT-RESTORE-131.md).
- Latest scale qualification: **132**, exact deployed runtime and real background workers. With the
  same 16 active orders, sequential payment-to-root completion is112–124 ms for 128 stored orders and
  107–116 ms for 20,128. Concurrent 64-payment batches take 2.77–2.87 seconds in both. Every touched value,
  acknowledgement and duplicate receipt passes. Full inventory is untimed and remains size-dependent;
  active-cache work and shared-parent contention are separate costs.
- Console: https://d2ya9daeiur7nz.cloudfront.net. The user's 512-document agreement remains intact
  (128 orders, 383 payments, one agreement; version 247/paidTotal 4956 at readback). Payment flow,
  operation dialogs, immutable History/Events, Timeline creation/signed content and reader Sharing
  passed browser qualification again in 134. API-key changes leave the document runtime and
  persistence classes byte-identical to 132. The earlier 132 QA payment showed 6.63 ms runtime, 248 ms browser
  response and 527 ms through both parents; 134 is a functional console check, not a new latency comparison. See [the walkthrough](PAYMENT-WALKTHROUGH.md).
- Release artifact `9e80cba92b4078d7ab4abf18f3f30aa88fef48ab778f20119ed9c21f9d84b396`,
  deployment command `c7860c0b-595d-4746-8465-31255e0323b2`: Success. The automatic-directory
  Main/DynamoDB restart qualification last passed in 128; private signed reader access was rechecked
  in 132. Ordinary console business execution remains host-links: Timeline append does not
  automatically invoke a document operation.
- Stored editions 1, 2 and 3 remain supported; creation uses edition 3-preview. Combined source
  acknowledgements are enabled. Operator-configured automatic source acquisition and durable
  scheduling are qualified separately. Iteration 133 additionally qualified real private reader
  custody on AWS, automatic child/parent execution, process restart, revocation and historical access.
  The cold first entry took 1.859 seconds in one functional sample; this is not a private-input latency
  benchmark. The temporary secret and policy were removed. See [the report](PRIVATE-TIMELINE-CUSTODY-133.md).
  Owner-console source adoption/routing, compact consumer replay and receiving-policy rebase remain open.
- Base stack `myos-better-20261105`, account `581571886327`, eu-west-1: c7i.large (2 vCPUs, 4 GiB),
  encrypted DynamoDB with point-in-time recovery, private S3 and Systems Manager operator access.
  Service: 8 workers, 256 sessions, 512 MiB estimated cache target, 32 uses. The console stack adds
  Cognito and CloudFront with a private VPC origin. Operator routes remain on loopback; owner routes
  require login and native reader routes enforce signed source/access rules.

The primary resources remain running. Review date **2026-11-12** is a tag, not automatic deletion.
Temporary secondary machines and their stacks were deleted after earlier qualification. The original
Java/Python/shared package sources and original MyOS deployment were not modified by this effort.
Existing credentials were used without copying them into these projects. No GitHub access was used.

## Execution model and semantic boundary

`blue-java-better` provides canonical Language, verified package loading, BEX and Contracts. Public
native step/channel registrations bind exact identities; importing package data does not install
executable code. There is no Caffeine dependency. Canonical Contracts supports atomic in-memory
embedded/shared/cyclic graphs and canonical receipts.

The separate `DocumentRuntime` lets the host commit one document at a time:

```text
Payment commits → Order receives its event and commits → Agreement receives that event and commits
```

DynamoDB conditional transactions retain state, immutable history, command deduplication and durable
outbox progress. Immutable radix pages change the affected embedding link without rewriting siblings.
There is no SQLite or graph-wide proof construction on this path. Parent progress is eventual: a
parent rejection cannot roll back its already committed child. Rejection is audited and redrivable.

The host's local identity excludes embedding links and is not a canonical graph proof. Canonical
identities remain available through explicit complete projections. Tested business behavior matches
canonical execution; universal graph-metadata/proof equivalence is not claimed. Fresh child trees
initialize bottom-up, preserving tested canonical initial-event order. The durable host does not yet
attach existing shared documents or coordinate distributed cycles.

## Earlier controlled comparison: three transactions instead of five

Release78 combines a parent publication with acknowledgment of its child's outbox where they can
share one conditional transaction. Existing receipts, contention, fan-out and transaction bounds retain
the separate fallback. A three-document payment path normally needs three transactions instead of five.
Local tests cover complete state/history/event equality, conflicting publication, lost responses,
source advancement, restart, fan-out and transaction limits.

Same-artifact AWS comparisons used 128 warmups and 128 measured payments per run, seed1729, selected
from actual stored order links. Run order was large separate/combined, then small combined/separate.
Every payment checked its expected agreement total.

| Stored population | Acknowledgment mode | Completion median | Completion p95 | Transactions/payment |
| --- | --- | ---: | ---: | ---: |
| 128 orders / 513 documents | Separate | 149.069 ms | 168.340 ms | 5 |
| 128 orders / 513 documents | Combined | 109.350 ms | 137.861 ms | 3 |
| 20,128 orders / 80,513 documents | Separate | 127.578 ms | 151.253 ms | 5 |
| 20,128 orders / 80,513 documents | Combined | 103.614 ms | 124.510 ms | 3 |

All 512 measured payments and their warmups passed aggregate checks. Every measured window reported
zero storage failures/retries. Median improvement was 26.6% small / 18.8% large in these bounded runs.
**Decision: keep**, with the explicit fallback and comparison switch.

The CLI used 64 sessions / 128 MiB estimated cache / 32 uses and synchronous draining, not concurrent
HTTP load. Both large runs had 260 session loads, 124 hits and 256 capacity evictions; their samples
hit 128 distinct orders/payments. Small runs hit 82 orders / 109 payments. Target enumeration and lookup
were outside timing. Enumeration itself is size-dependent and warms index pages. These are samples,
not operations on every document. A larger graph being faster is cloud variation, not a benefit of size.

Evidence: comparison, counters and provenance (`../reports/overnight-2026-11-05/ack-comparison-78.json`, historical external artifact; not bundled).
Raw sample arrays and command statuses are retained alongside it. No sustained-throughput or latency
SLA follows; authored scans and contention on a shared parent still cost work.

## Why the earlier slowdown disappeared

The parent retained internal lifetime counters proportional to embedded-document count. Edition3
moves stale-delivery fencing to the host's durable link versions/generations. Explicit, conditionally
published migration removed only redundant counters while preserving body identity, embedding index,
old history and receipts. No implicit migration occurs.

On the large root, the affected internal snapshot fell from 373,056 to 401 bytes. The controlled
release69 comparison, with eight active orders and identical binary, reduced large-tree median full
completion from 254.458 to 120.036 ms. After migration, small/large payment runtime medians were
1.926/1.982 ms. A later full-population release76 comparison measured 1.816/1.806 ms runtime and
145.618/126.214 ms full completion, again showing no size penalty in those samples.

Real order additions remained slightly slower on the large tree: 91.049 ms small / 101.084 ms large
median, with all initialization totals checked. The large case made 1–3 cold immutable-index reads
(median 7.279 ms) versus none in the small case. This is bounded indexed lookup, not execution or
copying of every sibling. Full setup of the original 20,000-order graph took 37.8 minutes; that cost
has not been eliminated. Growth brought the total to 20,128 orders and 80,513 documents.

Edition3 data requires an edition3-capable rollback binary. Old edition1/2 histories retain their own
semantics; restoring an older binary alone is not a valid downgrade strategy.

## Independent-process correctness and recovery

The latest qualification ran **two independent JVMs on one c7i.large**, both using the exact deployed
edition3 profile and combined acknowledgment policy, sharing DynamoDB. Four accounts contained 28
documents. Concurrent submissions intentionally raced duplicate and distinct commands.

- 128 submitted calls represented 64 unique commands; duplicate receipts matched exactly.
- Every payment, order and agreement total matched; both processes returned identical pinned views.
- Reusing a committed command identifier for changed input was rejected.
- Both temporary services stopped; the main 8-worker service passed exact-edition/policy health.

This bounded contention run took 6.814 seconds, with a 487.674 ms median submitted-call latency.
It includes duplicate races, cold JVMs, storage and contention on shared CPU/documents. It is not a
steady-state throughput benchmark, and its latency is not interchangeable with the sequential table.
Command `bac0dca6-3370-495f-bf4d-122639960504` succeeded. See the
compact report (`../reports/overnight-2026-11-05/ack-multi-process-80-result.jsonl`, historical external artifact; not bundled).

Earlier two-machine qualification demonstrated another machine recovering a committed leaf after
its producer stopped. That used an older release; it is not evidence that the current release has
passed a new two-machine failure campaign. Details and cleanup are in [DISTRIBUTED-QUALIFICATION.md](DISTRIBUTED-QUALIFICATION.md).
Local tests separately cover crash/restart, conditional races, lost responses, source advancement,
parent failure/redrive, and mixed-edition migration.

## Library, clauses and bounded reads

Retained optimizations share immutable verified heads and normalized recipes, cache compiled BEX and
immutable definition fields, eliminate repeated descendant compaction, reduce copies and reuse empty
schema observations. Controlled comparisons preserve exact gas arrays and identities.

- Nested BEX conversion at depth64 fell from about260 to10 microseconds and 1.49 MB to51 KB allocated
  in its isolated JMH stage. This is not a 26× whole-document speedup.
- Empty immutable schemas reduced measured Hello initialization allocation about11.1% in iteration77.
- Iteration79 avoids allocating unused mutable schema drafts. Four alternating JVM runs measured
  baseline110.90/113.10 ms and195.23/195.01 MB versus candidate111.34/109.57 ms and190.89/190.05 MB.
  Allocation improved about2.4%; timing overlaps. **Keep**, but initialization still allocates too much.
  This local benchmark includes admission/init/checkpoint/MemoryStore publication, not AWS I/O.

Published Agreements/Connections installation and Hello/Activation are tested, including approval,
message/audit updates, emitted events, and rejection preserving complete identity/version. The latest
published Hello AWS run used release86 and passed exact state, receipt and emitted-event checks. Two published
reseller/order cases previously failed in both old and new Java with the same binding mismatch;
shared packages were not changed to conceal it.

Version-pinned pagination read all 20,128 order links across 202 pages while a payment changed; old
child versions stayed stable. A full oversized expansion was refused without state changes. Read
budgets constrain documents, constructed values, text and depth, including workflow reads and cold
restoration. Optional projection-cache exhaustion omits the cache instead of rejecting execution.
These bounds do not yet reserve all active-work heap.

## Remaining work and next direction

1. Full native Timeline, Coordination, Mandates and Time services, including exact admission,
   ordering and completeness guarantees. The current Time publication requires these; a wall-clock
   callback would not implement its protocol. Generic scoped durable admission is implemented locally
   in iteration82; native protocol-specific preparation and full service semantics remain.
2. Full original MyOS application parity, shared-account permissions, participant/Timeline bootstrap,
   SSE and webhooks. Iteration104 adds a working authenticated document console with direct runtime
   initialization; it does not implement all original interfaces or native protocols.
3. Existing shared-document attachment and distributed cycles, with topology fencing and retained
   version semantics. The combined-acknowledgment shortcut relies on the current fresh-tree boundary.
4. Weighted active-work admission, retained history/content garbage collection, broader contention,
   soak and failure qualification, and production operational controls.
5. Public remote-executor/continuation API, adversarial/conformance qualification and simplification
   of adapted canonical internals. The public facade alone does not establish Caffeine-level quality.

Commands currently create at most 16 fresh documents and must fit DynamoDB transaction/record limits.
The next implementation work should advance these missing runtime/host capabilities, alongside
measured initialization and contention improvements. The objective remains active.

## Iteration81 library capability: native admission

`NativeChannel.verifyAdmission` now connects the existing pre-execution admission hook to a public,
thread-confined `NativeAdmission` context. Separate immutable `AdmissionEvidence` is keyed by exact
channel type; missing/unused evidence fails before external selection. The verifier sees exact
snapshot, payload and execution-input identities, timestamp, declaration/generation/checkpoint rows,
and a shared root-local gas allowance. It returns a bounded immutable projection; channel handlers
receive only that verified projection. The projection participates in the invocation commitment.

Canonical sessions and all three document-local editions accept explicit evidence. Command-identified
local calls expose their command-envelope identity separately from the unchanged payload identity.
No permit is cached across calls. Portable chunk reconstruction reverifies the original evidence and
retains exact gas/receipt parity. Evidence is validated against the private record domain at capture,
preventing locally accepted data from later failing solely because it cannot be transported for replay.
The optional admission fee is positive; non-verifying channels preserve their existing counter rules.

Fourteen new cases qualify HMAC-bound snapshot/input/payload/time/generation/channel rejection,
missing/unused evidence, independent mutable inputs, immutable verified output, context lifetime,
all local editions, checkpoint deduplication, cold restore/compaction, shared gas, callback failure,
output bounds, builder isolation, concurrent sessions, projection commitments and detached replay.
Full strict builds pass2,137 library/98 host tests. Initialization does not perform external admission.
No new package or native Timeline/Time semantics is silently enabled.

The paired non-verifying Hello probe (25 warmups/30 samples in each of four JVMs) compares baseline79
106.37/115.86ms and190.61/190.19MB with candidate81 108.47/109.47ms and190.91/190.43MB. Gas/identity
arrays match exactly. Timing overlaps and allocation differs about0.14%; no performance gain or
meaningful regression is established. Decision: keep the capability with its explicitly tested boundary.

This is a native admission SPI, not a complete native-service implementation. Snapshot/member IDs are
not stable managed account/document IDs. Iteration82 below adds a host-derived scope commitment and
durable evidence transport. Ordered Timeline/Time eligibility, effect provenance, production binding
configuration and timer progress remain integration work. See docs/NATIVE-ADMISSION.md for usage and
protocol obligations. Release81 is now deployed; the host still does not enable a native Timeline/Time service.


## Release81 checkpoint qualification

Deployment and both AWS probes completed successfully. The main service was restored with its exact
edition3 profile, combined acknowledgments and eight workers after the Hello qualification.

The large tree still contains20,128 orders/80,513 documents. After128 warmups,32 uniformly selected
stored-link payments passed all aggregate checks, with96 conditional commits (three per payment),
zero storage failures/retries,2.317ms median measured payment runtime and 122.283ms median completion.
This is an unpaired, smaller smoke sample. It does not establish a regression or improvement relative
to release78's103.614ms controlled result. Target inventory/lookup were outside operation timing.

Published Hello/Activation passed initialization, approval, message/audit update, rejection preserving
complete local identity/version, retained approval input/result and exact event payload assertions.
Single observed approve/hello/reject HTTP steps took167.109/153.884/53.498ms; these are not warmed
latency distributions. The qualification uses edition1 explicitly, then restores the edition3 service.
The compact summary retains the hash and source location of the29MB full report, not that large trace.

A separate four-JVM local payment comparison used4096 warmups/2048 samples per JVM, restoring every32
calls outside timing, with no storage or parent work. Baseline79 medians were0.379/0.374ms and candidate81
0.390/0.379ms. Exact gas, final identity and payment result matched. These overlapping observations
show no established material regression; they are not evidence for the cost of native verification.

Timing definitions: `localPaymentMillis` starts after session acquisition and excludes cold restore;
`leafCommitMillis`, despite its legacy name, measures the complete leaf call through publication,
including reads and execution, not just a DynamoDB transaction. `endToEndMillis` includes parent
propagation and verification of the root total. None of these is interchangeable with pure CPU time.

Evidence: release81 manifest (`../reports/overnight-2026-11-05/manifest-81.json`, historical external artifact; not bundled),
Hello assertions (`../reports/overnight-2026-11-05/hello-summary-81.json`, historical external artifact; not bundled), and
isolated payment comparison (`../reports/overnight-2026-11-05/admission-leaf-summary-81.json`, historical external artifact; not bundled).


## 82. Scoped durable native ingress and command ownership — keep locally

The library now supports bulk immutable `AdmissionEvidence.copyOf`/`asMap`, an explicit
`DocumentRuntime.Session.admit` result distinguishing verified authority from failed admission, and
current-document `requiresNativeAdmission` introspection. Ordinary `process`/`call` identities remain
unchanged. A required admission with no active verifier fails before workflows. Trusted detached child
delivery does not request unrelated external permits or select external native channels.

The companion host adds `/documents/admit` behind a stable `MYOS_ADMISSION_NAMESPACE` configuration.
It derives a domain-separated command commitment from authoritative namespace/account/document/command,
retains bounded original event/time/evidence/protocol data, rejects conflicting completed input, and
re-verifies after conditional state conflicts. Exact duplicates reconcile existing receipts after
restart or uncertain publication. Installed protocol code must verify the scoped input commitment;
no proof field supplies its own trusted target. No production native protocol/key is installed by
setting the namespace or loading a package.

Review found and closed command-ID poisoning: an invalid proof must not claim the ID before its valid
owner. Unverified rejection now uses a check-only state-revision fence with no receipt write. Verified
business failures retain their original input and result. Legacy evidence-free process/call endpoints
also refuse protected documents without claiming IDs. The DynamoDB adapter and in-memory store now
both accept1..100 writes/checks, including check-only transactions; the fake-SDK test checks exact
key/revision, absence of mutations and conditional-conflict handling.

Tests cover real signed cross-account/document/namespace replay with identical snapshots, all three
local editions, caller mutation, JSON transport, cold restart, lost response, re-verification after a
competing publication, command poisoning, authorized workflow failure, unsupported verifiers and
child-to-native-parent delivery. All2,139 library/109 host tests and11 hermetic launcher checks pass.
Strict formatting/compiler/Javadoc and qualification compilation pass. One initial failure fixture
incorrectly assumed replacing an absent field must fail; it was corrected to an explicit authored
`$failIf`. That changed the test trigger, not language patch semantics.

A four-JVM non-native leaf probe with4096 warmups/2048 samples had an anomalous candidate fork:
baseline0.477/0.371ms, candidate0.997/0.364ms. The raw evidence is retained. A bounded repeat with
16384 warmups/4096 samples measured baseline0.353/0.355ms and candidate0.358/0.352ms. Allocation medians
were895680/900648 bytes versus904200/897136 bytes. Both comparisons retain exact gas arrays, final
identity and payment amount. No material steady-state regression or speedup is established; the
short-warmup variability is not hidden. These probes exclude storage, parent work and session restore;
they do not measure production native-verifier latency.

Decision: keep the capability and boundary corrections. Source iteration82 is locally qualified;
AWS remains release81. Next is live qualification of scoped admission/check-only fencing and native
service integration, not a claim that published Timeline/Time protocols now work. The existing runtime
manifest still does not install a production native verifier. Full authentication, preparation,
ordering/closure, effect provenance, scheduling/cancellation and production qualification remain.


## 83. Live signed admission and deployed large-tree checkpoint — keep

Added a separately packaged NativeAdmissionProbe and a pinned AWS launcher. The probe creates fresh
Ed25519 keys, binds the public key into a synthetic semantics identity, and exercises the actual
DynamoDB adapter. Its private key is never printed or persisted. The launcher verifies deployed
host/library/tool digests, serializes against deployment, bounds execution time and checks that the
main service PID and exact health profile are unchanged. Two additional hermetic launcher tests pass.

Release83 deploys source82 runtime plus these qualification tools. Deployment, native qualification
and large-tree smoke all returned authoritative SSM Success. The main service is healthy on edition3
with combined acknowledgments and eight workers; nativeAdmission remains false because no production
native protocol has been installed. A namespace alone cannot install executable semantics.

The signed probe passed forgery, cross-account/document/command/time/namespace replay, condition-only
rejection without command ownership, legacy-ingress poisoning, valid reuse of a rejected ID, exact
events/state, retained proof, cold receipt reuse, changed-proof conflicts and stale-proof rejection.
After16 warmups,32 measured updates took32.044ms median durable call time, including7.075ms runtime.
Snapshot read/signature preparation was separately5.861ms median. All32 commits succeeded without
retry; gas was720 each. Final count49/version50. This is a small correctness qualification, not a
steady-state benchmark. Cold reconstruction occurs inside the same JVM with the same in-memory key;
it does not qualify production key persistence or the published Timeline/Time protocols.

The20,128-order/80,513-document tree passed128 warmups and32 uniformly selected stored-link payments.
Payment runtime median2.453ms, full leaf call24.997ms, full propagation/root verification109.063ms.
Every aggregate check passed, with96 commits and zero storage failures/retries. Target enumeration
took11.850s outside operation timing; target lookup was also excluded. The result is an unpaired
smoke check, not evidence of improvement over release78's controlled comparison.

Current recorded strict checks:2,139 library/109 host tests,13 launcher checks. Latest published
Hello/Activation live qualification remains release81. Artifact/provenance/raw arrays are retained
in reports/overnight-2026-11-05/manifest-83.json. No binaries, keys or large traces are archived there.
Decision: keep the deployment and reusable qualification tools. Full MyOS/native service integration,
large single-document initialization costs and sustained production qualification remain unfinished.


## 84. Keep a validation recursion path without copying ancestor sets — keep

A fresh JFR allocation profile of Hello host creation identified HashSet copies in ContractWork.visit.
Validation now pushes/pops recipe keys in one call-local ancestor set, with finally cleanup. Completed
siblings still use the separate pending/proven proof ledger; cyclic backedges keep their old behavior.
No validation predicate, traversal order, logical charge or admission rule changes.

Full strict library/host builds pass2,139/109 tests. Four isolated Hello JVMs with25 warmups/30 samples
retain exact gas arrays and initialized identities. Baseline82 medians112.705/110.579ms and
190.212/192.239MB; candidate84 113.841/107.100ms and185.328/185.289MB. Allocation falls roughly3.1%
across the fork medians; timing overlaps, so no established latency speedup is claimed. Keep the
simpler per-walk state. This reduces validation's physical ancestor storage from repeated depth-sized
copies to one active path; it does not remove the logical validation work or recursion depth limit.
AWS remains release83. See initialization-summary-84.json and the raw samples in the evidence archive.


## 85. Hash the identity bytes already rendered for charging — keep

Contracts identity establishment rendered the same canonical helper twice: once to count UTF-8 bytes
for the SHA-256 gas blocks and again for hashing. It now hashes the already rendered bytes through
a package-private LanguageCore.hashBytes helper. No digest, serialization, gas ordering or identity
rule changes; there is no new cache or public API. SHA padding arithmetic remains long-valued.

Full strict builds pass2,139 library/109 host tests. Four isolated Hello JVMs use25 warmups/30 samples
each and retain every exact gas and initialized identity. Baseline84 medians106.411/106.606ms and
185.113/185.509MB; candidate85 105.403/102.996ms and177.863/178.106MB. Allocation falls about4.0%,
with a modest local timing improvement in this bounded sample, not an AWS throughput claim. Keep the
removed duplicate work. AWS remains83. See initialization-summary-85.json and raw sample arrays.


## 86. Use a typed, context-local validation memo key — keep

Language validation previously concatenated a complete recipe key, validation goal and occurrence
role for every visit. A small ValidationKey record now retains those three immutable components.
Memo entries remain context-local; no resolver, semantic rule or host-budget check is skipped.
Two focused regression cases verify that a definition or optional absent field cannot pre-certify
a missing root payload. LanguageValidationTest owns this boundary in the test strategy.

All2,141 library/109 host tests and strict build checks pass. Four isolated Hello JVMs with25 warmups
and30 samples retain exact gas and initialized identities. Baseline85 medians103.245/103.408ms and
177.501/177.195MB; candidate86 102.342/103.504ms and174.772/174.208MB. Allocation falls about1.6%;
timing overlaps. Keep the clearer structured key and reduced copying, without a latency claim.
Across these sequential comparisons initialization remains roughly100ms and174MB, still substantial.
These changes do not establish full native-service parity or sustained production throughput.


## Release86 AWS checkpoint — qualification passed

Deployed artifact b3db2baa472cdd7eeeda40afe8f422eca9995fb43b93a0386a218bc34356a5f9 using command
3bebc2ed-3bc7-4ed0-a752-d3a19a44f6c3. The installed library digest matches the locally measured86
candidate. Deployment, published Hello/Activation and the large-tree probe all returned SSM Success.
Main service health confirms durable edition3, support for1/2/3, combined acknowledgments and8 workers.
No production native service was added; latest live synthetic admission qualification remains83.

Hello/Activation retained exact inactive initialization, approval, message/audit update, failed edit
identity/version, original receipt/input and emitted Terms Accepted/Hello Said payload assertions.
Single HTTP approve/hello/reject observations were196.761/153.213/70.420ms, not warmed distributions.
The large tree passed128 warmups and32 uniformly selected payment operations across20,128 orders and
80,513 documents. Medians:1.989ms payment runtime,27.243ms complete durable leaf call,126.073ms full
propagation including root verification. All96 commits succeeded with zero failures/retries. Final
paidTotal113358. Enumeration took14.938s outside operation timing; target lookup was also excluded.

This unpaired smoke result does not establish an AWS speedup or regression: full completion was
109.063ms on83 and 126.073ms on86, while runtime was2.453/1.989ms. Cloud/storage and scheduling costs
remain visible. The controlled small/large comparison is still release78's evidence. Local84–86
allocation improvements are supported by paired JVM samples, not by these cloud medians.

Evidence: reports/overnight-2026-11-05/manifest-84-86.json. Compact Hello assertions retain the full
report hash/S3 key; neither its29MB trace nor JFR/binaries are copied into the project evidence.
The fresh86 allocation profile identifies eager successful-tariff error strings and boxed/BigInteger
validation in primitive gas debits as the next candidates. They are not changed in86. Full native
protocols, original MyOS application integration, distributed sharing/cycles and production-scale
concurrency qualification remain required work.


## 87. Keep primitive gas checks primitive — keep

BEX tariff lookup now constructs its unknown-counter message only on failure. Contracts integer
validation has a primitive long path; boxed Integer/Long values delegate to it rather than allocating
BigInteger values and successful error-context varargs. BigInteger records retain exact conversion
and overflow behavior. No tariff, ordering, gas allowance or public API changed.

Fourteen additional cases qualify primitive/record bounds, invalid input categories, oversized
integers, unknown tariffs and retention of the first rejected shared/local charge. Full strict builds
pass 2,155 library tests and 109 host tests. Four isolated Hello JVMs (25 warmups/30 samples) retain
exact gas and initialized identities. Baseline86 medians 103.221/104.223 ms and 176.139/173.649 MB;
candidate87 101.039/102.180 ms and 174.078/172.355 MB. Allocation reduction is about 1% across fork
medians, smaller than the sampled profile suggested. Keep the simpler successful paths and measured
small reduction; no broad latency or AWS gain is established. AWS remains release86.


## 88. Pinned provider signature authentication — keep as a protocol component

Added ProviderSignatures for Coordination 1.0.0-deterministic.2. Adoption verifies the exact package
pin and provider ancestry, reads inherited endpoint/key fields and binds that provider identity to
the published provider-object, completeness-guarantee and reader-request signature domains. Wire
hex lengths/case and canonical key coordinates are checked; JDK Ed25519 performs point validation
and signature verification. Verifiers share no mutable Signature instance. No private key, signing
oracle, network client, permission grant or runtime registration is introduced.

Twenty-five cases cover three independent RFC 8032 vectors, actual published entry/guarantee/read
objects, expansion-preserved identity, inherited provider fields, changed object/provider/domain,
reusable reader queries, invalid endpoints, malformed/noncanonical keys/signatures, wrong package
and definition/type selection, and concurrent verification. All 2,180 library/109 host tests and
strict checks pass. The 675 KB self-contained package is a test fixture, with identity/checksum and
provenance recorded; tests never read the sibling repository. No performance claim is made for this
new component. The measured gas change is independently recorded in iteration87.

Decision: keep the independently qualified authentication component as groundwork for native
provider integration. It intentionally does not claim full provider/evidence/feeder conformance:
exact envelope/request/source binding, allowed object kinds, required-source completeness, access
state and executable Timeline/Time/Coordination/Mandates semantics still need implementation.
A public method authenticating a signature must not be confused with admission of its entire claim.

The protocol review identified a performance constraint for the next layer: recursively admitting
every predecessor would reopen an ever-growing history. Prefix advancement must inspect only a new
source suffix plus authenticated prior-prefix facts, while preserving missing-content failures and
typed field/ancestry checks. Arbitrary caller-supplied hashes are not such facts. This layer has not
yet been implemented. AWS remains release86; it has no production native verifier installed.
See docs/PROVIDER-AUTHENTICATION.md for API usage, precise obligations and primary references.


## 89. Incremental signed timeline prefixes — keep

Added the public TimelineVerifier with opaque Prefix/Result/Entry values. It accepts the actual
published Timeline Range Evidence and Timeline Successor Evidence, verifies exact provider/source
bindings and signatures, strict timestamps, adjacency, exclusive bounds, original messages and
complete final heads. Both proof forms share one prefix model. An authenticated successor remains
outside the closed prefix until a later bound closes it; later evidence cannot skip or replace it.
Empty-prefix assertions, missing bodies, old-bound replay and retained-head contradictions are distinct.

Supplied message fields are selected separately from contextual declaration metadata. Domain defaults
and explicitly supplied unvalued definitions work without adding the base property's documentation
to their identities. Recognized Operation Requests reuse the existing Contracts shape/token/document
validator. Actual declared type ancestry selects protocol roles. The adversarial Actor case exposed
that Language's structural subtype relation was too permissive for these roles; provider adoption
was corrected too, with a structurally compatible but unrelated provider regression case.

Strict validation of new content uses private invocation-local facts for the previously established
head. Those facts are created only after validation, cannot be supplied by a wire hash and cannot
leak into ordinary Language admission. Additional contextual constraints still run. The prefix
retains detached headers for at most the closed head and authenticated successor; it retains no
predecessor bodies or growing history list. Host code must retain content and serialize progress.
There is no unchecked prefix restore API and no native runtime is enabled by this component.

All 2,204 library/109 host tests and strict checks pass. The public API suite is grouped into prefix
advancement, integrity boundaries, original content and locality, including concurrent independent
calls. Locality tests remove all historical bodies before the next advancement. A separate three-JVM
probe uses128 warmups and 128 measured one-entry proofs per size, with the same issued prefix in each
measurement window. Every proof fetches exactly one new entry and returns the expected identity/bound.

| Prior entries | Fork0 median | Fork1 median | Fork2 median |
| --- | ---: | ---: | ---: |
| 1 | 3.299 ms | 1.120 ms | 1.135 ms |
| 128 | 2.290 ms | 1.139 ms | 1.748 ms |
| 1,024 | 1.413 ms | 1.192 ms | 1.108 ms |
| 10,000 | 1.153 ms | 1.514 ms | 1.128 ms |

The first measured groups show JVM warmup/order effects; their slower values are retained. Allocation
medians range3.36–3.78 MB, with no history-size growth. Timing includes signature verification,
scoped strict validation and the one new-body fetch/parse. It excludes initial history establishment,
transport construction, network/storage and document execution. This is evidence of history locality,
not a production latency SLA or a claim that arbitrarily large new messages take constant time.

Because this adds a scoped mechanism to the shared Language kernel, a four-JVM Hello regression
comparison also ran: baseline88 102.286/100.475 ms and172.150/172.375 MB, candidate89 101.887/102.095 ms
and174.868/173.005 MB. Gas arrays and all initialized identities match. Latencies overlap; the small
allocation increase (about1% across fork medians) is reported, not called an optimization. Retain the
verified history capability; no initialization or AWS speedup is claimed.

AWS remains release86. No deployment or live native conformance claim was made in this iteration.
Required next work includes response/request correlation, pagination, current and historical access,
required-source ordering/closure, trusted durable prefix provenance, evidence retention/equivocation
and integration with the native feeder/services. The new API is documented in TIMELINE-VERIFICATION.md;
measurements and provenance are archived in manifest-89.json.


## 90. Signed discovery and contiguous read pagination — keep

Added TimelineReader with immutable Read/Page values, typed signed failures and shared prefix
authority with TimelineVerifier. It authenticates the published Provider Descriptor, exact request
correlation, checkpoint guarantees and inline response/page/cursor boundaries. Positive page limits
use the provider's arbitrary-precision advertisement; baseline operations do not need to appear in
the optional-operation list. Private reader authorization and HTTP transport remain host duties.

Checkpoint-only reads distinguish a signed claim from an acquired contiguous prefix. Unknown heads
are not fetched or seeded as validated facts. Every page extends the exact established predecessor,
pins its checkpoint, preserves a known successor and checks timestamps, limits and final heads.
Only a terminal chain, or a refreshed bound for an already established identical head, issues a
completed prefix. Empty checkpoint claims cannot contradict established entries. Continuations keep
only the starting prefix and latest boundary, not old pages or their snapshots.

Review caught and fixed a nonfinal-page validation gap: deferring the entries field skipped its list
schema. A failing regression reproduced this; list and item refinements now run before a page can be
discarded. Extra header fields remain validated. Unknown checkpoint-head links are inspected only as
partial transport and never certify Language admission. The shared Language validation algorithm has
an internal occurrence-role overload; existing callers still use the same root role. Timeline URL
validation now rejects the trailing slash already forbidden by the published specification.

Full strict staged builds pass 2,237 library tests and 109 host tests with no failures or skips.
Thirty-three reader cases are grouped by discovery/requests, checkpoint/failure claims, pagination
continuity and wire/selected content. They include arbitrary-precision limits, timestamp seeks,
mixed successor/read advancement, malformed signatures/correlation, contradictory claims and long
pagination with unavailable old bodies. No Java test reads Python or a sibling package checkout.

The standalone public-API probe ran three isolated JVMs. Each size/mode uses128 warmups and 128 final
one-entry page verifications against the same issued continuation. Historical records are removed;
every measured verification fetches zero old/future bodies. Parsing-plus-verification medians:

| Previous pages | Fork0 | Fork1 | Fork2 |
| --- | ---: | ---: | ---: |
| 1 | 2.145 ms | 2.026 ms | 2.064 ms |
| 128 | 2.115 ms | 2.194 ms | 2.063 ms |
| 1,024 | 2.092 ms | 2.086 ms | 2.084 ms |

Prepared-response verification medians are1.984–2.231ms. Parsing-plus-verification allocates about
5.53–5.55MB per page, with no growth with previous-page count. This is still substantial allocation.
The probe excludes earlier-page establishment, request construction, network/storage and execution;
it repeats one final page per window and does not measure sequential appends or production throughput.
Raw arrays, parse modes, wire sizes and exact artifact digest are retained in reader-locality-summary-90.json.

A four-JVM Hello regression comparison uses25 warmups/30 samples. Baseline89 medians103.563/99.332ms
and173.830/172.798MB; candidate90 103.927/100.278ms and172.677/172.603MB. All exact gas arrays and
initialized identities match. Timing overlaps; this is a compatibility check, not an initialization
speedup claim. Initialization still needs separate work.

Decision: keep the qualified reader component. AWS remains release86 and no new native service has
been deployed or enabled. Exact-entry/content retrieval, current and historical access, durable
prefix/evidence provenance, required-source closure, native feeder/services, original MyOS app
integration and distributed sharing/cycles remain unfinished. Next work should connect these protocol
components to executable services and the durable host, not mistake isolated verification for full
native conformance. Usage and boundaries are in docs/TIMELINE-READING.md.


## 91. Executable Timeline Access and first-update locality — keep

Added TimelineAccess, a serialized process-local replay of one confirmed access document on the
adopted provider's reserved public control timeline. Exact package/program/channel checks precede
execution. Agent publication initializes the actual document through Contracts. Subsequent standard
Operation Requests target the original identity and execute the published BEX replacement program
with the complete original entry. The exact Timeline/Public Timeline channel bindings and Dispatch
are selected internally; this does not install a general native service into the host.

Each applicable disposition retains its actual Contracts transition and after-document. Unrelated
entries advance source coverage without a fabricated invocation. Every accepted replacement must
succeed; a failed suffix leaves the previously published view intact. Historical states are validated
individually: review reproduced a bug where a later valid replacement hid an invalid intermediate
timeline URL. The failing case now rejects the whole suffix, preserving the earlier established view.

An exploratory warmed benchmark hid first-update growth. After importing1/128/1024 control entries,
the first operation reopened old predecessor validation and discovered previously imported content
for receipt retention. A proposed admission bypass passed equality checks but did not remove the
cost; it was reverted. The retained implementation scopes the opaque verifier-issued prefix head
across Contracts resolver views and revokes that scope afterward. Strict new-content/refinement
checks and ordinary public admission remain intact. Each verified new suffix is retained once,
including unrelated entries, so a later operation need not rediscover old content.

Four alternating JVMs compare the original staged access implementation with the final candidate;
second-pair size order is reversed. Each size records the first real update separately, then16 real
sequential warmups/16 measured updates. The first update is included in those16 warmups. The request
contains one key and one timeline, not a large permission set.

| Prior entries | First update baseline (two JVMs) | First update candidate (two JVMs) | Baseline allocation | Candidate allocation |
| --- | --- | --- | --- | --- |
| 1 | 31.839 / 6.766 ms | 30.869 / 6.785 ms | 16.17 / 13.89 MB | 16.08 / 13.78 MB |
| 128 | 11.321 / 12.176 ms | 7.436 / 7.283 ms | 26.00 / 25.97 MB | 13.79 / 13.81 MB |
| 1,024 | 44.666 / 80.484 ms | 7.677 / 29.016 ms | 110.41 / 114.19 MB | 13.74 / 15.84 MB |

The first group in each JVM is colder; all observations remain reported. Candidate warmed medians
are5.547–7.420ms with13.13–13.27MB allocated. Exact gas arrays, receipt commitments and resulting
document identities match in every compared run. This establishes removal of the tested first-update
history growth, not a production latency SLA. Timing includes proof verification, actual BEX/Contracts,
receipt/event/view projection; it excludes history import, transport preparation, network and storage.
The process-local tracker retains content/history and is not a bounded durable replay cache.

All2,257 library/109 host tests and strict checks pass. Twenty access cases are grouped into
confirmation, executed replacements and integrity/atomicity. They cover altered definitions,
foreign-provider timelines, duplicate/invalid initial lists, exact emitted events, historical state,
human actors, forged proofs, duplicate publications, failed batches and private prefix fact lifetime.
A public regression removes old records: replay can use its issued prefix, but ordinary Language
admission still fails on that missing history before and after replay.

A separate four-JVM Hello regression compares actual90 with91,25 warmups/30 samples. Baseline
102.649/101.914ms and173.001/172.840MB; candidate104.130/103.420ms and172.625/173.162MB. Exact gas
and initialized identities match. No initialization improvement is claimed; allocation is essentially
unchanged and the observed timing difference is about1–2% in this bounded comparison.

Decision: keep the qualified capability, historical-state fix and measured prefix/retention reuse;
revert the ineffective bypass. The published duplicate-list BEX algorithm still contains nested scans
and can require quadratic work for large permission sets. No publication or gas rule was rewritten.
AWS remains86, with no production native service enabled. Current grant union/enforcement, HTTP
acquisition, trusted durable history/provenance, required-source closure, feeder/services and the
original MyOS application integration remain unfinished. See TIMELINE-ACCESS.md and manifest-91.json.


## Iteration92: durable source acquisition

The host now has a conditional timeline archive retaining signed evidence, exact/cyclic content,
immutable history and duplicate-acquisition receipts. Warm importers verify only new batches;
fresh importers replay retained evidence instead of trusting serialized bounds. Old content views
are pinned to their acquisition. Competing writers, lost responses, malformed claims, tenant isolation
and cyclic content have focused tests. The shared radix extraction preserves old embedding-page
hashes. Full library/host builds pass 2,261/130 tests; launcher checks were last run in 83.

Three-JVM MemoryStore measurements show warm acquisition medians 1.61–2.83ms for 1/128/1,024 prior
acquisitions, with no old history reads. Catch-up of one batch reads one old anchor and the new batch.
Fresh restoration of 1,024 batches still takes 1.40–1.43 seconds and allocates 4.28–4.32GB cumulatively.
That cold path is an explicit remaining bottleneck, not a production-ready recovery strategy. The
Hello regression retains exact gas/identity and roughly 100–104ms initialization with 173 MB allocation.

This archive is internal and not wired to document execution or HTTP. Current private-read grants,
historical access enforcement, required-source merging, native feeder/admission and efficient durable
restoration remain necessary. AWS stays 86. See [acquisition design](TIMELINE-ACQUISITION.md) and the
library's iteration92 log for sample arrays, limits and the keep decision.


## 93. Atomic source consumption and session-renewal locality — keep

The host now merges retained entries from an explicit fixed required-source set, ordered by timestamp,
timeline BlueId and entry BlueId. Every source must close strictly beyond the candidate timestamp;
quiet/missing/equal-bound sources block it. Cursors retain batch offsets and normalize only empty or
exhausted batches. Frozen content and coverage are bound to receiver state and progress revisions.
Successful actual BEX execution, document history/events/receipt and source disposition publish in one
conditional transaction. Lost responses, competing consumers, restart, altered inputs, inapplicable
coverage and business rejection are tested. Source-set changes fail closed pending historical rebase.
This is internal integration, not an automatic feeder or published native-channel implementation.

A median-only diagnostic hid a serious renewal cost. With 1,024 earlier signed/executed entries, each
32-use session replacement reopened predecessor validation: 85–94ms and390–393MB allocated. Keeping
opaque TimelineVerifier.Entry headers allows DocumentRuntime.Session.admit(command, entry, reader,
evidence) to reuse the exact validated original within one invocation. Original identity/time and
native authorization remain intact; message content still validates on demand. Scoped facts are not
serialized into checkpoints or ordinary Language documents. The host retains at most 256 recent
headers; eviction falls back to ordinary validation. This is a count bound, not a byte-weighted cache.

Four alternating baseline/candidate JVMs reverse size order for pair two. Every historical entry is
actually signed, acquired, selected and executed. After1/128/1024 prior entries,16 sequential warmups
and16 samples use BEX message amounts. The measured call includes native verification, BEX, session
loading/checkpoint export and atomic JSON-boundary MemoryStore publication. Acquisition, signing,
selection and readback are separate. Each measured window contains one session renewal.

| Prior entries | Renewal before (two JVMs) | Renewal after (two JVMs) | Allocated before | Allocated after |
| --- | --- | --- | --- | --- |
| 1 | 14.549 /5.003ms | 8.278 /1.947ms | 15.48 /12.39MB | 5.68 /3.89MB |
| 128 | 18.082 /15.201ms | 3.061 /1.878ms | 51.02 /54.05MB | 4.77 /3.75MB |
| 1,024 | 84.804 /93.550ms | 2.126 /2.300ms | 392.79 /390.26MB | 5.20 /5.21MB |

Candidate median calls range1.20–3.51ms, including the colder first group. Some ordinary-call allocation
increases: at1024 entries, about2.13→2.94MB, while eliminating the large renewal allocation. Exact gas,
checkpoint identities and checkpoint sizes match every baseline/candidate window. Final checkpoints
are14,656/14,665/14,671bytes for the three sizes. These are local sequential measurements, not AWS
latency, native protocol conformance or a throughput guarantee. Old backlog entries outside the header
window can still reopen history. Cold archive replay remains linear and was not optimized here.

Full strict builds pass2,263 library/147 host tests, zero failures/errors/skips. Two new public library
cases remove predecessor bodies before restored-session execution, retain exact gas/checkpoints and
prove missing native evidence still fails. Ordinary Language admission still refuses the missing
history afterward. Seventeen host cases cover source ordering/publication and actual execution,
including within-batch restart, frozen original-message reads and cache eviction.

Four separate Hello JVMs compare actual92 to93 with25 warmups/30 samples. Baseline medians107.266/
109.517ms and173.30/176.96MB; candidate108.423/107.820ms and176.13/176.61MB. Exact gas/identities match;
timing/allocation ranges overlap and no initialization gain is claimed. Initialization remains costly.

Decision: keep the source/result atomicity and measured renewal fix. AWS remains86; no native service
was enabled. Required-source derivation, historical rebase/access, published channel bindings, lineage,
transport/provider services, efficient authenticated cold restoration, arbitrary old backlog, memory
weight/retention, original MyOS integration and distributed sharing/cycles remain unfinished. The host
TIMELINE-PROCESSING.md and manifest-93.json retain the detailed method, raw arrays and capability limits.


## 94. Exact Timeline channel adapter and authenticated access reuse — keep

`TimelineChannels.verifiedBy(publication, historyVerifier)` now selects the two exact published
Coordination channel bindings. Contracts and DocumentRuntime builders install both and Dispatch
before initialization, checking all conflicts before mutation. The generic NativeChannel tariff is
unchanged. The published adapter charges only accept:1 and refuses a mismatched runtime before calling
the host verifier. It requires the pinned Coordination publication and an explicit verifier.

The immutable request distinguishes the original entry from a local command envelope and includes
the exact receiving snapshot, timestamp, declarations, generations, headers and checkpoints. The
verifier returns exactly one assessment per unchanged receiver. Missing/duplicate/altered rows and
inconsistent operation audiences reject before execution. Missing history is a rejection, never a
guessed INAPPLICABLE. The adapter builds the closed published commitment, sorts by code-point order,
includes negative assessments/reasons and omits raw transport data. The existing authenticated
Timeline Access tracker supplies its verified-history policy through this same adapter, removing
its duplicated native-binding/projection implementation.

This is a protocol adapter and trusted host SPI, not a complete native service. General receiving
history, source closure/derivation, target lineage, private access and feeder integration remain
unfinished. The host's TimelineInputs qualification still uses a separate HMAC test channel. A
user-written verifier can be wrong; callback presence alone does not establish authorization.

Eleven new cases cover exact binding fields/tariffs, runtime mismatch, Unicode assessment ordering,
original-entry identity, restoration, atomic registration conflicts, invalid receiver assessments,
negative receipt sensitivity and transport omission. The existing20 actual signed Access cases also
pass. Full strict builds pass2,274 library/147 host tests, zero failures/errors/skips.

The first final-artifact comparison used four alternating JVMs with1/128/1024 prior entries,
16 sequential warmups and16 measured signed updates. Exact gas, receipts and after-documents matched,
but first-workload timing varied: at1024, baseline7.18/11.44ms and candidate6.82/15.77ms.
These arrays are retained, not replaced by a more flattering run. A separate bounded comparison with
96 warmups/32 samples and reversed1/1024 order tested warmed behavior:

| Prior entries | Baseline medians, two JVMs | Adapter medians, two JVMs | Baseline allocation | Adapter allocation |
| --- | --- | --- | --- | --- |
| 1 | 5.584 /5.534ms | 5.627 /5.580ms | 13.398 /13.482MB | 13.536 /13.559MB |
| 1,024 | 5.447 /5.609ms | 5.243 /5.793ms | 13.394 /13.511MB | 13.547 /13.587MB |

All exact gas, receipts and document arrays match. Warmed timing overlaps; adapter allocation is
roughly0.6–1.1% higher in these paired observations. No speedup is claimed. The measured call includes
signature/history verification, actual Contracts/BEX and result projection. History establishment,
proof construction, transport and storage are excluded. The retained tracker is not a cold-recovery
or bounded-memory benchmark. First-use measurements remain separately recorded.

The independent Hello comparison uses25 warmups/30 samples in four alternating JVMs. Baseline
101.568/103.859ms and174.54/174.31MB compares with102.211/103.826ms and173.07/174.14MB. Exact gas and
initialized identities match; ranges overlap. This adds no established initialization improvement.

Decision: keep the reusable exact profile and removal of duplicate access-specific plumbing, with
its measured allocation tradeoff. No gas or published package change was made. AWS remains86;
general native services were not enabled. Cold recovery (~1.4s/4.3GB cumulative allocation at1024
entries in the earlier archive measurement), old backlog validation, full MyOS integration,
distributed sharing/cycles and production qualification remain. Usage is in TIMELINE-CHANNELS.md;
manifest-94.json binds the raw arrays, executed probes, fixture, source qualification and artifacts.


## 95. Document lineage prerequisite — kept locally

Native-enabled publication now indexes authored initial and committed local identities atomically
with state/history. Historical membership resolves through three reads rather than scanning prior
versions. Coverage revisions detect old-writer gaps; arbitrary content does not establish lineage.
Prepared child attachments retain their pre-initialization identity. Strict builds pass2274 library
and162 host tests. Scope, compatibility and migration limitations are in
[DOCUMENT-LINEAGE.md](DOCUMENT-LINEAGE.md).

The paired source-execution benchmark retains exact gas, identities and checkpoint sizes. At1024
prior entries, baseline medians1.028/1.025ms become1.058/1.116ms with about1.5–1.6% extra allocation.
This necessary index has publication cost, not a claimed speedup. Namespace-disabled Hello remains
about101–103ms/172–173MB, with matching gas/identity arrays. Native protocol integration remains
unfinished: the source-execution probe still uses a separate test-only HMAC verifier. No AWS service
was enabled or redeployed;86 remains the last verified deployment. Evidence is in manifest-95.json.


## 96. Real published Timeline ingress — kept locally

The internal host ingress now derives required public sources from receiving declarations, freezes
strict completeness/order, recognizes operations through Contracts and resolves historical targets
through established lineage. Both published channel bindings execute real signed entries and BEX;
this path uses no test HMAC permit. Immutable receiving inspection shares execution's semantics,
including composites and raw observers. Original Timeline order is retained alongside local command
checkpoints, atomically with source progress and document publication. Same-command races recover
the winner's receipt. Child events update independently committed parents through the durable outbox.

Full strict builds pass2279 library/178 host tests. Three JVMs after64 warmups/32 samples measure full
local ingress medians1.66–3.11ms across1/128/1024 prior entries; at1024,1.679/1.797/1.766ms with3.42–3.69ms
renewals. These include preparation, native admission, real BEX and JSON-boundary MemoryStore
publication, excluding signing/acquisition/readback. Every result checks business totals/events; exact
gas, identities and checkpoints agree across forks. The first workload remains warmer-sensitive;
this is a new capability measurement, not a before/after speedup or AWS guarantee. A separate paired
source regression shows about5% allocation cost for the new original-checkpoint index. Hello remains
about102–103ms/172.5MB. Raw arrays, probes and provenance are bound by manifest-96.json.

Private sources wait for historical access. Changes to source sets or receiving lifetimes require
explicit historical rebase, including an actor change on an unchanged timeline; rebase is unfinished.
The main HTTP service does not install the internal ingress automatically. Provider/service wiring,
private revocation/restoration, historical embedding obligations, full MyOS interfaces, Mandates/Time,
distributed sharing/cycles, cold recovery and production qualification remain. AWS remains86.
See [TIMELINE-INGRESS.md](TIMELINE-INGRESS.md) for the actual integration and boundaries.


## 97. Live published Timeline execution and current AWS deployment — keep

The tested 96 library and host are now deployed, with separately packaged qualification tooling added
in 97. Service artifact b5db4d6ac43aa3111b9b3b7ead1a74a2c78809c3f05c4441ad7a432bcc609cac binds library
SHA a0ff7dc1faa984257e42ea8f1099d3d721e4f688959509e27d555f02a642dfac and host SHA
d66336e5dfa76ac1602249f54e3e4af7dda014ba23a2489eb407680f5efeb46d. No runtime algorithm, gas rule or
package publication changed in 97. Actual/staged builds match; existing Java suites remain 2279 / 178,
and all 14 launcher checks pass. Unchanged tests/build inputs can reuse Gradle outputs.

`PublishedTimelineProbe` generates fresh Ed25519 provider keys in memory and uses the exact published
Coordination package. The qualification JAR and its package fixture are outside the service artifact;
no private key is persisted. Both published Timeline channel bindings execute actual BEX. Assertions
cover signature rejection, missing/equal completeness waits, forged serialized admission claims,
actor-positive and negative outcomes, exact emitted amounts, independent child/parent publication,
repeat selection/drain, cold archive/session reconstruction and original-order continuation afterward.
The launcher pins all executable digests and temporarily stops the main workers so the synthetic
native child is handled only by its configured runtime. An EXIT trap restores the original profile
on success or failure. This does not enable an automatic feeder in the main service.

The local 8-warmup / 8-sample run passed. AWS used 16 warmups / 32 measured entries on new synthetic documents:

| Published-Timeline measurement | Reported median |
| --- | ---: |
| Signed source acquisition, excluding construction/signing | 42.156ms |
| Native ingress preparation through durable child publication | 66.551ms |
| Contracts runtime within that call | 6.220ms |
| Ingress plus parent propagation and quiescence check | 162.441ms |

Each sample has 2 successful commits, 13 individual reads, 5 batched reads, 2 content publications and
16–17 work-index reads; every storage failure/retry counter is zero. Gas is 752 throughout. Restart
reconstructed the archive and inspected its idle position in 1353.518 ms after 49 entries, then executed
the next original entry exactly once. Final child version 51 and parent total 350 were verified. The
probe reports the upper middle sample for an even-sized median, consistent with its recorded arrays.
It is a bounded live correctness check, not a warmed throughput/SLA or before/after speedup claim.

The propagation timing includes a final empty-queue discovery round. The large-tree benchmark below
instead stops when the expected root total is visible; those completion measurements are not directly
comparable. Inspection identifies Propagation.drain's idle fallback as the cause of 16 work-shard reads
in that final round. Server workers also call this drain method when idle. This is an optimization
candidate, not yet fixed: durable discovery, fairness and cold recovery must be preserved.

SSM commands all reached Success:

- deployment 11b521fe-975f-4a40-8e99-e774c2adff0c;
- published Timeline 754e5dc9-1db5-4f70-8d35-2312e1ec4185;
- Hello/Activation 534e78a3-553b-4b3e-8cf6-1b7e49faea52;
- large tree 818e0dd9-6353-4271-86cb-11456252a92b.

The Hello example preserves inactive initialization, approval/activation, message/audit values,
rejection identity/version, original receipt/input and exact Terms Accepted/Hello Said event payloads.
Single HTTP approve/hello/reject observations 144.204/127.652/63.536ms are not latency distributions.

The existing 20,128-order/80,513-document tree passed 128 warmups and 32 measured payments selected from
all stored order links. Medians 2.417 ms runtime, 26.969 ms durable leaf call and 122.508 ms through agreement
verification; 96 successful commits, zero failures/retries, 32 distinct orders/payments. Final total 120078.
Target enumeration 13.956s and target lookup are excluded from operation timing. This unpaired smoke
check does not establish improvement over 86 or replace the controlled 78 comparisons. The service is
healthy afterward on edition 3-preview, support for 1/2/3, combined acknowledgments and 8 workers, with
nativeAdmission=false.

Decision: keep the isolated published-profile qualification and deploy the already tested runtime.
Full MyOS application/API integration, configured provider services, private historical access/rebase,
Mandates/Time, distributed sharing/cycles, cold recovery/retention, initialization allocation and
production qualification remain unfinished. Detailed results and artifact pins are in manifest-97.json.


## 98. Shared worker-discovery experiment — revert policy, keep measurement tooling

An attempted background polling policy shared one discovery pass per250ms across workers, queued
index hints separately from execution and preserved explicit full draining. It passed187 host tests,
including eight new cases for shared scan bounds, stale rows, failed reads, overflow, fairness and
concurrency, plus the existing two-host case under both APIs. Correctness checks alone did not settle
whether the policy was worth keeping.

Four local JVMs ran baseline/candidate/candidate/baseline with8 actual Server workers,8 warmup payments,
4 seconds settling,3 seconds idle observation,4 local payments and4 unhinted payments per fork.
A separate Core instance published unhinted payments; its ready queue was not shared with the
consumer. Both variants used the same document fixture, timestamps and exact-root-total completion
condition. The probe records which host/library JAR actually supplied its classes.

| Measurement | Baseline, two JVMs | Rejected candidate, two JVMs |
| --- | ---: | ---: |
| Idle work reads/second | 97.659 /108.468 | 31.915 /15.966 |
| Local completion median | 11.610 /11.763ms | 16.855 /19.919ms |
| Unhinted completion median | 35.141 /108.078ms | 296.620 /284.819ms |
| Unhinted completion after leaf commit | 30.995 /97.648ms | 289.156 /275.041ms |

Every operation's business result passed, and exact gas/version arrays and final totals matched.
The lower read count came with a concrete delay: the new interval gates unhinted discovery, combined
with the existing worker backoff. These small local samples are not AWS or production latency
estimates. They are sufficient evidence to reject a policy that explicitly introduces delay and
repeats that regression in both forks. No CPU improvement is claimed from the short idle window.

The production changes and their candidate-only tests were removed. The retained host JAR is again
exactly d66336e5dfa76ac1602249f54e3e4af7dda014ba23a2489eb407680f5efeb46d, matching deployed97; library
SHA remains a0ff7dc1faa984257e42ea8f1099d3d721e4f688959509e27d555f02a642dfac. The final strict build
passes the unchanged178 host cases; the2279 library results still describe unchanged library code.
All16 launcher checks pass. Rejected source patch,187-test build evidence and sample arrays remain
in ignored evidence; they are not maintained runtime APIs or dormant production options.

Keep WorkerDiscoveryProbe and the explicit comparison launcher. The launcher pins both host JARs,
the tool and all common dependencies, excludes any fallback host JAR, takes the deployment lock and
restores the main service on exit. Its shell-generation/validation tests pass; it has NOT been run
against AWS. No AWS deployment or live qualification ran in98. Release97 remains the last verified
service, with its real Timeline/Hello/80,513-document checks unchanged.

Next investigate repeated admission of the host-generated two-string NativeInput identity envelope.
The Language API already exposes pure identity preparation, so this may avoid redundant validation
without changing any business admission or native authority. It is a hypothesis to measure, not a
retained optimization. Cold Timeline replay, initialization allocation, full MyOS/native integration,
private/rebase, distributed sharing/cycles and production qualification remain unfinished.
See WORKER-DISCOVERY.md and manifest-98.json for reproducible scope and the keep/revert evidence.

## 99. Native input envelope identity — keep allocation reduction, no general latency claim

NativeInput.identity now prepares the BlueId of its host-generated command/event-identity strings
without admitting the envelope as a Language value. Event validation, signature verification,
native admission, publication and business execution are unchanged. A focused test proves equality
with the previous fully admitted envelope and distinct identities when namespace, account, document,
command or event changes. Full strict builds pass179 host tests; unchanged library results remain2279.

Four JVMs run baseline/candidate/candidate/baseline, reversing history-size order in the second pair.
For each1/128/1024-entry prelude,64 warmups precede32 samples through actual published public Timeline
admission, BEX, session renewal, checkpoint export and atomic JSON-boundary MemoryStore publication.
Signing, source acquisition and readback are outside the timed call; acquisition is reported
separately. Every operation checks events/totals; exact gas, identities and checkpoint sizes match.

| Earlier entries | Baseline medians, ascending / descending | Candidate medians, ascending / descending |
| --- | --- | --- |
| 1 | 3.638 /1.687ms | 2.987 /1.673ms |
| 128 | 2.259 /1.765ms | 2.206 /2.033ms |
| 1024 | 1.842 /1.743ms | 1.721 /1.731ms |

Median allocation falls0.124–0.173MB per matched workload/order, about2.9–4.1%; JVM warm-up effects
remain visible. Keep the simpler appropriate API and repeated allocation reduction. Mixed timings
do not establish an overall latency improvement. The library JAR remains unchanged; host JAR is
395c2a48d6ccda229f261bcdcd912aa617e582dfe9b67d5a6fc6ed12014cee02.
No AWS action occurred in99. Deployment97 and its published Timeline/Hello/large-tree evidence remain
the latest live qualification. Cold history reconstruction is the next substantive profiling target;
full MyOS integration and the previously listed production gaps remain open. See manifest-99.

## 100. Duplicate immutable evidence reuse — revised, kept and deployed

The shared LanguageMap rebuilt persistent-map paths even when a retained immutable value was being
registered again. It now preserves the existing root for identical values. Empty/self bulk inputs
are skipped; empty destinations retain efficient bulk construction; other bulk updates replace only
changed entries. Distinct equal objects still replace the old instance. Null/absent keys, old views,
all verification, gas and work counters retain their behavior. Full staged and actual strict builds
pass2281 library and179 host tests.

An initial callback-based bulk copy regressed Hello initialization by about2–4ms in paired runs. It
was replaced before promotion. The final revision passes the four-JVM baseline/candidate/candidate/
baseline comparison, including reversed history-size order. At1024 prior signed batches, cold restore
is1448.600/1469.626→1304.384/1369.735ms and cumulative allocation4363.189/4284.345→3493.712/3502.089MB:
roughly7–10% less time and18–20% less allocation. Every signed batch is still verified. Warm acquisition
medians improve1.867/1.844→1.685/1.670ms in the same local experiment. Small cold cases are mixed, and
the result is not a claim of constant-time history recovery.

Final Hello initialization uses25 warmups/30 samples per JVM:100.323/100.911→99.734/100.107ms and
172.154/172.440→170.209/171.005MB. Gas and initialized identities match. Archive comparisons check
all restored bounds/heads, original entry/evidence IDs, committed content roots and logical history/
publication work. An overly strict initial byte-for-byte temporary-page assertion was replaced by
committed-root checks: insertion order can change unreachable intermediate radix pages. Physical
counts/bytes remain in raw evidence. JFR was used only for diagnosis, not the paired measurements.
The library iteration log contains all small/large rows and the superseded-candidate observations.

The retained runtime was deployed to the existing private stack with the same8 workers,256 sessions,
512MiB cache estimate,32-use lifetime, edition3-preview and combined acknowledgments. Main-service
native admission remains disabled; published Timeline qualification supplies its temporary provider
configuration separately. Deployment and all three live checks reached Success:

- deployment6fb953a1-9fc7-4789-b933-030cc3b5db03;
- signed Timeline4bbc94ed-0d2b-4c7e-9ac2-349dcb1adea3;
- Hello25800fab-6b46-45dc-bfa2-af83a47300f1;
- large tree666eb1d8-75b5-4bd1-a150-ba56279014ca.

| AWS measurement | Reported median |
| --- | ---: |
| Large-tree payment runtime | 2.553ms |
| Large-tree durable leaf call | 27.065ms |
| Payment through verified agreement update | 113.317ms |
| Published native ingress through child commit | 63.985ms |
| Native Contracts runtime within that call | 5.843ms |
| Native ingress through parent plus quiescence | 161.439ms |
| Separate signed-source acquisition | 43.273ms |

The20,128-order/80,513-document run has128 warmups and32 measured operations on32 distinct orders/
payments. All expected root totals pass, final126798;96 commits, zero failures/retries. Target
enumeration12.324s and target lookup are excluded from operation timings. Native qualification uses
16 warmups/32 samples, gas752, two commits per sample and no failures/retries. Every recorded signature,
closure, actor, forged-input, exact-event, idempotency and restart assertion passes. Cold archive/session
reconstruction after49 entries still costs1348.706ms; the local map improvement has not removed durable
replay I/O. The following original entry executes exactly once, final child version51 and parent350.
The native completion condition includes16–17 final-discovery work reads; it differs from the large
tree's expected-total completion. These are bounded unpaired AWS checks, not before/after speedups.

Published Hello preserves inactive initialization, approval activation, message/audit values,
rejection identity/version, exact original input/receipt and Terms Accepted/Hello Said payloads.
Single HTTP approve/hello/reject observations196.783/103.350/59.140ms are not latency distributions.
The temporary Hello service was stopped; the main service is healthy after all checks.

Pins: library293ea1139bbe5863f2e3b46de611fe584c4dd8b8b1951807ea63d7c7e92da3a1,
host395c2a48d6ccda229f261bcdcd912aa617e582dfe9b67d5a6fc6ed12014cee02,
distributionb2e750530399937fa4d6ec94c6daf6e53ac03019c815e13cc72d67466eaef8b8.
No package/specification change, automatic native feeder or original MyOS API parity is claimed.
Full integration, private/rebase, distributed sharing/cycles, initialization, cold recovery and
production qualification remain open. Manifest-100 binds the compact evidence and keep/revise decision.


## 101. Bounded durable history read-ahead — keep after paired AWS recovery

Fresh TimelineArchive recovery previously read every history row, batch manifest and batch body
individually. It now reads immutable history in windows of at most32 positions, prefetching each
manifest and only its first64KiB chunk. One-batch warm catch-up keeps the old single-read path.
AtomicStore's default content batch preserves compatibility with memory/fault stores; DynamoStore
uses strongly consistent BatchGetItem with deduplication and bounded retry of unprocessed keys.
Every page remains tenant-scoped, size-bounded and hash-checked. Existing32MiB page-cache bounds remain.

Read-ahead never establishes authority. Every batch still passes TimelineVerifier in order with its
own historical content root. Recovery pins the initial committed head, checks exact history keys and
versions, and publishes its cached verified prefix only after all signatures, entries, bounds,
successors and final roots agree. Tests cover a source advancing during a65-entry recovery, missing
later history, corrupt prefetch, partial DynamoDB responses, interruption and later-chunk corruption.
The staged strict host build passes186 cases;18 hermetic qualification-launcher checks and both local
recovery/native probe preflights pass. Library code is unchanged from100 (2281 passing cases).

Four local baseline/candidate/candidate/baseline JVMs retain exact restored heads, all committed
content roots, original identities and logical history work. Their MemoryStore path cannot measure
network savings. At1024 entries local cold medians/observations are1331.433/1359.384ms baseline versus
1364.467/1362.868ms candidate, with3.50–3.53GB cumulative allocation for both: no CPU speedup claim.
Hello initialization medians100.775/101.606ms versus100.388/100.835ms overlap; gas/identities match.
The final qualification JAR differs from the paired local tool only because a local preflight mode
was added to TimelineRecoveryProbe; the measured HostInitTiming and ColdTimelineProbe are unchanged.

The isolated AWS comparison seeded49- and 128-entry histories once with the baseline, then used the
same public fixture and stored signed evidence for four JVMs in ABBA order. Each source/fork performs
one unmeasured recovery and three samples, each with a fresh Repository and TimelineArchive. The SDK
and JVM are warmed; source-definition admission/setup are outside timing. This is archive recovery,
not whole-service cold startup or steady-state event execution.

| History | Baseline fork medians | Candidate fork medians |
| --- | ---: | ---: |
|49 acquisitions |1314.639 /1452.210ms |1030.645 /1073.089ms |
|128 acquisitions |2318.995 /2722.958ms |1850.083 /1942.893ms |

Matched pairs improve roughly20–29%. At49 entries50 single row reads plus308 single content reads
become1 row read,2 history batches,210 content reads and4 content batches (358→217 store calls).
At128 entries129+860 single reads become1+4+604+8 (989→617). Transport retries, if any, would be inside
a batch call; these are store-call counts, not a direct HTTP request trace. All samples restore the
same signed heads/completeness bounds and report zero storage failures. Median main-thread cumulative
allocation falls220–221→213–214MB and528–531→499–501MB respectively. Other evidence/index pages are
still demand-loaded, and signature/content verification still scales with retained history.

SSM comparison3adb7abb-add7-48e4-8cae-e815b45ce169 reached Success and restored the main100 service/profile.
Candidate hostcdfa2a61d8516707d3ba60f9f43c3b65db8b58e47c23fb2069e922c1ab153264;
common library293ea1139bbe5863f2e3b46de611fe584c4dd8b8b1951807ea63d7c7e92da3a1;
final tool7907aaf82e7b8262498294041661f846882ff61c4d025297ad2286c4ae0c8253.
Keep the bounded transport change; no gas, package, signature, authority or specification change.
Full MyOS/native feeder integration, private/rebase, Mandates/Time, distributed sharing/cycles,
initialization allocation, retention and production qualification remain unfinished.


Iteration101 was promoted to both actual projects; their host build passes186 tests and its runtime/
qualification JAR hashes match the measured stage. Library2281 results apply to unchanged code.
Distributionb130a3fae8415fcfdbe0579738a96e61fce351f3f11a8755ac45b5113472f88a is deployed. All live commands reached Success:

- deploymenta7c0c3b2-2013-448a-b099-7ba6f6ff11e7;
- published Timelined1bedafa-494c-41db-b194-13b25c61eba0;
- Hello4599dbe0-24de-48d8-a494-7484daba4272;
- large tree56880cf8-9737-4052-b918-258fb9de6a02.

The post-deploy Timeline check uses16 warmups/32 samples, two commits/sample, gas752, no recorded
storage failures/retries, exact events and original-order restart continuation. Medians: ingress
67.369ms, runtime5.365ms, through parent plus quiescence170.213ms,
separate acquisition46.141ms. Cold archive/session recovery after49 entries is942.106ms.
These are unpaired acceptance observations; the controlled same-history comparison above supports
the optimization decision. Final child51/parent350 and the following exactly-once entry pass.

Published Hello/Activation preserves initialization, activation, exact message/audit/events, input
receipt and rejection identity/version. Single approve/hello/reject HTTP observations are
205.870/114.838/51.539ms; they are not latency distributions.
The existing20,128-order/80,513-document tree passes128 warmups and32 measured payments across32 distinct
orders/payments: runtime2.169ms, durable leaf25.899ms and through agreement110.319ms medians.
There are96 commits, zero recorded failures/retries and finalpaidTotal133518.
Target enumeration11.930s and target lookup are separate and excluded.
The main service is healthy afterward with8 workers and nativeAdmission=false; temporary Hello stopped.
No steady-state speedup or production-throughput claim follows from these bounded smoke results.
See manifest-101 for compact raw evidence and artifact/source checks.


## 102. Trusted Timeline configuration and normal HTTP processing — keep

The normal Main/Server now loads explicitly adopted provider/timeline definitions and exposes source
head, signed-evidence acquisition and one-entry processing. HTTP selects an account-local alias;
provider keys, trust and the required source set are not request-controlled. Exact runtime receiving
declarations determine every required source. Namespace and aliases are conditionally persisted and
cannot silently change across restart. Source files have pinned BlueIds, strict UTF-8, bounded size
and confined paths. This is a trusted loopback operator API, not new application user authentication.

Startup initially rejected the full Coordination publication requirement, correctly: the selected
Timeline channel profile does not implement the entire native protocol. Package purpose now defaults
to execution (unchanged strict behavior); explicit definitions loads verified type data only. The
separate timelines section installs exact supported channel bindings and Dispatch. Full execution
requirements continue to fail closed. No canonical package, gas or signature rule changed.

The final strict staged host build passes195 tests, zero failures/errors/skips;19 hermetic launcher
checks pass. Library runtime code is unchanged from100/101 (2281 recorded passing tests). New tests
cover real HTTP execution, strict quiet-source closure, invalid signatures, missing/corrupt referenced
content, forged admission claims, parent events, restart, persistent alias/namespace changes,
conditional startup races and lost commit responses. Main shares one store with the native runtime
and closes it on failed bootstrap.

A final-artifact local probe starts actual Main and uses only HTTP, with4 warmups/8 samples. It passes
all state/event/closure/idempotency assertions. Its MemoryStore does not establish process-restart
durability. Host f717652430e8e3051e5d7d6d73aaf8d372c151fde69768b36ad28fc989ef00fd;
library293ea1139bbe5863f2e3b46de611fe584c4dd8b8b1951807ea63d7c7e92da3a1;
tool c08b7ffb11ca57bfad25760d2f0737ab64d707a619b0a09e79f823686a9d8a82.
This is integration evidence, not a paired performance optimization. The separate DynamoDB probe
will restart Main, continue the original signed history and verify parent propagation exactly once.

Decision: keep the integration; promotion/deployment evidence follows. Automatic provider HTTP
acquisition/pagination and durable receiver scheduling are the next required work. Private access,
receiving-history rebase, Mandates/Time, distributed sharing/cycles, full MyOS APIs and production
qualification remain incomplete. The static source registry is bounded to64 sources; it is a bootstrap
configuration, not the final multi-tenant registration service.


Iteration102 is promoted, deployed and qualified. The actual host build passes195 tests and its host/
library/qualification JARs match the final staged artifacts. Deploymentc08ba929-5d17-4522-b0b0-904937064607
reached Success for distribution720128ba00768a24f69de6da86048a258d3ec643471fec335841181b0e661f9a.

The real-Main HTTP/DynamoDB qualificationa0d51edc-8c2d-4df5-923b-30a04c9ab925 passed:
invalid signature does not advance history; missing/equal-bound quiet sources block; a caller cannot
choose an incomplete source set; original entries execute exactly once; parent events update totals;
acquisition retries are idempotent; stopping/restarting Main retains aliases/progress and the next
original entry executes once. Final child/parent count98. Provider signing keys stayed ephemeral.
With4 warmups/8 samples, acquisition/HTTP ingress/through-parent medians are
90.150/97.135/200.044ms; runtime within ingress 16.060ms.
This short warm-up qualification is not a steady-state or paired timing comparison. Parent completion
includes HTTP polling; these numbers are not interchangeable with the earlier direct-ingress probe.
The final-artifact MemoryStore Main probe also passed, with separate acquisition/ingress/parent
medians7.189/7.091/17.601ms and no process-restart persistence claim.

The published non-native Hello/Activation checkde97d572-74f2-49ea-b4e1-13d3d0cb1474 also reached Success and preserved
inactive initialization, approval, message/audit updates, exact event payloads and original receipt,
and rejection identity/version. Single approve/hello/reject HTTP observations are
157.793/108.669/50.026ms; not latency distributions. Its temporary service stopped. Main was restored and
healthy with8 workers, host-links3-preview, combined acknowledgments, nativeAdmission=false and
timelineProcessing=false. Test sources belong to the temporary configured runtime; the ordinary
manifest does not adopt them. No new AWS machine was created.

Decision: **keep**. This completes explicit normal-service integration, not the automatic feeder.
The 80,513-document iteration101 evidence is retained; no large-tree or contention rerun was performed
in102 because the library/execution/storage algorithms are unchanged. New checks target the changed
startup, package-selection and HTTP/native-service boundary. See manifest-102 for source/artifact pins,
compact raw evidence and checks. Automatic acquisition/scheduling and the full remaining objective
are still unfinished.


## 104. Authenticated AWS console and document bootstrap — keep

User-directed work moved from the unfinished103 native-acquisition draft to a directly usable AWS
console. The draft is preserved separately; the deployed runtime builds on accepted102.

Reused MyOS Core's layout, YAML/JSON viewer, dialogs, tables, fonts and styling. New document screens
use the actual Better Core and DynamoDB. The public router derives account identity from a verified
Cognito ID token; it cannot select the operator API's account parameter. Sessions use Secure/HttpOnly
cookies, same-origin mutations and bounded requests. Operator routes remain private. Root creation
registers an account discovery entry in the existing transaction; subsequent payment processing does
not update a global listing. Commands retain their idempotency keys through uncertain responses.

The new edge stack owns CloudFront, a private VPC origin, restricted8088 ingress and Cognito. It uses
the existing Better instance/table; no additional EC2 host or database was created. Source and built
asset hashes bind each console distribution; deployment refuses stale frontend output.

Validation and corrections:

- 200 host tests pass, including signed-token rejection, HTTP account isolation, initialization,
  operation retries, immutable history, events and the real agreement/order/payment flow.
- Live Cognito/HTTPS checks passed for two accounts: anonymous401, cross-origin403, cross-account404,
  no caller account override, bootstrap, retry, history, child navigation and agreement total42.
- The first AWS root-list check exposed an empty DynamoDB sort-prefix query unsupported by DynamoDB.
  Fixed it to use partition-only equality for an empty prefix; added a regression test and repeated
  the full strict host build and live HTTP checks successfully.
- Final-artifact Chromium ran real sign-in, editable counter bootstrap, initialization, increment,
  event viewing and reload persistence. The complex example created four documents and total42;
  navigating to its payment and calling `record` again produced agreement total84.
- Visual inspection caught a mobile drawer obscuring content. The final build hides the closed
  drawer from rendering and interaction, and tests opening, navigation and Escape dismissal.
  Desktop and mobile screenshots were inspected after the successful final deployment.
- The final dependency audit identified a Vite6.4.4 development-server advisory. Updated the build
  dependency to6.4.5, rebuilt and redeployed; npm reports zero known vulnerabilities. No Vite server
  runs in AWS: the Java console serves the bundled static files.
- Frontend retry tests pass2 cases; TypeScript and production bundling pass. No library semantics
  change and no repeat of the large-tree benchmark. The library's previous2281 tests remain the
  unchanged baseline, not a newly run suite for this UI iteration.

Decision: **keep**, including the DynamoDB query fix and mobile correction. The console supports
real document execution, not a static demo. Bootstrapping initializes directly in the local-document
runtime; it is not yet original MyOS's participant/email-to-Timeline bootstrap. Shared permissions,
full native services, HA, retention/garbage collection and production qualification remain unfinished.
Cognito QA credentials stay in a protected local file and are excluded from promoted reports. The
new identity pool is retained on stack deletion; review date2026-11-12 does not trigger cleanup.

Evidence: iteration104 manifest (`../reports/overnight-2026-11-05/manifest-104.json`, historical external artifact; not bundled), HTTP/browser
results and screenshots beside it. [Console guide](CONSOLE.md) describes signup and both examples.


## 105. Native acquisition and durable scheduling — retain draft, not deployed

Resumed the preserved103 work on top of104, keeping the console intact. Complete signed provider
pagination, HTTPS transport and durable source/receiver wakeups were integrated in the draft. New
wake-up tests pass for lost hints, lost commit responses, paginated tenant-scoped fanout, concurrent
source arrivals, subscription removal and explicit retry after a business rejection. An optional
bounded automatic runner polls sources separately from receiver execution.

The focused Timeline suite passes78/79 cases. The remaining competing-host assertion assumes archive
position stays1; actual business execution was correct, but a subsequent empty anchored range can
publish another evidence position for unchanged coverage. Investigate that behavior and avoid useless
acquisition history before qualification. Do not weaken source closure or claim full native parity.
The user then requested their large interactive agreement and richer console. The draft is safely
retained at `/private/tmp/blue-overnight-f92j_qhw/native-wip-105/` and is not in promoted106.
Decision: **continue the draft after the user-facing console work; do not deploy it yet**.

## 106. User agreement scale demonstration and richer console — keep

The user's existing account/agreement was inspected and expanded through ordinary authenticated
operations. It initially held one order/two payments and total42. Added127 orders with three payments
each, preserving the original order: one agreement,128 orders and383 payments,512 distinct documents.
A separate paginated read inventory confirms the counts. Growth took37.6 seconds through public HTTPS
with three concurrent requests. No full graph expansion or global proof construction was used.

The paired benchmark invokes the running Main service through local HTTP on the same EC2 host, with
DynamoDB and normal background workers. Original payments before/after:16 warmups,32 measured calls.
Random follow-up:16 different orders/payments, seed1729, no dedicated warmups. Every call verifies
payment/order/agreement totals; account data is kept separate from QA accounts.

| Scenario | Runtime median | Durable leaf HTTP median | Agreement completion median | Completion p95 |
| --- | ---: | ---: | ---: | ---: |
|1 order /4 documents|4.501ms|26.307ms|127.139ms|154.831ms|
|128 orders /512 documents, same payments|2.177ms|21.568ms|102.675ms|124.032ms|
|128 orders, 16 random target orders|1.823ms|25.341ms|118.425ms|178.435ms|

These samples show no size-related slowdown, not an intrinsic benefit from graph size. JVM warm-up
and cloud variation remain possible explanations for faster later measurements. Completion includes
read polling; runtime excludes persistence and parent work. No throughput/SLA claim follows.

The console now adapts the original MyOS operation cards/dialogs, request-shape display, and version/
event navigation. addOrder supplies a fresh-key template. Payment flow displays one selected chain,
checks each parent's exact observed child version, and separates runtime from browser/network timing.
A new authenticated embedding read performs a point lookup without enumerating siblings. Tests prove
account isolation and that observed versions advance only after propagation. History starts with the
latest20 versions and pages backward; immutable state inspection remains available.

Four frontend tests cover command/account isolation, uncertain responses and reuse of the original
comparison state after a potentially committed payment. Visual/browser checks corrected a stale
headline total and ambiguous select labels; version comparisons retain integer precision. A historical
view assertion was corrected to wait for its asynchronous response rather than inspecting stale UI.
Final evidence is bound in manifest106 alongside browser screenshots and the full512-document inventory.

Final browser acceptance on the user's own agreement passes: all128 orders load through pagination,
Order128/payment0 advances0→42, the order advances0→42 and the agreement advances4914→4956
(version 247). Both observed-child-version links confirm delivery. The separate QA account passes
bootstrap, repeated payment, historical-state inspection, addOrder template initialization and mobile
navigation. The user screenshot records67.35ms leaf runtime,481ms browser response and 1063ms browser
observation after deployment; this is separate from the warmed server-local benchmark and exposes
the remaining cold/runtime and network costs rather than hiding them behind the lower medians.

Decision: **keep**. No library or execution-rule change. Native Timeline creation/browsing still needs
account-owned provider management, dynamic adoption and remaining native integration; document History
is not relabeled as a signed Timeline. Original projects, package publications and deployment remain
unchanged. Passwords are not saved; protected temporary session cookies are excluded from evidence.


## 107. Automatic public Timeline acquisition and durable scheduling — keep

Merged the preserved105 native implementation onto106, retaining the direct-embedding read and all
console improvements. `TimelineReader.Page.claimedHeadId()` exposes the signed claimed head for
bounded page collection; it does not issue a verified prefix before complete history is available.
Public HTTPS discovery and pagination run outside archive locks, with response/body/deadline bounds.
Completed reads enter the ordinary range verifier and immutable archive; no new trusted-proof shortcut
or signature exemption was introduced.

Acquisition and its source notification publish atomically. Documents publish their subscriptions
alongside their checkpoint. Durable fanout is paginated; local hints are bounded conveniences.
Conditional idle transitions preserve newer arrivals. Failed receivers require explicit retry while
other receivers continue. Automatic polling and receiver processing use separate bounded workers;
provider failures back off independently. Configuration stays opt-in and public-source-only.

The failed105 competing-host assertion exposed an unnecessary second acquisition batch. The next
read anchors after the established head, producing a different range identity for unchanged signed
coverage. After verification, equal completeness/head/successor facts now reuse the original receipt.
Tests prove no extra history after repeated cold restarts, rejection of tampered equivalent coverage,
and persistence of genuinely new successor facts even when no closed entry or time bound is added.
The focused Timeline suite passes80 tests; the strict host build passes223, and the library build
passes2281 with identical-input cache reuse where applicable.

`AutomaticTimelineProbe` launches the actual Main subprocess against a signed HTTPS provider and
performs no manual acquisition or receiver-step calls. It verifies invalid signatures, multi-page
reads, quiet-source closure, repeated-poll deduplication and child/parent effects. The first local
fixture attempt omitted the provider identity from the object-signature domain; verification correctly
rejected it. Corrected to the published domain. The first AWS fixture attempt replaced normal TLS
roots with its localhost certificate; DynamoDB TLS failed before any table data was written. Fixed
the qualification trust store to retain platform roots and add the localhost certificate. Production
TLS verification and the service JAR never include this test certificate.

The final AWS command `2db29dc5-731d-4998-a6b7-5d4375fb0a07` reached Success. It used a separate
temporary table and scoped host access while the user's console process remained running. Stopping
and restarting the candidate Main process proved that an offline arrival is acquired and applied once;
14 original occurrences produced the expected receiver/agreement count98. The successful candidate
host SHA is `a4d212fa3ef5c0e0280787df04313a2232a79b0075b89643c2a4e50282da85f4`, library SHA
`6aa10abb207b78be85ac473377d818c9e20a2434df79dbf03597efaff22551f5`.

With100ms polling, two warmups and six samples, append-to-parent observation measured657.509ms
conventional median and846.964ms nearest-rank p95. The probe's existing summary field reports the upper
middle sample693.126ms as its median; the conventional value here is recomputed from the retained raw
array. Correct that reporting convention alongside the next latency instrumentation. This includes provider signing, HTTPS discovery/reads, verification, archive/storage
publication, scheduling, execution and polling reads. The shared two-vCPU host was still serving the
console. These numbers are not a paired improvement claim or comparable to leaf-runtime timings.
The remaining automatic-path overhead needs measurement and reduction; no throughput SLA follows.

Decision: **keep** the integrated bounded public-source path and unchanged-coverage fix. Full native
and production parity remain unfinished: dynamic account-owned providers/sources and console Timeline
management, migration of older receiver subscriptions, durable continuation beyond acquisition limits,
private permissions, historical rebase, Mandates/Time, shared/distributed cycles, HA and retention.
The default public console configuration still does not adopt a native provider. See
[TIMELINE-SERVICE.md](TIMELINE-SERVICE.md) for opt-in configuration and ownership of each test layer.


The107 release is deployed successfully with the106 console assets preserved. The temporary native
qualification stack `myos-better-native107-6d860698e4` reached DELETE_COMPLETE, removing its DynamoDB
table and scoped IAM policy. Raw successful and failed attempts, fixture corrections, artifact/source
pins, build results and deployment confirmation are retained with manifest107.


## 108. Measure automatic acquisition and reuse verified discovery — keep

Added fixed-cardinality inclusive phase counters to the operator metrics endpoint. Acquisition,
verification, storage publication, fanout and execution can now be distinguished without retaining
account/document/source identifiers. The qualification captures storage and provider counters around
each measured append. Completion timing ends before extra validation reads. Both Timeline probes now
report the conventional median for an even-sized sample. Counters cover a slightly longer observation
window, overlap across phases and concurrent sources, and cannot be summed as a critical-path trace.

The instrumented baseline exposed repeated discovery: every source poll fetched, parsed and verified
its provider descriptor again. Across six AWS observations that was 30 discovery calls, averaging
39.75 ms inclusive each, in addition to 29 read calls. The new source-owned reader retains only the
verified provider/control identity and page limit for up to five minutes. Every actual response still
requires exact-request, signature and continuity verification, followed by ordinary archive admission.
Any failed attempt invalidates discovery; expiry requires a successful refresh with no stale fallback.
A reduced page limit therefore causes a safe refusal and refresh on the next attempt. No provider,
Timeline or permission identity is substituted. No spec, gas, library or worker-scheduling rule changed.

Three focused tests cover reuse with newly appended occurrences and tampered responses, exact expiry
with invalid refresh and a changed page limit, and a signed LIMIT_EXCEEDED failure followed by recovery.
The strict host build passes 226 tests. The library runtime is unchanged from 107 (2281 previously
passing tests). The real-Main local HTTPS probe passes; its median 114.04→116.85 ms is effectively bounded
by the configured 100 ms polling interval and provides no local latency improvement claim.

| Automatic public Timeline path | Instrumented baseline | Discovery reuse |
| --- | ---: | ---: |
| AWS append through parent median |638.490ms|433.721ms|
| Largest of six samples / nearest-rank p95 |664.654ms|570.407ms|
| Discovery calls during observations |30|0|
| Provider responses during observations |59|25|
| Final receiver and parent count after restart |98|98|

Both variants use two warmups, six measurements, 100 ms polling, signed HTTPS, DynamoDB and the same
shared two-vCPU host while the public console remains running. Each verifies multi-page acquisition,
invalid-signature refusal, strict quiet-source closure, duplicate-poll suppression, parent effects and
a fresh-process restart with an offline arrival. Baseline SSM `9b4b1a01-a92f-4d89-be38-2946832875ab` and
candidate `906195f1-cda5-46c6-bddc-399764ca177c` both reached Success. The observed median reduction is 32.1%;
this bounded sequential comparison is not a randomized throughput study or an SLA.

The remaining 434 ms includes configured polling delay, provider signing/HTTPS, page and range checks,
source publication, durable fanout, receiver processing and parent observation. It is not BEX runtime
and does not measure the 512-document payment console path. There are still 491 point reads and 424
work-index queries across the candidate observation windows, with repeated idle discovery and source
selection as further targets. These totals include concurrent workers and validation reads; they are
not the number of mandatory reads per business operation. The rejected iteration 98 polling change is
not reintroduced: saving reads by delaying unhinted work would be a regression. Any future scheduling
change must also preserve backlog recovery and unhinted arrival latency.

Decision: **keep** verified descriptor reuse, diagnostics and corrected timing boundaries. The two
isolated test tables and scoped IAM policies are removed after their terminal results are retained.
The ordinary console manifest still adopts no native provider. Account-owned Timeline creation,
dynamic adoption, private permissions, durable long-read continuation, historical rebase, remaining
native semantics, HA and retention remain unfinished. The user-facing payment-flow walkthrough is
updated to distinguish integrated opt-in Timeline support from the console features actually exposed.


Release `bf8627aad5f68aacfa4da9d15a0648500c1f62f0fe8e8d57a709facb85da0d53` is deployed; SSM
`76193ada-e08e-462b-9ae1-52a88bfad188` reached Success. Both temporary qualification stacks reached
DELETE_COMPLETE. The promoted host JAR matches the qualified artifact byte-for-byte. The live browser
checks pass sign-in, bootstrap, child navigation, payment-to-order-to-agreement updates, operation
dialogs, historical state and mobile navigation. Console assets remain unchanged from 106. Raw results,
comparison boundaries, separate diagnostic/optimization patches and source pins are in manifest 108.


## 109. Bounded successor catchup and prompt conflict recovery — keep

A backlog previously had to fit in one bounded read attempt: 30 seconds, 16 MiB and 100000
entries/records. Restarting the attempt from the same head could never finish a larger history.
The published provider protocol already offers the required primitive: an independently authenticated
accepted entry is a successor proof. It establishes completeness strictly before that entry's
timestamp. The entry itself stays pending. No specification change or unchecked continuation token
is needed.

`TimelineReader.Page.fetchLastEntry(request)` verifies the exact request, outer correlated response,
entry identity and reusable inner provider attestation. The host assembles ordinary successor evidence
and passes it through the existing archive verifier. It commits after at most eight pages, or earlier
to reserve resources, provided at least one earlier occurrence closes. Fresh attempts read after the
committed head, including the pending successor again. The final chunk uses ordinary range evidence.
Original signatures, continuity, limits, immutable history and source/result transaction conditions
remain mandatory. Oversized individual input and unavailable acceptance still fail safely. Cold
archive restore still verifies retained history proportionally to its size.

The longer real-Main test found a separate scheduling defect. A parent acknowledges the child's
previous event while the child prepares the next native publication. This legitimately changes WAKE,
so the conditional publication loses. The old generic exception left work indexed but unhinted;
rediscovery took roughly four seconds per conflict. The local probe reached its 60-second deadline
instead of completing. Temporary condition diagnostics identified WAKE revisions as the conflict;
that instrumentation was removed before qualification.

A specific reselection signal now returns RETRY from native ingress. The automatic worker promptly
selects a fresh receiving view and original occurrence. It never reuses the stale invocation or removes
a conditional check. A deterministic test injects the parent's acknowledgement at exactly that
publication boundary: the losing child publishes no effects or source advancement; the retry executes
once. The automatic multi-chunk regression now includes an embedded receiver and parent reaction.
Unexpected failures retain durable work and produce an actionable stack trace.

Validation: 2285 library tests and 231 host tests pass with strict compilation/formatting. Library
coverage includes inner/outer signature and identity failures, exact entry-reference requirements,
referenced attestation objects, empty pages and signed failures. Host tests cover page sizes one/two,
restart after each chunk, preserved milestones after later failure, strict resource bounds and
automatic parent effects. The actual-Main local probe completes all 41 occurrences (parent and child
both 287, receiver version 42); remaining catchup takes 1073.094ms after the first milestone, including
provider retry/poll scheduling. This is recovery evidence, not a per-operation benchmark.

AWS qualification `ddb92fba-e9bf-46ab-8279-0fd73a18e1e7` reached Success using the unchanged live console
host and an isolated DynamoDB table. Eight two-entry pages plus a separate accepted-entry proof close
15 entries at bound 16. The provider pauses, Main exits, and another entry is appended offline. A fresh
Main resumes from the stored proof and closes batches 15, 15, 11 at final bound 42. Exactly 41 occurrences
execute, receiver version 42 and both totals 287. An independent archive instance replays the retained
proof history from DynamoDB. First milestone including startup 11371.450ms; remaining catchup including
restart and 26 executions/parent updates 13070.641ms. These deliberately cold, small qualification values
are not throughput or warmed per-operation distributions and do not supersede the 512-document payment
console timings. No user document is changed by this native probe.

Decision: **keep** successor-based bounded acquisition and prompt conflict reselection. This closes
multi-attempt catchup without a new storage format or gas/Language/BEX/Contracts semantics change.
Dynamic account-owned provider adoption, console Timeline creation, private permissions, historical
source-set rebase, remaining native protocols, distributed/shared cycles, retention and production HA
remain unfinished. The ordinary console manifest still adopts no native provider. Deployment and
cleanup confirmations, failed reproductions, passing results and exact artifacts are retained in
manifest 109.


Release `cede6a74631d7482a0c805c85bada20247cf0fcb43c3689921e4b2db72034d07` is deployed; SSM
`f65cb221-ae5e-44b5-b908-274f106c78ce` reached Success with the existing console and document table.
Promoted library/host builds pass and the installed artifacts match the isolated qualification.
Temporary stack `myos-better-native109-77a411fd80` reached DELETE_COMPLETE. The default manifest
continues to report nativeAdmission=false and timelineProcessing=false; native recovery is separately
qualified through explicit provider configuration.
The live browser acceptance passes sign-in, editable YAML bootstrap, original operation dialogs,
payment/order/agreement propagation, immutable historical state, prefilled add-order requests and
mobile navigation. Console assets remain unchanged from 106. Manifest109 retains both failed stall
reproductions and the final passing checks; the temporary diagnostics are absent from the release.


## 110. Durable account-owned Timeline publication — keep, integration incomplete

Started the next missing console capability at its storage boundary. `HostedTimelines` provides
private creation, tenant-scoped listing/lookup, signed original-entry append and signed completeness
checkpoints. Provider identity/package pins survive restart. Names remain owner metadata. New
occurrences retain original exact messages, custom-type evidence, actor attribution and recognized
operation payloads. JCA owns Ed25519. The production component takes a pinned signer; it neither
creates nor persists signing keys. Main/HTTP/console wiring and production key custody are not yet
implemented, so the live console stays on the verified 109 release.

There is no global append journal. Each timeline has a conditional head; append publishes that head,
entry, identity index and command receipt atomically. Entry time is at least the durable exclusive
completeness bound and strictly later than its predecessor. A quiet checkpoint atomically preserves
its signed proof and clock fence. Competing processes reselect their own timeline state. Lost replies
recover the original receipt; a peer winning the same command with a different timestamp returns the
peer's committed occurrence. A future requested bound is refused. The final safe protocol timestamp
and exclusive bound are covered. No reader grant or Contracts admission is inferred from ownership.

The twelve new tests are grouped by owner identity, original occurrence publication and completeness
fences. Coverage includes private defaults, cross-account refusal, names/idempotency, key mismatch,
provider rebinding refusal, lost creation/append/checkpoint replies, concurrent publishers, append
versus seal, exact custom messages/operations/definitions, unavailable and oversized content, clock
rollback and exhaustion. A fresh publisher can append/seal when all 64 earlier blobs are unavailable;
reading an old entry correctly fails. This demonstrates dependency locality independently of timing.

An initial fixture incorrectly used Blue's reserved value field and was corrected to amount. A real
lost-checkpoint-reply bug compared boxed Long values against JSON-restored BigIntegers; checkpoint
recovery now compares exact integer values and content identity. The complete strict host build passes
243 tests. The library is unchanged from 109, whose 2285 tests remain passing evidence. A small result
reuse refinement avoids decoding an already-owned newly committed occurrence while preserving the
peer-winner path. Local eight-sample medians do not demonstrate a latency improvement for that change;
it is retained for eliminating redundant materialization, not claimed as a measured speedup.

| Provider publication, DynamoDB | History starts at 1 entry | History starts at 128 entries |
| --- | ---: | ---: |
| Append median |31.544ms|33.611ms|
| Append maximum of 8 |35.268ms|37.493ms|
| Checkpoint median |26.722ms|26.454ms|
| Final measured occurrence count |9|136|

Eight alternating measurements follow eight warmups. Setup/population takes 6482.538ms and is excluded
from those medians. Append excludes sealing and validation. These are direct publisher/storage calls,
not HTTP, BEX, native reception, parent propagation, large-tree scaling or an SLA. The live console
shares the existing two-vCPU host and remains running. The small difference between the two lengths
is not evidence of history-linear work; the implementation and missing-body test establish that
unrelated earlier occurrences are not read. Larger messages still cost proportionally to their own
content, and one timeline remains serialized at its head.

AWS SSM `bb294ca2-b36d-41cb-8db9-7373b7b8edea` reached Success using the isolated DynamoDB table. A
populating JVM exits; a fresh JVM loads the same ephemeral qualification key and recovers original
creation, append and checkpoint identities. It re-verifies all 136 original long-history entries through
the existing archive verifier, appends entry 137 beyond the stored completeness fence and rejects a
foreign-account lookup. The temporary key directory is deleted by the probe. It is qualification
material only, not deployed provider key management. The local MemoryStore run also passes but makes
no cross-process persistence claim.

Decision: **keep** this durable component. It advances the required provider implementation but does
not complete Timeline creation in the console. Remaining work is explicitly ordered in
[HOSTED-TIMELINES.md](HOSTED-TIMELINES.md): protected provider/control configuration, signed public/private
reads, current and historical access, durable dynamic adoption, native integration and authenticated
console controls. The selected Better Coordination publication is explicit; newer original-MyOS human
and access-authority behavior has not been silently replaced or claimed compatible. No production
service mutation is needed for an unconnected component; release 109 continues serving the user demo.


Temporary stack `myos-better-native110-b9081cad5d` reached DELETE_COMPLETE, removing the table and
scoped host policy. The promoted host build passes 243 tests and its installed artifact matches the
AWS-qualified publisher artifact. Source/evidence hashes and the unchanged 109 deployment identity
are recorded in manifest 110. No signing key or ephemeral private qualification directory is retained
in that report. The existing live console continues serving the 512-document payment example.

## 111. Authenticated Timeline console and pinned key custody — keep

Connected the durable publisher to the authenticated console. Accounts can create private Timelines,
append YAML/JSON, list bounded entry metadata and inspect an original entry with its attestation.
The existing MyOS layout, dialogs, data table and content viewer are reused. Names remain labels.
Every mutation takes its account from verified login; caller-supplied account/actor/time/access fields
are rejected. Foreign IDs return 404 before parsing a message. Idempotent creation/appends retain the
same Timeline/entry after a retry. The canonical resource URL is resolved through an account-scoped
index created atomically with the Timeline, with no all-account search. This extends the previously
unreleased 110 creation path; it does not migrate arbitrary private prototype stores made from 110.

The runtime loads one exact AWS Secrets Manager version at startup. It checks the provider BlueId,
endpoint, supported Coordination package and private/public key match before serving. It never
silently regenerates keys. The dedicated retained secret has a resource-scoped GetSecretValue grant
for the existing Better role. Explicit provisioning generates a temporary mode-0600 key file inside
a private directory, uploads it, and removes the directory. Public deployment pins carry no private
key. A later deploy cannot silently remove provider configuration by omitting its public pin file.
No original MyOS resources or shared package sources changed.

The full strict host suite passes 247 tests. The new HTTP tests cover actual authenticated owner
routes, retry equality, signature verification, canonical resource lookup, foreign account refusal,
invalid/oversized input and optional disabled publication. Separate identity tests cover mismatched
keys/endpoint/BlueId, partial configuration and sanitized failure diagnostics. A publication-locality
test additionally pages 50 + 14 metadata records while all 64 old entry bodies are unavailable.
The 21 deployment/qualification script tests pass, as do four browser-side intent tests and the
TypeScript production build. Library code is unchanged; prior 2285 passing library tests remain its
qualification evidence. Dependency resolution required disabling Gradle's configuration cache for
the existing maintenance task; normal check/build uses the cache.

Live browser acceptance passes the existing counter/bootstrap, editable operation dialogs,
payment/order/agreement propagation, history and mobile checks, plus Timeline creation, two message
formats, original-content/attestation viewing, page reload and foreign-account read/append denial.
The first browser test was corrected to wait for loaded state rather than its placeholder. The next
run exposed an ambiguous textarea accessible label; the UI now gives it an explicit matching label.
Both failed reproductions are retained alongside the passing run. The new Timeline view also shows
an explicit loading message rather than suggesting an empty history while loading.

Two live sample appends took 308 ms and 293 ms browser response time, including HTTPS/network travel.
They are examples, not a latency distribution or throughput claim. The underlying 110 DynamoDB
publication experiment remains 31.54 ms / 33.61 ms median at histories starting with 1 / 128 entries;
no new BEX or execution engine speedup is claimed in this UI integration. The 512-document user's
payment walkthrough and its paired 1-order/128-order results remain documented separately.

Decision: **keep** the working private owner console and persistent identity boundary. This is not a
complete native provider. Signed reader protocol, current/historical access, control history, richer
actor authority, dynamic native source adoption and operation submission through those paths remain
unfinished. The ordinary runtime still reports nativeAdmission=false and timelineProcessing=false.
Console Timeline append does not yet execute documents; Operations and Payment flow remain the
supported business execution path. Full original MyOS parity, distributed cycles, retention and HA
are still outside this iteration's evidence.

Final release `f375d0da98d895b651107152d0592d5a36265878831399c17aa455f250e8114f` reached AWS
Success under SSM `a6a7e872-c556-4542-a19d-9febda556732`. A subsequent controlled Main restart under
`1d5db666-40aa-455f-b1e0-1a6f12ab71af` changed the service PID from 114715 to 114934 and returned healthy.
The QA-owned Timeline was read before and after: exact original entry, provider identity, head,
metadata page and append receipt were identical. Retrying the captured third append after restart
kept count 3 instead of creating entry 4. The retained secret/policy stack is intentional live
infrastructure, not a temporary benchmark resource. Source pins, passing browser screenshots,
initial failed reproductions and restart evidence are recorded in manifest 111.

The promoted checkout passes all 247 host tests and the rebuilt UI tests/build. Its host and library
JARs match the deployed build byte for byte. The final read-only user-agreement check confirms version
247, all 128 direct orders initialized, and `paidTotal: 4956`; it performs no business mutation. The
previous 512-document inventory is preserved. The provider custody stack is CREATE_COMPLETE and
remains intentionally retained for live Timeline use.

## 112. Checkpoint-bound provider reads — keep

Added internal signed read and exact entry-fetch assembly on the existing owner-scoped publisher.
The library's `TimelineRequests` parser is shared with the existing consumer, preventing separate
rules for inherited values, target binding, selector exclusivity and limits. A parsed request is
neither authenticated nor a proof of retained membership. The host recognizes checkpoints and anchors
inside the selected durable account/timeline; an unknown, foreign or post-checkpoint anchor is refused.

Fresh reads seal a checkpoint; continuations keep the exact retained checkpoint across later appends
and publisher reconstruction. Entry seeking uses an index. Timestamp seeking uses binary search over
new immutable timestamp metadata, with original-body fallback for old rows. Only the immediate
predecessor and requested entries are loaded. Count/byte bounds prevent unbounded responses. A normal
page is signed once; oversized candidates use bounded prefix search. An oversized single entry fails
instead of returning an empty nonterminal page. Entry fetch preserves its original reusable acceptance.
Custom type evidence and definition messages remain available after a fresh publisher loads storage.

Full strict library checks/Javadocs pass 2298 tests. Host tests are organized into closed prefixes,
seek/locality and wire bounds, sharing deterministic publisher fixtures. They use the ordinary Blue
reader to verify generated pages. The physical locality test hides unrelated older entry bodies and
uses a fresh publisher; the selected tail must still load. Earlier test failures exposed assumptions
in the test setup: a warmed cache could still hold a hidden body, and a genesis predecessor is smaller
than an interior predecessor on the wire. Tests now start a fresh publisher and derive the byte bound
from an interior page. The library's strict Javadoc check also required accessor main descriptions.

The bounded actual DynamoDB run used a separate temporary table, the existing two-vCPU host and exact
hash-pinned artifacts. The console process stayed running and passed its before/after health checks.
SSM `103c3d90-2cf2-4eb8-b2e2-064ff5575d68` reached Success. A populating JVM exits; a fresh JVM restores
all original receipts, validates 136 original entries, appends beyond the durable clock fence, and
assembles retained reads. A later append increases the longer history to 138 while those reads remain
bound to their 137-entry checkpoint. Temporary qualification keys are deleted afterward.

| Retained read: one entry + predecessor | 9-entry checkpoint | 137-entry checkpoint |
|---|---:|---:|
| Warm publisher median |33.728 ms|34.299 ms|
| Warm maximum of 8 |39.072 ms|40.887 ms|
| Empty publisher-cache median |47.939 ms|48.870 ms|
| Empty publisher-cache maximum of 8 |55.170 ms|59.437 ms|
| Metadata reads per response |7|7|
| Content reads, warm / empty cache |0 / 6|0 / 6|
| Query scans / writes per response |0 / 0|0 / 0|

Each mode has eight alternating samples after eight warmups. Timing includes durable reads, response
construction and signing; it excludes setup, constructor binding and consumer verification. A fresh
publisher means an empty content cache, not a cold JVM. The response sizes are 2905/2909 bytes; the four
extra bytes are wire-value differences, not inclusion of the earlier history. Fresh checkpoint sealing,
timestamp seeking, HTTPS, reader authentication and document execution are outside these read timings.
The same run's append medians are 35.598/35.381 ms and checkpoint medians 29.775/29.135 ms for histories
starting at 1/128 entries. Those are bounded publisher measurements, not a new engine speedup claim.

Decision: **keep**. The work scales with the requested page; timestamp seeking adds logarithmic metadata
lookups, and full-history verification remains proportional to requested history. Seven serial metadata
reads are still an optimization opportunity, including duplicate head/predecessor lookups. No new
native route is exposed and no production release is changed for this internal component. The existing
release 111 console and 512-document walkthrough remain live. Next integration needs current signed
reader permissions, control/access publication, Timeline-scoped content resolution, dynamic adoption
and native receiver delivery. Full original MyOS parity, distributed cycles, retention and HA remain
unqualified. Exact shared Coordination archive SHA-256 is
`e46a16ead0492739abb9a46bd3ef8220bea7e4accb53ce6a6b158741e4264f5a`.

Final host qualification passes all 258 tests, with no failures or skips. The promoted checkout
passes the same strict check (identical-input Gradle cache reuse) and produces the exact host/library/
qualification JARs used by the AWS probe. All promoted source digests match the reviewed stage.
Temporary stack `myos-better-native112-442736365e` reached DELETE_COMPLETE. Manifest 112 retains
source changes, artifact pins, raw measurements, test logs and cleanup confirmation. No user-agreement
operation or production restart was performed in this iteration.

## 113 — Executable access publication and native reader integration (candidate)

The staged candidate implements atomic control/access publication, current reader authorization,
source-scoped content, legacy content migration, and the complete baseline native HTTP routes.
The existing owner console and host-links business runtime remain compatible. 281 host tests and
2313 library tests pass with strict formatting/Javadocs. Cross-replay assertions compare document IDs,
receipts, gas and events; storage tests cover conflicts, lost replies, revocation and restarts.
See HOSTED-TIMELINES.md and TESTING.md for ownership, bounds, first-owner identity policy and coverage.

A bounded local run exposes an unresolved cost: eight cold-host access updates at starting control
lengths 1/32 have median 16.172/19.006 ms, while content reads are 28–45/139–156. Restoring an access
snapshot still validates its original predecessor history. Incremental export avoids repeated history
serialization; it does not eliminate this validation. Eight warm signed one-entry reads after the
resulting 9/40 control entries are 5.304/5.387 ms, with identical storage call counts. They include a
fresh source checkpoint and signing, not HTTP, business execution or parent propagation.

Decision remains **pending** qualification of cold restoration and the real DynamoDB/HTTP boundary.
Keep the tested work in staging. Actual Better sources remain iteration 112 and live AWS release 111;
no production restart, business-document operation or cloud mutation occurred in this work so far.
Dynamic native receiver adoption/delivery, broader MyOS/Python parity and production qualification
remain open. Do not relabel provider readers as native business processing.


Iteration 113 publisher recovery follow-up (candidate, not yet deployed): source-bound protected
validation checkpoints remove historical control-entry schema and retention walks on cold restore.
The host atomically commits the checkpoint with accepted control/access state; consumers continue
strict signed-history verification. Focused recovery and interleaving/corruption tests plus full suites
pass: 2318 library tests and 283 host tests. The local before/after measurements and explicit trust
boundary are documented in HOSTED-TIMELINES.md. Earlier iteration-113 counts and cold-history findings
above describe the pre-checkpoint candidate, not the latest checked implementation.


Iteration 113 AWS storage qualification passes: SSM d621ab00-b160-4a5b-846e-c9fbea6caeca,
isolated table myos-better-native113-6007a6de15, 16.8 seconds. Cold access updates at 1/32 initial
control entries: medians 181.580/243.343 ms; signed one-entry reads 55.390/52.271 ms. Historical
entry bodies are no longer revisited; the content index still adds remote reads as it grows. Keep
the tested publisher recovery change for its explicit correctness boundary and removed history walk,
without claiming flat total AWS update latency. Cleanup of the isolated table has been requested.


### Iteration 113 final deployed qualification

**Keep.** Both actual Better repositories reproduce the staged library, service and qualification
artifact hashes. Deployment SSM `f2c5c024-8e2d-4cf6-812e-90373b93555c` is Success, release
`d3071e8713f7680dd7c4a9ed402e021a8bddfbeca9317a5324d6edfb65ee7564`.
The live browser passes bootstrap, counter/events/reload, payment→order→agreement, immutable history,
prefilled `addOrder`, Timeline YAML/JSON append/content/reload, foreign-account denial and mobile layout.
Its native extension verifies the pinned public provider and response signatures, owner access creation
and idempotency, a portable signed private read with no login/Origin, exact entry/content fetch, actual
BEX revocation, rejection of the saved signed read and current access discovery.

SSM `2e395222-2e79-4b3d-8ae3-c34979dc084a` then restarts the real JVM. Before/after enumeration finds
128 orders, 383 payments, 512 distinct documents in the user's original agreement, version 247 and
paidTotal 4956. The signed current access response has the identical digest across restart. No business
operation was called on the user's agreement during this final check. Runtime native admission and
Timeline processing remain false; the newly deployed provider/access API does not silently enable
business delivery. The report preserves the remaining content-index latency and semantic boundaries.


## Iteration114 — private acquisition and authenticated content resolution (kept and deployed)

Private sources can now use an account/provider/timeline-bound reader identity pinned to one exact
Secrets Manager version. Startup validates the Ed25519 key pair and all identities. Only public pins
enter runtime manifests; malformed secret errors omit secret material. Reader keys are bound durably
to source aliases, so changing identity cannot silently reuse a history. The shared exact-version
loader also preserves existing provider custody. No new live key or IAM grant is required for the
ordinary console profile, which still has automatic native business processing disabled.

Each private read/entry/content request is signed independently; archives retain original evidence,
never replayable signed reader requests. Public control acquisition stays independent. The integration
test revokes a grant, appends another private occurrence, observes blocked private progress while the
control prefix advances, restores the grant and resumes the same private history. Fresh archive
objects recover the same original entries. Current grants remain transport authority only.

That test exposed a real missing path: control entries reference timeline definitions not carried
inline. `TimelineReader.content(request).accept(response)` now verifies source-scoped requests,
provider signatures, correlation and returned exact content identity, without pretending that a
definition is an admitted value. The importer resolves missing content using that API. It shares
its original deadline and byte/record budgets, permits at most 256 distinct fetches, rejects repeated
unresolved identities and never advances a prefix from an incomplete proposal. Known retained
closures are reused. Missing cyclic owning-set proofs still require explicit portable records.

Validation: 2322 library tests and 296 host tests pass with strict compilation, formatting and
Javadocs. Tests include wrong content/request/provider/signature, unresolved definitions, signed
failures, invalid transport extras, missing content, deadline refusal, grant revocation/restoration,
credential non-retention and cold archive restore. The original public acquisition suites also pass.
`NativeTimelineHttpProbe` now exercises the private importer against CloudFront as part of browser
qualification. Its local consumer archive uses MemoryStore; a cold object is not a JVM restart.

Decision: KEEP. This adds protocol coverage and fixes unavailable referenced content. The business
execution kernel, canonical gas and payment propagation path are unchanged; no new business latency
speedup is claimed. Historical private permission enforcement, receiver rebase, dynamic account-owned
source management, durable shared/cyclic attachment and broader production qualification remain.

Deployed CloudFront qualification passes desktop/mobile bootstrap, payments, operation dialogs,
History/Events, Timeline creation, signed entry/content views and account isolation. The private
importer acquired the two original entries in 1120.79 ms over HTTPS from the local QA machine; a fresh
archive object resumed in 552.66 ms without replaying either entry. These include network travel,
discovery and local verification and are not business-runtime timings. Revoked acquisition retained
the same archive position.

The first live probe incorrectly expected an advancing real-time completeness bound to be an exact
duplicate. Its assertion was corrected to require no repeated occurrences, an unchanged head and a
nondecreasing completeness bound. No runtime workaround was made. Its ephemeral grant was explicitly
revoked; the probe now revokes on failure too. The successful final grant also ends revoked. Both
runs and cleanup evidence are retained. The corrected qualification jar is separate from the service
distribution; the deployed host/library hashes did not change during this probe correction.


## Iteration115 — explicit historical private admission (kept and deployed)

The host now offers `myos.timeline-access-at-input/1`, an explicitly selected consuming-document
policy. Every active private receiving source declares required public keys and initial access grants.
The provider's signed discovery determines its reserved public control source, acquired independently.
Canonical Timeline Access BEX establishes actual permission after-states. Selection uses the original
input's full timestamp/timeline/entry order, never the latest grant snapshot. Separate grants can
contribute a union. Missing coverage waits; established insufficient access is `PENDING_ACCESS`,
without a consumed disposition or hot worker loop. Later restoration does not authorize a formerly
revoked input retroactively. Policy changes require historical rebase, still unimplemented.

The invocation binds public access facts to its frozen document/source cut. Public control updates
used only as dependencies are inapplicable without requiring an unrelated Core target lineage.
Public Timeline Channels now explicitly require public sources. Provider discovery survives restart
only after identity/signature validation; a changed reserved control requires explicit adoption.

Replay is shared by exact account/provider/control/grant, with independent locks and no archive I/O
under the cache registry lock. At most64 replays retain1024 recent changes each; old queries replay
canonically. Catch-up advances at most32 acquired batches per call. These limits do not bound the
underlying canonical consumer's retained receipt history or per-call wall time. Compact validated
consumer recovery and byte-based retention remain necessary for long-lived production use.

All314 host tests pass with strict compilation and formatting, including18 additions covering
end-to-end grant/revoke/restoration, two-key union, cold host recovery, full-key ties, evicted history,
discarded-window gaps, bounded catch-up, concurrent accounts, zero-read warm queries, signed discovery
persistence/corruption and immutable namespace policy. Library code is unchanged; its2322 passing
tests are retained from114. No broad Blue semantic or gas change is claimed.

Bounded local qualification uses one publication versus publication plus64 real canonical updates,
eight alternating fresh consumer replays after two warmups per size, then100 cached queries per size.
It verifies exact publisher/consumer executed identities and permission sets on every selection.

| Control occurrences | Cold replay median / maximum | Cached selection median / maximum | Cached archive reads |
| --- | ---: | ---: | ---: |
| 1 | 7.435 / 8.232 ms | 0.004021 / 0.152959 ms | 0 |
| 65 | 181.619 / 209.172 ms | 0.004459 / 0.068584 ms | 0 |

These are memory-store, same-JVM measurements after signed import setup. They exclude HTTP, durable
AWS storage, business execution and parent propagation. Cold canonical replay still scales with
history; cached selection does not reread it. Raw samples are `iteration-115-history-benchmark.json`.

Decision: KEEP as opt-in protocol coverage, with the limitations explicit. It does not enable native
Timeline processing for the existing CloudFront business demo or alter the payment execution path.
See [the policy guide](TIMELINE-PRIVATE-ACCESS.md) for runnable-test scenarios and a document template.

Deployment115 is healthy and the promoted library/host/qualification JARs match the tested stage.
Desktop/mobile CloudFront qualification passes bootstrap, operations, payment propagation, immutable
history, Timeline create/append/entry/content, account isolation, signed private acquisition and grant
revocation. The isolated QA grant ends revoked. Its two-entry importer took1111.31ms initially and
572.67ms on a fresh archive object, with zero repeated entries and no progress after revocation. These
are local-client HTTPS timings, not business execution, JVM restart or private historical-admission
measurements. The live business profile deliberately keeps native Timeline processing disabled.

The post-deployment read-only inventory confirms the original user agreement remains at512 documents (128 orders, 383 payments), version 247, paidTotal 4956. No user business document was mutated by iteration115 qualification.


## Iteration116 — scoped canonical replay reuse (kept; source qualified)

Profiling the115 cold replay found repeated operation grammar validation and runtime construction,
not just signature checks. The baseline sampled stacks repeatedly constructed a Contracts registry
for every operation, including identical requests and unchanged initial access definitions.

Three changes stay within existing trust and semantic boundaries:

- Cache successful operation grammar checks by exact request identity inside one TimelineContent
  verification view. Every original entry still validates its source, actor, timestamp, chain and
  contextual constraints; its operation marker must identify the exact message before reuse.
- Reuse one grammar registry for distinct requests in the same evidence view, installing newly
  validated immutable content. Neither successful checks nor the registry survive that view.
- Give each canonical access invocation the opaque header of its already verified original entry,
  using the existing invocation-scoped facility from DocumentRuntime. Close the scope on success or
  failure. Native access checks still run; no stored permission Boolean, new semantics or wire proof
  shortcut is introduced. The old prefix-only scope method is now unnecessary and removed.

Four new test cases cover repeated grammar with invalid marker/time, distinct malformed requests
without partial prefix publication, and8/64-update bulk-versus-individual replay. The latter compares
exact gas, receipts, events, observations and every historical document, including use after close.
All2326 library tests, strict Javadocs, formatting and all314 host tests pass.

The first grammar-reuse candidate helped repeated requests, while changing-request latency remained
within run variation. Sharing the registry extends the improvement to changing requests. Per-entry
scope reuse is retained with exact equivalence coverage; an isolated substantial speedup from that
subchange is not claimed.

The final comparison used the same qualification-tool JAR against separately pinned baseline115 and
candidate library JARs. Two independent JVM rounds reversed variant order. Each round used two
warmups and eight cold replays per size; the table pools16 measured cold samples. Each replay checks
its exact executed state identity against the canonical publisher. Changing requests retain the
transport reader key and replace an additional key each time. Allocation is main-thread bytes during
the action, not retained or peak heap. Both versions use MemoryStore with signed imports prepared
before measurement. No HTTP, DynamoDB, business document execution or parent propagation is timed.

|65 control entries|Baseline115 median|Candidate median|Reduction|Allocated bytes, baseline → candidate|
|---|---:|---:|---:|---:|
|Repeated request|180.310ms|147.164ms|18.4%|481,429,744 →332,666,464 (30.9%)|
|Changing request|209.198ms|166.372ms|20.5%|554,745,384 →401,639,496 (27.6%)|

The one-entry medians stayed around7ms, with no consistent improvement. Warm selections stayed
around0.004–0.005ms and performed zero archive reads. Cold replay still grows with control history,
and remaining temporary allocation is too high for a claim of production-grade cold recovery.
Consumer checkpoint/retention work, historical policy rebase, dynamic native console routing and
broader native service coverage remain unfinished. No new payment latency improvement is claimed.

Decision: KEEP the measured combined change. Source is qualified; the live console remains115 until
this candidate is included in the next tested deployment. Raw comparisons, scope metadata and exact
binary digests are in `iteration-116-comparison.json`; no profile data is runtime authority.


## Iteration117 — immutable identity metadata (keep)

The116 allocation profile identified canonical helper serialization inside logical identity
construction as the largest sampled allocation source. Ordinary immutable values now retain their
canonical UTF-8 helper length alongside the existing digest. Contracts still traverses and charges
all required children, identity construction, rebuilt members and every logical hash block. The
memo avoids repeated serialization and hashing; it is not admission evidence or a construction
witness. Cyclic-token hashes never populate this ordinary memo, and pure lists retain their folding
algorithm. No published semantic edition, tariff or receipt format changes.

`ContractIdentityTest` owns exact cold/warm charges, Unicode/escaping, failed gas limits, typed lists,
objects, merge policy, pure-list folding and distinct cyclic scopes. The full library and host suites
are required before promotion. Previously published canonical fixture identities remain unchanged.

Two reversed-order JVM rounds compare the same qualification tool against preserved116 and117
binaries. Each replay population has16 cold samples; allocation counts bytes on the calling thread,
not retained heap. Signed import is setup; network, DynamoDB and parent execution are excluded.

| Local scenario |116 median|117 median|Allocated bytes,116 →117|
|---|---:|---:|---:|
|65 control entries, repeated requests|141.430 ms|120.786 ms|329,705,636 →255,952,776|
|65 control entries, changing requests|168.727 ms|144.153 ms|401,586,980 →327,049,724|
|One control entry, repeated scenario|7.127 ms|7.246 ms|15,301,648 →15,314,500|
|One control entry, changing scenario|6.981 ms|7.353 ms|15,321,940 →15,348,352|
|Payment runtime,256 samples per variant|1.181 ms|1.176 ms|935,648 →894,656|
|Hello-clause host initialization,30 samples per variant|113.628 ms|105.707 ms|172,510,616 →160,565,808|

The longer replays improve14.6% with18.6–22.4% less allocation. Initialization improves7.0%; the
payment latency difference is negligible, with4.4% less allocation. Single-entry cold replay is
slightly slower in this bounded run; no single-entry speedup is claimed. Business probes check exact
gas arrays and final identities/state across every run. Replay probes check canonical permission
state and keys. Keep the modest, scoped optimization; it does not solve history-length growth or
consumer recovery. Raw samples and pinned binary digests are in the117 report artifacts.

This local comparison makes no new AWS latency claim. Deployment and post-deployment qualification
are recorded separately. Earlier source-qualified116 changes are included in the next release.


### Release117 qualification

All2,337 library tests and314 host tests pass, with no failures or skips, plus strict compilation,
formatting and Javadoc. Rebuilding the promoted source produces identical library, host and tool JARs.
Release117 was deployed successfully with existing provider identity/custody and runtime settings.
Desktop/mobile browser qualification passes bootstrap, editable operation dialogs, child creation,
payment →order →agreement propagation, historical views, Timeline creation/append/entry content,
signed private acquisition, owner grant/revoke and denied access after revocation. QA uses separate
documents. Read-only inspection confirms the user's existing graph remains512 documents:128 orders,
383 payments, version 247 and paidTotal 4956. No new user business operation was needed for this release.

The manifest117 retains raw local comparisons, test/build logs, source digests, release pins, browser
results/screenshots and the post-deployment graph result. Earlier116 source-only status is historical;
its changes are now live in117. Business Timeline processing remains disabled in the ordinary service.


## Iteration118 — definition traversal iterators (revert)

A fresh117 Hello initialization JFR sample changed the next target: recursive `visitDefinition`
iterator allocation outweighed fresh canonical serialization. The candidate used indexed recipe
parts and sorted-map `forEach` traversal, preserving the selected children, read order and charges.
Existing definition sharing/failure/concurrency, published BEX/Contracts and clause integration tests
passed. Two reversed-order JVM rounds checked exact gas arrays and final identities/state.

| Local scenario |117 median|Candidate median|Allocated bytes,117 →candidate|
|---|---:|---:|---:|
|Payment runtime,256 samples per variant|1.018 ms|1.096 ms|873,396 →890,504|
|Hello initialization,40 samples per variant|103.445 ms|101.058 ms|159,393,768 →157,141,544|

Initialization improved only2.3% and allocation1.4%; the payment comparison was7.6% slower with2.0%
more allocation. These bounded observations do not isolate a causal payment regression, but they
also do not demonstrate a useful overall improvement. Decision: REVERT. Production source is restored
byte-for-byte to117. No AWS deployment or semantic change was made for118. The deployed117 console
and512-document walkthrough remain the current accepted checkpoint.

The sanitized profile summary and raw benchmark samples are retained with manifest118. Raw JFR and
its process/system metadata remain temporary diagnostics and are excluded from the project report.
Next work should target repeated definition interpretation more substantially, while preserving
per-invocation validation, evidence opening and exact logical charges; a cached value alone is not
sufficient authority to skip those steps.


## Iteration119 — invocation-local definition read reuse (revert)

The next candidate recorded the ordered charge sequence after a complete successful definition
read. Reuse was confined to the identical invocation ledger and evidence context, bounded to8 entries /
512 KiB, with oversized inputs falling back to ordinary traversal. It preserved individual charge
boundaries, current owner attribution and first-read manifest/witness establishment. Five focused
tests covered warm/cold exact charges, each gas-limit boundary, changed definitions, new ledgers,
replacement evidence contexts and large/many-definition fallback. Existing definition, portable,
canonical BEX/Contracts and published-clause tests also passed.

Two reversed-order JVM rounds checked exact gas arrays and final state/identities. The real workload
did not benefit:

| Local scenario |117 median|Candidate median|Allocated bytes,117 →candidate|
|---|---:|---:|---:|
|Payment runtime,256 samples per variant|1.096 ms|1.107 ms|891,716 →892,000|
|Hello initialization,40 samples per variant|103.445 ms|107.661 ms|159,855,000 →161,581,336|

A separate instrumented two-initialization diagnostic recorded2 definition-read cache hits and8
misses, despite6 shared definition-value cache hits. The opportunity inside one invocation is too
small for this implementation to offset recording and lookup cost. Initialization was4.1% slower
and allocated1.1% more; the payment difference was about1%. Decision: REVERT, including the proposed
class and its candidate-specific tests. Their sources and measurements remain in the119 evidence
folder for inspection. All accepted source and rebuilt JARs match117; no119 AWS deployment occurred.

The finding redirects optimization toward first-time interpretation and validation rather than an
additional memo at this boundary. Capability documentation was refreshed to distinguish implemented
host private-read/history features from still-missing consumer recovery and native console routing.


## Iteration120 — owner Timeline sharing controls (keep, deployed)

The console's new Sharing tab exposes the already implemented executed Timeline Access protocol.
Owners can grant a reader public key, inspect current canonical access documents and revoke a whole
grant. Existing native multi-key/multi-Timeline grants are shown with their complete scope; revocation
confirms that scope. Different active native permissions cannot be silently overwritten by the simple
single-reader form. Empty grants can be reactivated. No parallel authorization model or console-account
invitation system was added; reader applications use signed native requests.

Host grant/reactivation and revoke use one canonical publication with a durable command receipt.
Older grant retries after revocation never reactivate access; older revoke retries after a new grant
never revoke it again. This is tested across restart and lost replies. The bounded64-grant inventory
batches membership metadata reads. Native Create/Update/Get semantics remain unchanged. All owner
routes retain login, Origin and account isolation checks; the UI never requests a private reader key.

Decision: KEEP this functional capability. All319 host tests pass, plus frontend typecheck/build and
4 existing frontend tests. The unchanged library's2,337-test qualification is reused from117 after
exact source and rebuilt JAR comparison. Browser qualification passes bootstrap, Operations, payment
propagation, History, Timeline append/content, Sharing grant/reload/revoke/regrant, mobile layout,
foreign-account refusal and the real signed private-reader protocol. Test grants use separate QA
Timelines and ephemeral keys; they are revoked afterward. Desktop/mobile screenshots were inspected.

The final deployed artifact is `2c36ee582280c1d64d38f7de6802676f4778e221564d0ae9147a7b6259a963ca`,
SSM command `ab7b4720-266a-476f-8e60-0dbe930d753a`: Success. A preliminary build was superseded by
the reviewed guard protecting active multi-key/multi-Timeline grants; final qualification applies to
this artifact only. Provider identity, key custody, business runtime settings and table are unchanged.
The user agreement is verified read-only:512 documents,128 orders, 383 payments, version 247,
paidTotal 4956. No business latency improvement is claimed for this UI/access-adapter release.

Native business Timeline routing, compact consumer recovery, policy rebase, shared/cyclic durable
attachments and Mandates/Time remain unfinished. The work remains active. Manifest120 retains the
source/artifact hashes, checks, browser results/screenshots and graph verification.


## Iteration121 — render identity helpers without temporary maps (keep)

Ordinary exact nodes already retain fields in canonical key order. Identity calculation now renders
those fields directly, using the existing canonical scalar/quote renderer, instead of creating a
second tree of helper maps and sorting their keys again. List folding renders its fixed helper shape
in canonical `elem`, then `prev` order. All buffers remain invocation-local. No resolver evidence,
logical charges, identity-cache lifetime, node admission or public API changes.

Six new Language identity cases check nested helpers, Unicode/escape ordering, scalar categories,
typed/pure lists, merge policies and cyclic token separation against ordinary helper serialization.
The complete library suite passes2,343 tests, with strict formatting and Javadoc. The unchanged
Contracts oracle and gas-limit tests pass. The host suite and live deployment qualification are
recorded in manifest121; the source comparison pins the baseline to release120 (library117).

A first bounded baseline/candidate/candidate/baseline screen had substantial between-process timing
variation, so one further comparison increased JVM warm-up. Every run checks exact gas and final
identities/state. The follow-up uses1,024 warm-up payments and512 measured payments in each fresh JVM;
Hello uses10 warm-ups and30 samples. There are two JVMs per variant:

| Local scenario |120 median|121 median|Allocated bytes,120 →121|
|---|---:|---:|---:|
|Payment runtime,1,024 measured calls per variant|0.446 ms|0.424 ms|813,204 →811,400|
|Hello initialization,60 measured calls per variant|94.733 ms|93.919 ms|158,779,644 →155,388,484|

Decision: KEEP for the simpler physical construction and3.39 MB (2.14%) lower initialization
allocation. Initialization latency is essentially unchanged. The observed4.84% payment difference
is a small local measurement with between-JVM variation, not a throughput or AWS latency guarantee.
This does not solve the remaining cold definition interpretation/history replay costs. The first
screen, follow-up, inputs, exact-result checks and artifact digests are retained with manifest121.

Tracing console-native routing also confirmed the remaining boundary: hosted Timeline ownership
and provider publication do not establish an executed receiving policy. Dynamic durable adoption,
private historical access, source closure and account-isolated scheduling must precede that bridge.
No implicit Timeline-to-host-operation shortcut or runtime-profile change was introduced.


Release121 is deployed and live browser qualification passes bootstrap, Operations, parent payment
propagation, History, Timeline creation/append/content, Sharing grant/revoke/regrant, foreign-account
refusal and mobile layout. The unchanged provider key/identity also passes real signed private-reader
checks. The user graph was verified read-only after deployment:512 documents,128 orders, 383 payments,
version 247 and paidTotal 4956. Separate QA documents exercised mutations. Artifact
`61933a8475988af31b34c2de27e3d0c4566a77a80a86fa741ada56a782cdb795`, SSM
`1c678f69-791a-4f96-bc11-c8c6999eeada`: Success. The host and console artifacts are byte-identical to120;
only the library runtime artifact changed. No new AWS business-latency comparison was performed.


## Iteration122 — durable, account-scoped Timeline directory (keep)

Added the opt-in `myos.timeline-directory/1` source profile. Operators can atomically adopt exact
provider/Timeline pins after startup, list sources in pages and recover their portable definition
closures after restart without source files. Configuration, alias, Timeline and provider/URL indexes
are published together; lost replies resolve against the complete immutable publication. Changed
bindings require an explicit migration. Directory aliases and content remain account-scoped.

Frozen receiving declarations select their exact source indexes. A warm single-source lookup makes
3 metadata reads both before and after128 unrelated sources, without inventory or content reads.
Parents with no native channels perform no directory lookup. Optional archive reuse is capped at64
entries; eviction preserves durable signed history and process phase counters. The count bound does
not solve retained-history byte bounds or cold replay cost.

The explicit signed-discovery endpoint verifies the reserved public control identity without
advancing history or granting permission. Directory-backed private receivers require that discovery
and exact control adoption before publication. Existing historical private-access checks still decide
permission at the original input, including revoke/restore behavior. Static automatic acquisition is
unchanged. Directory automatic acquisition and reader custody currently fail startup; this is the
foundation for subsequent console-owned source adoption and automatic processing, not that complete
integration. The ordinary public console keeps its existing host-links operations.

Decision: KEEP the functional capability. All334 host tests pass, including actual HTTP child-to-parent
execution across restart, private history in both source profiles, portable collapsed metadata and
cache eviction. Four launcher tests pass. The unchanged library reuses release121's2,343-test evidence
after exact source/artifact comparison. Final Main/HTTP and AWS results are retained in manifest122.
See [the directory guide](TIMELINE-DIRECTORY.md) in the host project for configuration and APIs.


The AWS Main/HTTP directory qualification passed with DynamoDB and a second fresh Main process:
zero bootstrap source files, exact source adoption, foreign-account refusal, rejected rebinding,
invalid-signature refusal, strict quiet-source closure, original-entry deduplication and continued
child/parent execution. All8 original inputs were reflected in the final child/parent total56.
The bounded run used2 warm-ups and4 measured inputs:

| Inclusive phase | AWS median |
|---|---:|
|Signed source acquisition|102.12 ms|
|Native ingress through durable child response|141.87 ms|
|Runtime execution within ingress|15.86 ms|
|Ingress through observed parent update|227.96 ms|

Acquisition is separate; the remaining rows overlap and must not be added. These small qualification
samples have little JVM warm-up and are not steady-state timing or a before/after speedup claim.
SSM `13cd4f31-5c49-49ec-88e0-3e229f444cc5` completed successfully and restored the normal service's
exact host-links profile. The public console still does not automatically execute Timeline messages.


Release122 is deployed as artifact
`3b15d72315320a1b63c6a26e56b1a2aaa7761434a9fe58dc164d6e727f1a16e3`;
SSM `59bfb43f-18a1-405d-8189-5fc49c6e1252` completed successfully. After restoring the
ordinary service, live browser qualification passed bootstrap, operation dialogs, payment propagation,
immutable History, Timeline creation/append/content, Sharing grant/revoke/regrant, account isolation
and signed private-reader revocation. The original user agreement was then verified read-only:
512 documents, 128 orders, 383 payments, version 247 and paidTotal 4956. Business mutations ran in
separate QA documents. The console and library runtime artifacts remain unchanged from121.
Manifest122 records source/artifact hashes, full host tests, native restart qualification, live browser
results and the graph check. No new AWS business-latency comparison was performed in this iteration.


## Iteration123 — isolate durable Timeline work by runtime binding (keep)

Reproduced a scheduler correctness defect: a runtime could consume a different runtime's READY
receiver from the shared work index and mark it waiting with no usable sources. Discovery now uses
the full immutable account binding as its index scope, and receiver execution also checks that this
worker supports every subscribed source. Unexpected hints receive the same ownership checks.
Source fanout can wake a receiver for another capable worker without executing it on an incomplete
worker. Namespace, Coordination and private-access/directory profile remain distinct.

Existing subscriptions recover through each adopted source's bounded fanout, including a lost
publication response and a fresh worker. This resets migration fanout from the beginning, preserves
failed-receiver blocks and does not replay business effects. Static startup recovers its explicitly
configured sources; directory users repeat the same adoption. Old/new mixed workers and downgrade
require explicit migration; documents created before subscriptions existed still need migration.

The deterministic locality check queues1,024 receivers belonging to another binding. Discovering
and settling the one owned receiver still returns one work item, requires one strong job-row read,
and issues no subscription query. Ordinary acquired-source and receiver updates reuse the protected
job's immutable binding with no extra per-occurrence account lookup or hashing. These are I/O-count
checks, not an AWS latency or throughput claim.

Decision: KEEP for scheduler correctness and bounded discovery. Dynamic directory automatic
acquisition, reader custody and the console-native business bridge remain unfinished. Final test,
Main/HTTPS and deployment evidence will be recorded in manifest123.


All342 host tests pass, along with four launcher tests. The unchanged library source/JAR retains
release121's2,343-test qualification. The promoted host JAR exactly matches the tested artifact
`534c65302a4e67b8ce56980a0535113424057053ad84462b17887335da06ab62`.
The local actual-Main/HTTPS probe passes. AWS qualification also passes with DynamoDB and a fresh
Main process: rejected invalid signatures, quiet-source closure, duplicate suppression, automatic
child/parent execution and an offline arrival recovered exactly once. Twelve original inputs
produce final total84. The two warm-ups/four measured samples yield529.13 ms median and569.06 ms
maximum/p95 from append through observed parent update. This includes the100 ms source polling
interval, HTTPS signing/verification, storage and background scheduling. It is a bounded functional
qualification with limited warm-up, not a before/after latency comparison or throughput guarantee.
Diagnostic phase totals overlap across threads; they cannot be summed into one request duration.

Release123 artifact `be1bd7e321c347358237e216621b9b4225d9a94e3228b9dc25ce44b0740c6e22` is deployed;
SSM `387471b2-7df6-4012-8e48-ea476da0f62b`: Success. Automatic qualification SSM
`60aca0ad-2ab1-446d-a214-0e1668ef0fc9`: Success, including restoration of the ordinary console's
exact host-links profile. The public console still does not execute appended Timeline messages
automatically. Its business payment measurements remain pinned to104.


After service restoration, live browser qualification passes bootstrap, operation dialogs, payment
propagation, immutable History, Timeline creation/append/content, Sharing grant/revoke/regrant,
account isolation and signed private-reader revocation. The original user agreement is verified
read-only:512 documents,128 orders, 383 payments, version 247 and paidTotal 4956. Business mutations
ran in separate QA documents. Manifest123 retains source/runtime pins and all qualification outputs.

Next performance investigation: the automatic probe's observation windows include repeated work
index queries by otherwise idle parent-propagation workers. `Propagation.drain` currently scans up
to16 shards when it delivers no hinted work, on top of timed discovery. This is a concrete code and
I/O observation, not a proven share of end-to-end latency. Measure idle queries, burst wakeup latency
and crash/restart discovery before changing that policy; preserve fairness under continuous hints.


## Iteration124 — advisory push wakeups (revert)

Tested two bounded AWS variants against the current worker polling: DynamoDB Stream/Pipe/SQS hints,
then immediate asynchronous SQS publication after a confirmed transaction with Streams as recovery.
Both reduced idle work-index queries from about93–109/s to4/s and preserved exact totals, gas and
versions. All359 candidate host tests passed, including unknown commit outcomes, bounded overflow,
queue failure, duplicate hints and crash recovery.

Stream-only discovery regressed independent-producer completion from239–251ms to402–452ms. Immediate
notification reduced that path from252–270ms to206–238ms, but same-worker completion regressed from
162–171ms to189–202ms. These are ranges of two run medians from small, separately pinned campaigns;
not confidence intervals or a throughput qualification. The producer uses a separate Core in the
same JVM, not another machine.

Decision: REVERT both candidates. Keep release123 and its dependency graph; do not add SQS to the
shipping host. Archive the experiments and remove their temporary infrastructure. Reduced idle
reads do not by themselves justify slowing the normal console payment path. The exact source of
the same-worker overhead remains unproven; future work should isolate notification transport and
stale-hint read costs before adopting a new scheduling policy. Full details and artifact pins are
in the host's docs/PERFORMANCE-EXPERIMENT-124.md and manifest124. No library runtime change.


Validation after revert: the restored host and qualification JARs exactly match123; the restored
342-test host check passes (Gradle reused matching test results), and the library JAR remains121.
The live browser passes bootstrap, payment propagation, immutable History, operation dialogs,
Timeline creation/append/content, Sharing grant/revoke/regrant and signed private-reader denial.
A fresh QA payment had10.50ms runtime,261ms browser response and517ms until all parent updates were
observed; this single sample is not a performance guarantee. The original user agreement is checked
read-only:512 documents,128 orders, 383 payments,version 247,paidTotal 4956.

The experimental CloudFormation stack is DELETE_COMPLETE, the original stack is UPDATE_COMPLETE,
and the retained DynamoDB table is ACTIVE with its original stream setting restored. No candidate
runtime was promoted or deployed as the normal service. Source changes retained for124 are reports
only. Both AWS comparison commands completed successfully and restored release123.


## Iteration125 — automatic acquisition from the durable source directory (keep)

The directory can now register automatic acquisition after startup, with no provider/Timeline source
files. Public source adoption and its polling registration share one conditional transaction. Repeating
old adoption adds a missing polling registration without rebinding definitions or resetting history.
Private sources require an explicit loopback-operator custody registration referencing an exact
protected secret version. Account, protocol, provider, Timeline and key correspondence are verified
before registration and on cold custody load; only non-secret references are stored. Current provider
permission and the document's historical business-access policy remain independent requirements.

Discovery pages four runtime-scoped shards fairly, with at most64 queued/running jobs,64 cached pull
sessions and1–8 acquisition workers. Incomplete chunks can rejoin the bounded queue immediately. A
growing shard cannot starve another. Retry backoff is durable and preserves monotonic revisions across
recovery: a deterministic test first reproduced an old-success/delete/recreate race, then passed with
the retained fix. Healthy unchanged polling writes no scheduling record and repeats no business effect.

All351 host tests and four launcher tests pass. The actual Main/HTTPS directory probe passes with
zero source files, runtime HTTP adoption, invalid-signature refusal, strict quiet-source closure,
duplicate suppression and child/parent execution. Its two warm-ups/four samples yield99.80ms median
and132.86ms maximum/p95 through the parent in memory. These are inclusive functional-qualification
timings, not a before/after speedup or throughput guarantee. Library runtime/source remains121.

The bounded tests cover130 adopted sources, namespace isolation, competing workers, offline input
after restart, private-reader revocation/restoration and custody mismatch. Private custody is tested
locally; AWS private-key provisioning and its end-to-end qualification remain separate. Distributed
polling leases/provider quotas, historical byte bounds, retirement/key rotation and owner-authorized
console adoption/routing remain unfinished. The ordinary console still uses host-links operations;
Timeline append there does not automatically invoke business operations.


AWS qualification and deployment are complete. Release
`da719d40abe5f3848b93f1c1070d79472e35c6dc861b483596028036728c2578`
passed deployment health checks and the automatic-directory probe against DynamoDB. The probe used
two warm-ups and four samples, restored an offline arrival in a fresh Main process, and finished at
the expected parent count of 84. Median through-parent latency was 643.77 ms; the maximum (also the
nearest-rank p95 for four samples) was 663.62 ms. These signed-acquisition measurements include polling,
verification and persistence; they are not directly comparable to the host-links payment benchmark.
Both runtime JAR hashes match the promoted build. The qualification restored the ordinary host-links
service successfully, with automatic native business execution still disabled in its manifest.

Live browser verification passed editable bootstrap, operation dialogs, payment propagation, immutable
History, Events, private Timeline creation and signed entries, Sharing grant/revoke/regrant and
cross-account denial. A fresh small QA graph showed 9.19 ms payment runtime, 253 ms browser response
and 504 ms until all parent updates were observed. This is one functional sample, not a performance
campaign. The original user's agreement was checked read-only: 128 orders, 383 payments, 512 documents,
version 247 and paidTotal 4956. The library runtime remains unchanged. Decision: KEEP iteration 125;
its benefit is durable runtime adoption and bounded, isolated acquisition, not a claimed latency gain.
Evidence and exact source hashes are retained in manifest-125.json.


## Iteration126 — batch parent-delivery reads (keep)

Wake-up, head and dispatch acknowledgement reads now share one strong per-key batch. Discovery
prefetches bounded groups of at most 25 sources (100 records with legacy outboxes). Existing revision
fences and command receipts still decide publication; the batch is not a transactional snapshot.
No runtime edition, storage format, package pin, queue policy or business semantics changed.

Seven tests cover read budgets, stale/suspended/deleted index entries, failed reads, legacy hashes,
a source advancing after read and a peer completing prefetched work. All 358 host tests and three
launcher tests pass. Library runtime is unchanged from121. The live baseline/candidate campaign
used eight workers, eight measured payments per case, and recovery of 64 payments on separate orders.
All gas/version arrays, exact totals and durable acknowledgements matched.

The candidate reduced average backlog get/getAll calls from1007.5 to807.5 (19.9%). This is a measured
store-call reduction, not a claim about billed units or HTTP retries. Hinted medians remained in the
162–172ms baseline range; the candidate backlog durations of3186–3212ms lay within the2964–3250ms
baseline range. There is no demonstrated latency speedup. Keep the small read-batching improvement
for lower read-call overhead; root-update serialization and global queue discovery remain open work.
The detailed report is docs/PERFORMANCE-PROPAGATION-READS-126.md.


Deployment and qualification are complete. Release
`66d53c20bc4b9cd96b4a17fdfb09e1a0ea3ac41acceef6d736e5af875ff7ccaf`
passed health checks with the existing host-links profile, provider identity and worker/cache capacity.
The signed automatic-directory probe passed on DynamoDB, including a fresh-process restart and an
offline arrival, with the expected final count84. Its two warm-ups/four samples measured607.75ms
median through the parent; this separate functional run is not a paired speedup result.

Live browser verification passed bootstrap, operations, payment/order/agreement propagation,
immutable History, Events, Timeline creation/append/content, reader grant/revoke/regrant and account
isolation. The fresh small QA graph recorded13.94ms runtime,262ms browser response and515ms until all
three updates were visible. The original user graph remains512 documents (128orders, 383payments),
version 247,paidTotal 4956, verified read-only. Exact evidence and source pins are in manifest126.


## Iteration127 — cached parent snapshots with freshness recovery (keep)

Parent delivery now reuses the existing bounded speculative head cache, as direct operations already
do. Conditional publication remains authoritative. A cached child-version gap refreshes before
waiting, and a parent-processing exception refreshes before suspending the source when a peer has
already changed the parent. Both missing safeguards were reproduced in focused failing tests before
the final implementation. The first measured candidate was not promoted because it could suspend a
child after a peer had amended an oversized workflow into a valid bounded one.

All364 host tests pass. The corrected binary was separately measured in four local and four AWS
forks with the same64-order backlog and single-payment fixture. Gas, versions, totals and durable
acknowledgements match. AWS get/getAll calls average812.5 before and723 after (11.0% fewer). Hinted
medians are149–162ms baseline versus143–154ms candidate; unhinted medians overlap. Recovery is
about2.9–3.0s for both. Keep the redundant-read removal with the freshness guards, without claiming
a material backlog/throughput improvement. Direct library runtime, storage formats and execution
editions are unchanged. Details: docs/PERFORMANCE-PARENT-SNAPSHOTS-127.md.

Deployment and qualification are complete. Release
`694e90e5da55dbd5319c29917b5c9751ff24c9f826013f68284beeaa15476954`
passed health checks with the existing host-links profile, provider identity and worker/cache capacity.
The signed automatic-directory probe passed on DynamoDB, including a fresh-process restart and an
offline arrival, with expected final count 84. Its two warmups/four samples measured 627.29 ms
median through the parent; this separate functional run is not a paired speedup result.

Live browser verification passed bootstrap, operations, payment/order/agreement propagation,
immutable History, Events, Timeline creation/append/content, reader grant/revoke/regrant and account
isolation. The fresh small QA graph recorded 7.01 ms runtime, 273 ms browser response and 575 ms until
all three updates were visible. The original user graph remains 512 documents (128 orders, 383 payments),
version 247, paidTotal 4956, verified read-only. Exact evidence and source pins are in manifest 127.

## Iteration 128 — publish small embedding pages with document state (keep)

The previous iteration 127 is deployed and qualified. This iteration combines small immutable
embedding-page sets with the existing conditional document/source transaction. Large sets retain
the bounded page-first fallback. Newly staged pages enter the repository cache only after a
successful commit; failed or uncertain outcomes preserve durable-receipt recovery. No document
format, runtime edition, gas or library source changes.

All 374 host tests pass. Four local forks preserve results without a proven memory speedup. Four AWS
forks preserve gas, versions, totals and acknowledgements. 64-order recovery falls from 3.17–3.33 s to
2.50–2.85 s (17.8% shorter on average), with 128 commits and zero conflicts per fork. Hinted
payment medians improve; unhinted timings are mixed. Keep the change for measured recovery latency,
while noting transactional page writes consume more capacity and may conflict across concurrent
writers. No cost saving, sustained-throughput or SLA claim. See docs/PERFORMANCE-CONTENT-PUBLICATION-128.md.

Deployment and qualification are complete. Release
`0347a23bcb93bc4a20ec4b46776ef5e359374efbe9d83642b2dacc6c470abc60`
passed health checks with the existing profile, provider identity and worker/cache settings.
The automatic-directory probe passed on DynamoDB, including a fresh-process restart and offline
arrival, with final count 84. Its two warmups/four samples measured 555.00 ms median through the
parent; this separate functional qualification is not a paired speedup measurement.

Live browser checks passed bootstrap, operation dialogs, payment/order/agreement propagation,
immutable History, Events, Timeline creation/append/content, reader grant/revoke/regrant and tenant
isolation. The fresh small QA graph recorded 6.03 ms runtime, 279 ms browser response and 583 ms until
all three updates were visible. The original user graph remains 512 documents (128 orders, 383 payments),
version 247 and paidTotal 4956, verified read-only. The ordinary console still uses host-links;
Timeline append does not automatically call a business operation.

The actual checkout's stale root deployment metadata is updated to this verified release, preserving
its old value in the evidence archive. Runtime sources and exact artifacts are pinned in manifest 128.

## Iteration 129 — current large-graph worker and contention qualification (keep tooling)

The prior 128 change is deployed and qualified. This iteration changes qualification tooling and
reports only; the runtime/library stay unchanged. The new probe loads the deployed runtime manifest,
samples actual stored order links and uses real Server workers. Fresh local 8/256-order cases and a
mixed-edition regression (edition 3 root, edition 2 children) pass; all 25 control-script tests pass.

Four AWS forks on existing 128/20,128-order graphs pass 512 measured payments, 64 warmups and 12 receipt
replays, checking every touched leaf/order value, root total and durable acknowledgement. Sequential
root completion medians are 129–137ms small and 129–133ms large. Eight concurrent callers complete 64
payments in 2.75–2.84s small and 2.86–3.09s large. Larger active sets cause 130 session restores and 71
evictions versus 80/0 small; all measured phases have zero cold content reads after untimed target
selection. Size alone is not isolated by the concurrent test. Next: hold working set constant and
profile restoration. These are finite batches, not sustained production throughput.

The first tool incorrectly selected only edition 3; it was fixed to use the actual multi-edition
manifest. A later summary parser assumed JSON was the last log line and rejected a passing result
followed by a shutdown diagnostic. All three passing samples were retained; only the missing fourth
was completed, using the exact same artifacts. Per-sample command provenance is retained. The main
service was restored after every AWS job. Keep the tools/evidence; no runtime optimization or new
deployment is claimed. Details: docs/PERFORMANCE-GRAPH-LOAD-129.md; manifest 129.


## Iteration 135 — generic document inspection and source-reader connection

Keep the source/history inspection and original-style detail navigation. New historical records point
to the existing immutable command receipt; source and input inspection do not duplicate large payloads
or add a database write to every operation. Older records without that pointer remain readable and
explicitly lack original-input inspection. Parent/child execution, checkpoints and propagation
semantics are unchanged. No new performance claim is made.

Remove the server's order/payment demo orchestration, special `addOrder` request generation and
record/settle descriptions, and the Payment flow widget. Examples are ordinary source data. Browser
checks create a root, supply an order request, navigate its links and call its payment operation through
the same public APIs used for other documents. Source, version URLs, input/outcome history, processing
diagnostics, Timeline filtering and message-only inspection have their own verification.

Add optional owner Timeline source connections and immutable Secrets Manager reader custody. The
owner is checked before credentials or grants are created. A generated key belongs to one account,
Coordination publication, provider and Timeline; retries/restarts cannot replace it. Tests exercise
actual signed reading and ordinary workflow execution. This foundation is disabled in the ordinary
AWS runtime until bootstrap and native operation routing are integrated; no IAM grant or reader
secret is created by this release.

The current source fanout still wakes all subscribers of a shared Timeline. Addressed operation
routing needs a separate indexed selection design that preserves source ordering, raw observers and
historical access; simply skipping receiver evidence would change semantics. This is not a measured
shared-Timeline scalability improvement. Full participant bootstrap and the actual
Activation/Documents/Payment tutorial remain mandatory unfinished work in `CONSOLE-PARITY.md`.


## Iteration 136 — native console operations, participant bootstrap and real clauses

Keep the generic console and host changes. The final artifact and independent browser results are
recorded in `reports/overnight-2026-11-05/manifest-136.json`. This supersedes iteration 135's disabled
native-console/bootstrap status. The original MyOS repository, Python and package sources are unchanged.

The deployed `console-native` profile discovers effective operation declarations from the runtime,
publishes signed requests on the selected participant Timeline, and consumes them automatically.
The console supports original/bound source, exact local state, immutable historical state and input,
events, current receiving Timelines, independently stored child links and processing diagnostics.
Creation distinguishes fully supplied documents from participant bootstrap. Bootstrap previews
inherited Activation parties, creates or reuses owner Timelines, provisions scoped readers/access and
retains a durable idempotent provisioning intent. API keys have one-time disclosure, bearer use,
expiry and revocation; bearer credentials cannot administer keys.

The runnable Activation/Documents Agreement creates an Activation/Payment Order, which creates a
manual Payment Instruction. Two participant Timelines are reused across all three. Receipt
acknowledgment produces Payment completion 200, Order paidTotal 200 and Agreement paidTotal 200.
Version 1 remains zero. Business behavior is entirely in pinned packages and ordinary source;
Java does not recognize the example's document names, order paths or payment operation names.
See `examples/clause-tutorial/README.md` for console steps and API examples.

The real journey exposed identity loss through local JSON projections, eager child expansion,
missing shared definitions on archive replay, missing source-order provenance on authenticated
forks, failure/wakeup races and unnecessary delivery of control-only history. Each has focused
regressions. Older forks without original checkpoint evidence are visibly blocked rather than
retried indefinitely; the host does not manufacture authority for them.

The final replay improvements retain a bounded disposable index of verified empty acquisition
runs. Permission replay still verifies the selected signed checkpoint and every actual control
entry; source cursors skip only empty acquisitions inside their pinned verified cut. The library
avoids constructing an execution context for a verified empty suffix. Cold archive reconstruction
still verifies stored history; this iteration does not claim constant-time cold startup.

All 2,369 library tests, 403 host tests and 34 deployment/qualification-script tests passed. Frontend
compilation and both frontend tests passed. Browser evidence separates submission latency from
asynchronous business propagation. The resumed pre-final run took about 20 seconds from the browser
acknowledgment action to Agreement visibility; that includes browser navigation, polling, network,
source acquisition and propagation, and is not an engine-only benchmark. Final measurements and
any failed qualification attempts remain recorded separately.

This remains a preview, not complete original Core parity or a production SLA. Cross-account/email
invitation bootstrap, representative/agent provisioning, original Terms/Timers controls,
multi-account memberships and broader lifecycle controls remain. The tutorial uses required: []
and binds both roles to its owner. Shared business Timeline fanout, cold history replay and
production HA need further work; large-network throughput is not inferred from this tutorial.
