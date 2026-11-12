# Native AWS latency investigation: iterations 148–196

The millisecond local-engine and synthetic graph measurements did **not** establish millisecond
latency for the console's complete signed native Timeline workflow. These are separate paths and
must be reported separately. The native AWS target is not yet met by the measurements below.

## Actual route

1. The console publishes an addressed Operation Request on an owned participant Timeline.
2. A reader authenticates and acquires a verified source suffix. Input selection requires sufficient
   coverage from the other participating sources and the provider's access-control Timeline.
3. The receiver checks target lineage and historical reader permissions, then runs Contracts/BEX.
4. The host stores the local checkpoint and any newly initialized child documents atomically.
5. Child events enter the durable reverse-link queue. Each affected parent executes its own
   listeners and publishes its own new version. The root's visible result follows that propagation.

The engine timer covers step 3 and local attachment preparation. It does not include submission,
queueing, source acquisition, checkpoint persistence, network round trips or parent propagation.
The client observation timer includes those costs and polling. Neither timer is a substitute for the other.

## Retained changes

- **148:** share pinned child reads within one attempt. Repeated field reads no longer reload or
  repeatedly charge the same child against the host document-count bound.
- **149–150:** reuse privately issued, single-use prepared child state instead of immediately
  deserializing it again. Public/foreign/consumed checkpoints still take the validated restore path.
  Addressed notifications identify their intended receivers; unknown inputs retain broadcast behavior.
- **151:** replace allocation-heavy Base58 identifier validation with bounded arithmetic and a small
  successful-result cache. Randomized comparisons against an independent decoder verify the result.
- **152–153:** reuse immutable presence facts and child-open inventories; share definition-work
  calculations within one metered invocation while retaining logical gas. Source arrivals and missing
  coverage now signal a bounded priority queue instead of waiting for periodic discovery.
- **154:** stop hashing an unused logical proof ledger for host-local completions. A comparison test
  verifies identical parent/child checkpoints, gas and events. Canonical proof mode remains supported.
  Cache an ExactView's validated own body for repeated metadata reads.
- **155:** eliminate generic-map JSON round trips inside local Timeline transport. Change the demo
  host's fallback source polling from 500 ms to 5 s; immediate arrival and coverage notifications
  carry active work. A lost hint may wait for fallback discovery. External deployments retain their
  explicit polling configuration.
- **156:** distinguish actual input sources from access-control dependencies in durable receiver
  metadata. Control-only updates do not wake an idle business receiver. Waiting and pending-access
  receivers still wake, as do documents with an actual channel on the control source. Legacy
  receivers acquire this metadata through a document-version-fenced inspection. Addressed work
  receives queue priority; bounded fairness preserves background progress and durable discovery
  recovers lost hints.

No input signature, current reader permission, historical business permission, idempotency check,
conditional publication or required Language validation was removed by these changes.

## Evidence

On the two-vCPU c7i.large host, a ten-second idle sample before iteration 155 consumed 12.86 CPU
seconds (1.285 cores) and performed 4,913 storage `get` calls. After iteration 155, a ten-second idle
sample consumed 3.48 CPU seconds (0.348 cores) and performed 540 `get` calls. Neither sample included
a business execution. These samples establish reduced idle contention, not end-to-end latency.

A fresh iteration-155 Agreement → Order → Payment journey passed all state checks: receipt
acknowledgement produced 200 minor units in Payment, Order and Agreement; all reused the same two
participant Timelines. It still took seconds:

| Operation | Client submission ms | Client observed result ms | Local engine ms |
|---|---:|---:|---:|
| placeOrder | 2,340 | 19,639 | 2,150 |
| preparePayment | 1,636 | 7,307 | 1,218 |
| acceptPaymentInstruction | 1,539 | 5,677 | 270 |
| reportTransfer | 828 | 3,001 | 188 |
| acknowledgeTransfer, through Agreement | 539 | 3,194 | 237 |

The acknowledgement's parent Order and Agreement execution took 452 and 357 ms respectively.
Counters for this small native journey showed 116 session loads and 559 receiver-preparation
passes. Shared control notifications were activating unrelated previously created documents.
Iteration 156 addresses that behavior. Its first run still includes one-time inspection of legacy
receiver records; a subsequent fresh-document run is required to assess steady routing behavior.

The full library and host regression suites passed through iteration 156. Targeted checks include
128 idle receivers, observers, pending access, legacy migration, stale inspection, lost hints,
tenant isolation, queue priority across 1,000 hints, bounded fairness, signed private transport,
and the complete native published-clause journey with twelve Orders and a 60-second fallback poll.
These are correctness/locality checks, not a thousand-document native AWS performance claim.

## Remaining scaling limits

The published Documents clause's `sharedRefresh` explicitly loops over its collection, including
arbitrary `domainNextActions` functions. Engine scheduling cannot silently omit those declared
computations. Truly local refresh needs explicit dependencies or a new package edition with
changed semantics. Similarly, addressed fanout currently prioritizes the target but still pages
subscription metadata in the background. It does not execute every idle sibling, but metadata
work is not yet independent of subscriber count.

Further profiling must continue on the native route. Do not describe a low engine-only number,
a warm run, or a synthetic graph as proof that the complete cold console workflow is millisecond-fast.

## Follow-up findings (157–158)

The second fresh iteration-156 journey used 13 additional session loads instead of loading all old
sessions again. Its client-observed times were 5,469 / 4,041 / 4,284 / 2,893 / 1,797 ms in the five
operation order shown above. Payment engine execution was 102 / 48 / 86 ms for acceptance, report,
and acknowledgement. The acknowledgement's Order and Agreement listeners took 86 / 85 ms.
These are improved but still do not meet the full native-route target.

A receiver inventory then found 86 older documents with explicit control-Timeline channels stuck
at `Unknown target history`. They account for much of the repeated preparation after the new
control-dependency metadata was installed. The previous host gate required the operation target to
be known somewhere in the account before considering this receiver, even though this receiver's
own complete lineage was enough to decide nonmembership.

Iteration 157 passed the full suites with two changes: immutable hosted Timeline definitions are
reused by exact serialized definition; protocol verification reuses completed dependency checks
within the same evidence view. New protocol requests still own new budgets and facts. Ownership,
heads, signatures and current permissions are not cached by these changes.

Iteration 158 changes that host routing gate: a target outside the receiver's complete established
history is unaddressed. It does not execute that receiver's operation. Explicit raw event observers
may still observe it, exactly as they observe known targets belonging to another document. Missing
or incomplete receiver lineage remains an error. Previously stuck unaddressed occurrences can now
advance; this is a deliberate host behavior correction, not a change to a published Contracts gas
schedule. The full suites passed. Its first fresh AWS run still failed the 30-second report-transfer deadline; this is retained as failed latency evidence, not a successful performance result.


## Unrelated-event amplification (159–161)

Iteration 158 exposed an additional defect: one fresh Agreement reached 66 versions during a
small five-operation journey. Most were empty-event updates from embedded access documents,
including old control occurrences unrelated to that Agreement. The engine time for an empty
parent update was tiny, but loading, storing and propagating each version was not.

- **159:** verified ordinary events wake declared raw observers, waiting receivers and conservative
  legacy subscriptions, without waking idle operation-only subscribers. The full suites passed.
- **160:** queue ownership spans the running job. Arrivals during execution coalesce into a
  retained continuation; directly addressed work keeps priority across bounded preparation passes.
  One background job still runs after every eight priority jobs. Tests cover 1,000 queued jobs,
  notifications during an active job, and receiver preparation with unrelated work queued. The full
  suites passed.
- **161:** ordinary events on channels with no declared raw listener receive a version-fenced
  inapplicable disposition. They no longer advance the Contracts checkpoint, publish an empty
  document version or cascade that artificial change to parents. Real operations and declared
  observers keep their existing path. This corrects the host's selective input policy; it does not
  change direct canonical Contracts execution or its gas schedule. A regression exercises 33
  unrelated ordinary events followed by a real operation, requiring zero intervening document
  versions and successful execution of the operation.

Live measurements after these changes must be read separately from the failed iteration-158 run.


## Measured result after 161

The fresh native journey passed. The Agreement had six versions after the full payment journey,
rather than the 66 observed during the failed 158 attempt. Its remaining versions corresponded to
the real Order/Payment work. A later inventory found 197 idle control subscribers and four older
failed fixtures; no receivers remained stuck at `Unknown target history`.

Client observations for the fresh journey were 12,825 / 4,855 / 3,347 / 2,167 / 1,727 ms for
place Order, prepare Payment, accept, report and acknowledge through the Agreement. Bootstrap
was 5,668 ms. A following 16-Order run passed, including payment propagation from the last Order.
The added-Order engine times were mostly 527–836 ms, with one 1,620 ms sample; there was no clear
increasing trend within this small sample. These results are still not the requested latency.

## Protected unchanged access state (162)

For the co-located provider only, the receiving host may reuse an unchanged access document's
actual committed execution result. This requires the same protected store, exact Coordination,
provider and control identities, the same owner, a version-one access record and matching original
and current body addresses. Its original control entry must publish the exact initial grant.
The document grants nothing before that occurrence in the selected total order.

The independently acquired control prefix must still close strictly beyond the business input
before this lookup. A concurrent later provider update cannot be backdated into that closed
prefix. The current access pointer is read strongly on every check; it is not a cached permission.
Any updated access document, different source or different owner takes the existing canonical
historical replay path. Remote sources retain their previous verification behavior.

This is reuse of this host's atomically published execution state, analogous to its protected
source recovery, not a portable proof or a new permission supplied by a request. Focused tests
compare the result with independent replay, check pre-publication denial, revocation and
regrant fallback, store/account/source isolation and constant record reads with unrelated grants.

## Structural evidence union (163)

A full-stack profile of the native journey still found `LanguageMap.putAll` scanning retained
content on small operations. Persistent entry storage alone did not provide persistent union:
the old merge visited every entry, including thousands of identical bodies.

Language evidence now uses an immutable hash trie. Merging snapshots skips identical branches,
keeps ordinary right-hand `putAll` semantics, and does not retain an ancestry log. A changed
right-hand value replaces the left value; absence on the right never removes a left entry.
The change is physical storage only: it removes no validation, proof, gas or permission check.

Randomized tests compare branching merges, removals, nulls and hash collisions against `HashMap`.
A 50,000-entry test checks that two small divergent edits do not compare all retained keys.
The full library and host suites passed.

A short local JMH comparison (one fork, two 300 ms warmups, three 300 ms measurements) measured
union with one added body at 65,536 retained entries: the previous scan averaged 4,451 microseconds
and approximately 2.1 MB allocated; shared-branch union averaged 0.389 microseconds and 432 bytes.
These are isolated map measurements, **not native operation latency**. Live measurements are
required before attributing a workflow speedup to this change.

During follow-up analysis of the earlier AWS recording, converting allocation samples to a giant
JSON report exhausted the test instance's memory and interrupted the service. No business probe
was running then. The instance was rebooted; durable state is stored in DynamoDB. Further profile
analysis must be streamed or performed off-host, with an explicit memory bound.

## Avoid immediately repeating completed native work (164–165)

A successful single-page Timeline read had already verified the checkpoint, signatures, exact
entries and continuity, but converting it into stored range evidence repeated that work.
Iteration 164 retains one bounded completed range in the same verifier. Reuse requires the exact
same opaque starting prefix and the exact canonical evidence identity. Partial pages, altered
evidence, another verifier or another starting prefix use the ordinary verifier. The retained
range has at most 64 entries and a bounded header weight. It grants no current reader permission
or business authorization. Tests compare against independent full verification and exercise
missing content, changed evidence and incomplete reads. A verified ordinary message also avoids
repeating operation recognition, after checking that its exact message identity matches.
The full library and host suites passed.

Iteration 165 reuses an already-open document when its version exactly matches the requested
operation target. Previously the first call could restore a second session for that same version.
Historical and unavailable versions retain the bounded historical-session path. Target content
must still match the selected committed identity; tenant/version and restart behavior are tested.

## Background work and immutable grant decoding (166–167)

The fresh 165 native journey passed all state and two-Timeline checks. Client-observed results
were 4,992 / 4,407 / 2,793 / 2,131 / 2,026 ms; bootstrap was 4,934 ms. Acknowledgement executed
Payment / Order / Agreement in 267 / 135 / 175 ms on that run. The earlier tiny synthetic-engine
figures still do not characterize this complete native workload.

Across a 55-second interval containing that journey and background activity, counters recorded
9,257 record gets, 6,420 work-index queries and 349 acquisition attempts. Only five business
inputs executed. These counters include idle polling; they are not all charged to one operation.

The propagation worker's empty-queue path scanned all sixteen partitions on every call despite
having a separate timed-discovery mechanism. Automatic workers now share that timed discovery
and consume immediate local hints; explicit administrative draining still scans immediately.
A bounded run of stale, already-delivered hints is discarded before reporting idle. A focused
regression caught this stale-hint case in the first candidate; that candidate was not deployed.
Tests cover repeated idle polls, immediate local work, later partitions and a blocked source.
Background discovery visits one partition per 250 ms, so lost hints across a full cycle can take
about four seconds plus storage/queue delay. This is recovery behavior, not normal local delivery.

Access authorization now reuses only the decoded fields of an immutable stored body, with a
256-body bound. Membership and the current access pointer are still read strongly on every call.
Tests warm this cache, revoke the grant from another publisher, change keys, and restore the
grant; each next request observes the current permissions. No permission decision is cached.

## Idle co-located sources (168)

After a source is fully acquired, automatic polling can compare its local publisher's current
head with the already verified prefix. If the head is unchanged, no successor is pending, and no
higher completeness bound is demanded, it performs no signed acquisition. The comparison grants
no new prefix, new data or access permission. A new entry, changed source, failed prior read,
restart, or unmet coverage demand takes the ordinary signed path. Remote providers are unchanged.
The optimization avoids expensive idle self-reads rather than lengthening the polling interval.
Tests verify unchanged prefix identity during idle checks, new-entry detection, refusal to publish
revoked reads, and renewed acquisition for a quiet source with a higher requested bound.


## Iteration 168 AWS measurement

The full host and library checks passed. The deployed build completed a fresh native journey:
bootstrap 4,858 ms; placeOrder 5,025 ms; preparePayment 4,036 ms; acceptance 3,096 ms;
report 1,933 ms; acknowledgement through the Agreement 1,757 ms. These are client-observed
measurements, including network and polling. Payment engine executions were 104 / 114 / 118 ms;
Order and Agreement acknowledgement handlers were 128 / 139 ms. Creating the Order and Payment
still cost 911 / 1,032 ms inside the engine. This does not meet the end-to-end millisecond target.

The native sixteen-Order follow-up also passed, including a Payment in the last Order and shared
participant Timelines. Adding Orders 4 / 8 / 12 / 16 took 2,163 / 2,279 / 2,305 / 2,391 ms;
the final acknowledgement reached the Agreement in 1,851 ms. This bounded run is not evidence
for thousands of native Orders. A 90-second, 32 MiB-bounded JFR recording accompanies the run.

## Iteration 169: immutable historical grant facts

Historical permission lookup still reparsed the unchanged grant and its original signed control
publication for each receiver. Retain only the immutable initial identity, publication order and
executed permission sets in a bounded 256-entry cache. Each lookup still strongly reads the
current grant row, validates ownership and provider/control binding, and requires version one
and identical original/current content. Any update, including revocation followed by restoration,
falls back to ordinary historical replay. Publication time is compared separately for each input.

Focused access, private-ingress and native tutorial tests pass. A new test verifies one current
row read per warm lookup, earlier-input exclusion, foreign-owner exclusion, and peer revocation
and restoration. Full regression and AWS measurements remain required before claiming a gain.


## Iteration 170: immutable storage decoding

The 90-second native profile sampled roughly 6.3 GB of allocations directly below DynamoDB
record reads, and substantial repeated Language validation. This is sampled allocation, not
retained heap. The host was repeatedly decompressing and parsing the same checkpoint bytes.
DynamoDB reads remain strongly consistent; only decoding of identical returned body bytes is
reused. The 64 MiB estimated cache freezes nested JSON and never caches row identity, revision
or absence. A different body at the same key and revision cannot hit the old decoded value.

Focused storage and tutorial tests passed. Tests check revision changes, changed bodies,
deletion/recreation, nested immutability, byte identity, weight eviction and disabled capacity.
The shared JSON-boundary test store also freezes nested data, exercising ordinary runtime
consumers against the same immutable representation.

## Iteration 171: revocable native semantic memo branches

A native Timeline call supplies temporary verifier facts. Captured BEX views previously discarded
all completed semantic validation whenever those facts existed, repeatedly validating unchanged
content. A shared invocation scope now owns the semantic memo branches. Captured views reuse
completed checks during that scope; closing it restores every branch's pre-scope heads,
concreteness facts, validation and verified closures. Temporary cut points cannot become permanent
admission facts. The scope retains no branches after closing. This changes physical work only.

A focused test checks fast parent/child/grandchild reuse, revocation in every branch, repeated
close, rejection after close, missing-content failure after revocation and preservation of
independent facts established before the scope. Full native regression and live measurement
remain necessary before calling this a performance result.


## Iteration 171 live result and remaining work

The full suites passed: 2,408 library and 458 host tests. The installed artifact was
`75ec126e33767328ebdf87bba587a2051adace2b2b36497555395b9bab6244e5`.
The first QA bootstrap returned 503 because Secrets Manager reported a pinned reader secret
not found. Retrying the same idempotent command recovered; the first failure remains in evidence.
The 3,344 ms bootstrap retry is not a fresh-bootstrap latency measurement. The five subsequent
operations were first executions: 4,362 / 3,480 / 2,223 / 1,939 / 1,584 ms client-observed.
Payment engine execution was 100 / 83 / 119 ms; final Order / Agreement handlers 99 / 108 ms.

The 32-Order follow-up passed, including shared Timelines and final paid totals. Order additions
ranged from 1.897 to 8.496 seconds (median 2.218 seconds); final acknowledgement reached the Agreement in 1,572 ms.
Payment acknowledgement itself took 50 ms, Order 58 ms, Agreement 202 ms. This remains short of
the end-to-end target. A bounded JFR profile showed large allocations in DynamoDB SDK reads,
Language validation and repeated persistent-map/set operations. Decoding reuse alone cannot
remove network SDK allocation for full large bodies.

## Iteration 172: exclude known-unrelated operation preparation

A verified occurrence already identifies an ordinary event or an explicit operation target.
When the receiver's complete lineage establishes that target is unrelated, preparation no longer
reopens the original message merely to rediscover that fact. Ordinary verified events also need
no operation grammar replay. Raw observers still participate; addressed and unclassified inputs
still resolve through the usual grammar. Historical permissions, order, coverage, conditional
publication and checkpoints retain their existing checks. The RESOLVE_INPUT counter measures
actual message resolution separately from preparation.

Tests verify no message resolution for ordinary history or unrelated targets, normal resolution
for an addressed operation, continued raw observation, and stopping at a relevant look-ahead
without losing it. The existing missing-lineage, checkpoint, permission and restart tests apply.

## Iteration 173: strongly select large cached bodies by digest

Large DynamoDB rows were still transferring their full compressed checkpoint on every read.
New writes include a digest of the compressed body in the same conditional item. After a full
read verifies and caches a large body, later reads strongly fetch current key/revision/digest.
If that exact body is cached, it can be reused without transferring and decoding it again.
Changed or evicted bodies trigger a full current read; legacy rows stay on the original path.
A race may return a newer complete row from the second read, as allowed by a linearizable get.
No mutable row is selected from a stale local cache. DynamoDB still charges reads by item size;
this optimization targets transfer, SDK allocation and decoding, not a read-capacity reduction.

Tests cover changed revisions, changed content at the same revision, deletion/recreation,
legacy rows, corrupt full-body digests and strong consistency on every request.


## Iteration 174: avoid allocating traversal state for completed checks

The allocation profile also identifies repeated construction of validation queues for already
completed scalar/schema checks. Validation now checks the completed recipe/goal/root fact
before allocating a work queue and visited set. Strict validation similarly skips traversal
allocation when the complete dependency closure is already established. The same diagnostic
phase, goal validation and work steps remain. A focused test checks the exact five-step warm
budget, failure at four steps, and rejection of changed invalid content. No newly supplied
content obtains authority from this shortcut.

The complete suites passed through iteration 173. Its initially failed storage test caught an
unintended digest addition to immutable content pages; that was removed, preserving their
existing three-attribute wire format. Conditional document records alone gain the digest.


## Iterations 175–176: immediate wakeups and immutable candidate reuse

A deterministic propagation regression reproduced loss of a local wakeup while another worker owned the same source. A competing publication could also make the final acknowledgement lose its conditional write without scheduling an immediate retry. Iteration 175 coalesces notifications during ownership and retries failed acknowledgements from the local queue. Both regression variants failed before the fix; the propagation concurrency, recovery and ordering tests passed after it. The full check passed.

Iteration 176 removes the candidate-head cache's repeated checkpoint JSON encoding/decoding. It keeps deeply immutable record values with an estimated 64 MiB heap budget, rather than 16 MiB of serialized bytes. A candidate remains speculative: durable reads and conditional publication still establish the current version. Tests cover caller mutation, account isolation, undefined/null distinction, invalidation and oversized replacement. It also avoids eager fallback hashing and adds a per-request `Server-Timing: app;dur=...` header to distinguish HTTP service time from client-observed latency. The full check passed, the build was deployed, and AWS reported healthy.

Iteration 174's native 64-Order run passed all business assertions, but did **not** meet the latency target. Across all 63 additions, minimum/median/maximum were 1,737 / 2,674 / 12,207 ms. Median first eight was 2,066 ms and last eight 3,262 ms. Final Payment preparation/acceptance/report/acknowledgement took 3,081 / 2,808 / 9,121 / 2,486 ms client-observed. These include outliers omitted by every-fourth progress logging.

The 171 allocation profile also included substantial JFR StringPool allocation attributed below DynamoDB calls; its entire sampled total must not be described as application JSON allocation. Profiling and unprofiled wall-clock measurements are distinct evidence.


## Iteration 177: prioritize coverage waiters

Native entry arrivals already prioritized an addressed receiver. Empty completeness updates did not: they walked the source subscriber list to find waiting receivers. A regression with 128 idle siblings proved this queueing difference. The fix retains bounded local waiting hints (256 sources, at most 16 receivers each), strongly rechecks receiver scope/subscription/status, and conditionally marks those receivers ready before continuing durable subscriber paging. No hint proves permission or completeness. Cache loss, excess waiters and restart retain the original durable path. Tests cover prompt wakeup, concurrent subscription removal and more waiters than the hint capacity. The full suite passed; deployment follows.

Iteration 176's fresh native workflow passed. Client submission / server submission / completion (ms): placeOrder 1048 / 783 / 5281; preparePayment 818 / 509 / 3546; accept 658 / 444 / 2288; report 696 / 456 / 1878; acknowledge through Agreement 429 / 216 / 1742. This remains well above the requested end-to-end target. Server timing excludes network transit and body transmission; it does not include later asynchronous execution.


## Admission and publisher schema reuse (178–179)

Admission previously generated canonical content, decoded it again, and compared identities on
 every call, then discarded the generated content. Iteration 178 removes that redundant codec
 self-check from admission. Strict Language validation and Source omission/revalidation remain;
 canonical export is still available explicitly. Canonical round-trip cases now exercise scalar,
 arbitrary-precision integer, double, record, dictionary, definition and append-only list exports
 in the language test suite. Two initially proposed fixtures were invalid Language shapes and
 were corrected before qualification.

Iteration 179 gives each hosted publisher a bounded cache of previously completed immutable
message-schema work. It retains neither provider callbacks nor native admission facts and is not
an authorization or history cache. Invalid new messages still fail strict validation; another
publisher cannot use this publisher's retained content. The host retains at most four publisher
caches, each capped by a conservative 64 MiB estimate. Exact current state, ownership, publication
transactions and consumer verification retain their original paths. This is an experiment pending
fresh AWS measurements, not evidence that the millisecond latency target has been met.


## Idle-source work (180)

Fallback polling rebuilt evicted validation state and reloaded reader credentials before checking
whether an inactive co-located source had changed. The runner now retains up to 4,096 small idle
markers separately from its 64 active source objects. Before skipping work it strongly rechecks
the immutable registration, retry status, archive revision, requested coverage and current
publisher head. A new entry, demand, archive version or retry falls back to acquisition. The marker
cannot grant read access, advance a cursor or issue completeness. Tests cover invalidation by
new entries, archive changes and coverage demand; existing access-revocation checks remain.

The fresh 179 AWS journey passed, but remains seconds: bootstrap 4,716 ms; client-observed Order
creation 5,005 ms, Payment creation 3,809 ms, acceptance 2,449 ms, report 2,023 ms and acknowledgement
through the Agreement 1,724 ms. Server submission time was respectively 834/481/411/397/149 ms.
These measurements do not establish millisecond completion or thousand-Order scalability.


## Background fanout priority (181)

The coalescing queue retained a running job's urgency for every continuation, even when the source
requested an ordinary subscriber-scan continuation after delivering its urgent notifications.
That incorrectly promoted an entire background scan. Queue continuations now carry their own
priority. Receiver catch-up explicitly preserves priority; source scans explicitly return to
background priority. A new urgent arrival during execution still wins over background demotion.
Regressions cover all three cases, including bounded fairness for ordinary work.


## Lazy input content and committed child sessions (182–183)

The 179 CPU profile attributed 114 sampled stacks to unioning the offered content catalogue during
`ContractReception.operation`. The receiver now borrows that catalogue for the duration of grammar
inspection and loads only requested records. Exact identity and cyclic-proof validation still run;
verified bodies remain available, while unused content and provider callbacks are released. A
10,000-unrelated-record regression checks that reading one selected record retains only that record.
Other cases check bad evidence, scope cleanup and cyclic export after closing the scope.

The same profile found substantial checkpoint restore work. Child creation previously initialized
a child, exported it and closed its session, then receiver startup restored the same child. New
sessions now have an explicit pending owner until their enclosing transaction is known to commit.
Only then can they enter the ordinary bounded session cache. Failed or uncertain publication closes
them; a concurrently loaded or running session always wins. Tests cover abort, successful handoff,
account/version isolation, concurrent receiver ownership and cache pressure.

The 179 16-Order measurement (15 additions) was min 1,867 ms, median 2,262 ms, max 10,904 ms. A final
Payment preparation took 9,275 ms. Those pauses remain part of the evidence; they were not removed
from the statistics. The bounded profile included 21 GC pauses over 10 ms (up to 81 ms); those
individual GC pauses do not explain a ten-second outlier.


## Frozen source selection (184)

A frozen source view retains its current cursor result and batch. Advancing another Timeline in
the ordered merge no longer rereads this source's unchanged history row. The cache holds one
cursor result and one batch per view; acquisition after the cut cannot become visible through it.
The full suites passed, and the 53 installed JARs matched the tested build before deployment.
The fresh AWS run still took 5,685 / 3,522 / 2,151 / 2,082 / 1,714 ms for Order creation, Payment
creation, acceptance, report and acknowledgement through Agreement. All state and shared-Timeline
assertions passed. This is not millisecond end-to-end performance.

## Local publication races and source registration (185–187)

Source acquisition atomically advances both the source prefix and its FANOUT scheduling record.
A notification worker can advance FANOUT during that attempt without changing the source prefix.
Previously this ordinary conflict became an acquisition exception followed by provider backoff.
Acquisition now makes up to four conditional attempts, refreshing only the scheduling record
while the exact source cut remains unchanged. It does not repeat proof validation. If the source
changed or contention persists, a typed local conflict requests a fair fresh acquisition, without
provider-failure backoff. Invalid signatures, unavailable transport and uncertain commits keep
the existing failure paths. A regression injects real FANOUT progress between reads and commit,
checks one verification/two commits, preserves that progress and publishes exactly one source batch.

A long-poll-interval regression also found that source adoption and reader registration did not
signal immediate acquisition. Both now signal only after their durable registration commits.
Parameterized tests use a 60-second fallback interval with and without an injected local conflict;
the reader must make progress before the fallback. Focused tests passed. Full-suite and AWS results
are recorded in the next qualification section when available.

Receiver routing also uses one version-pinned lineage view for its batch. Coverage is read first,
then each distinct membership and historical publication is checked once. Later document versions
remain excluded; a new invocation obtains a new cut. Regression tests cover repeated reads, a peer
advancing the document, future identities and cross-account isolation.

These are reproduced defects, but the retained participant-source proposals for iterations
174/179/181/184 did not show publication conflicts. The fixes alone do not establish the cause of
every prior 9–12 second outlier; absence of a final retry record cannot exclude transient failures.


## Early required-source acquisition (188)

After an addressed operation is durably published, the host requests exclusive timestamp coverage
from the receiving document's declared sources and immediately signals their readers. Those reads
can overlap acquisition of the incoming entry. Previously they started only after receiver
selection discovered that another source was behind. These are best-effort scheduling hints:
current declarations, permission, source ordering and actual verified coverage still determine
admission. Failed prefetch cannot turn an already durable submission into a failed API response;
ordinary receiver selection remains the recovery path. The regression checks all declared source
hints, tenant isolation, and that prefetch itself neither processes nor authorizes an operation.

The complete suites passed through iteration 187 (478 host tests). The initial long-interval
regression failed because adoption did not signal the worker; fixing that production defect made
both the normal and publication-conflict cases pass. Iteration 188 is under qualification.

The shared provider control source does contain retained publication conflicts: inspection of its
324 protected Q# records is in `control-conflicts-184.json`. This confirms the faulty provider-backoff
classification affected a source required by the private workflow, although those records alone
do not time-correlate each historical client outlier.

## Iterations 189–191: eliminate repeated immutable work

- 189: `DocumentRuntime.Session` retains one immutable checkpoint for its current committed
  document wrapper. A successful state change selects a new wrapper; a rejected operation keeps
  the previous export. Privately issued prepared children start with their already verified export.
  Foreign or externally constructed prepared values still take ordinary restore verification.
- 190: semantic snapshot sets use the existing persistent hash trie, including structural union.
  Collision, removal, null-key and independent-snapshot tests compare behavior against `HashSet`.
- 191: cache individual immutable contract headers as well as whole declaration maps. Updating a
  lifecycle/checkpoint marker no longer reparses every unchanged operation and channel header.
  Per-document marker, channel-reference and placement checks still run. Changing installed
  native or interpreted bindings invalidates these caches.

The bounded local signed-clause profile (12 orders, payment → order → agreement) had 262 sampled
CPU stacks under `Core.execute` before 189/190 and 185 afterward. These are sampling observations,
not a claimed wall-clock percentage or a replacement for AWS measurements. The checkpoint path
was a prominent redundant cost in the earlier recording. End-to-end native latency remains the
acceptance target.

- 192: install native runtime configuration once, then fork its immutable evidence/memos for
  independent document sessions. Mutable registries, witnesses, gas, archives, and BEX invocation
  state remain session-owned. Concurrent initialization/restoration tests check state and gas
  isolation; existing native-extension tests exercise the installed callbacks.


## Iteration 193: share verified immutable content at the storage boundary

Publisher, receiver and engine repositories previously had independent content caches.
DynamoStore now retains a bounded 64 MiB cache of hash-verified immutable pages, scoped
by account. Successful reads and acknowledged publications populate it using owned
bytes; mutable rows, missing pages and permissions remain uncached. A mixed batch
fetches only its misses. Failed or ambiguous publications do not prime the cache.
Tests cover defensive copies, account isolation, corrupt data, failed writes and
mixed hit/miss batches. This removes repeat physical reads across host components;
it does not change timeline verification, gas or publication semantics.


## Iteration 194: edit private identity-map branches in place

ContractIdentityMap formerly rebuilt each trie branch on every new identity witness,
even when no captured view shared the branch. It now uses private ownership tokens.
Forking revokes ownership in constant time; each map copies a shared branch before
writing, while consecutive writes to its private branches reuse them. The change
preserves identity-key semantics, null values, snapshot isolation and trimming.

A bounded, alternating microbenchmark with 160,000 writes and 4,000 captured views
reported 72.00 MB allocated before versus 34.26–34.45 MB after. The five measured
trials were 11.98–18.56 ms before and 11.03–12.83 ms after. This is a data-structure
measurement, not a claim about end-to-end AWS latency. The complete regression suite
and randomized branching-snapshot model test passed.


## Iteration 195: keep ordinary identity-ledger keys as identities

Every ordinary established-identity lookup used to construct and JSON-encode the same
`["ordinary", identity]` key. Ordinary identities now use their existing immutable strings
in the invocation ledger. Canonical completion export still emits the exact original
array, in the same canonical order. Cyclic identities retain their scope-specific key,
and speculative forks copy both inventories. Full canonical receipt/gas and host tests
passed; this changes representation only, not reuse eligibility or validation.


## Iteration 196: sparse source fanout

The bounded AWS recording showed 11.60 aggregate seconds in fanout transaction socket
waits, versus 1.00 seconds in the direct document-admission commit path during that
recording. These are sums across threads, not per-request latency or proof of a
specific request's critical path. Fanout continued publishing a cursor after each
16 subscriptions even when almost all receivers were idle and did not need a wakeup.

Fanout now scans up to 64 subscriptions before publishing the cursor. Current receiver
records are still fetched in batches of 16, and each transaction still wakes at most
16 receivers plus updates its source cursor. Dense notification work stops at the
last actually processed subscription; no later receiver is skipped. Sparse scans
need fewer cursor transactions without increasing the dense-case read batch or
transaction size. The existing concurrency, removal, recovery, tenant and observer
tests remain; a 256-subscriber sparse regression requires at most four scan pages.

This still scans subscriber metadata. It is not constant-work targeted delivery; a
durable index for addressed recipients, raw observers and waiting receivers remains
necessary to eliminate metadata work proportional to unrelated subscribers.
