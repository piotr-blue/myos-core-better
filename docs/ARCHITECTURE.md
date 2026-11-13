# How MyOS publishes a document result

MyOS receives signed Timeline entries, establishes their order and publishes the outcomes of authored Blue documents. Blue owns document identity, channel admission, events and gas. MyOS owns verified source history, the right to advance each owner, and the durable publication readers can see. An owner is an independently scheduled unit derived from the native embedding graph. A cycle can put several members in one unit. [Owner partition](../src/main/java/myos/better/OwnerPartition.java) · [Native runtime and packages](RUNTIME-PACKAGES.md)

| Maintainer's question | Follow this path |
|---|---|
| How did a signed entry become available? | [TimelineArchive](../src/main/java/myos/better/TimelineArchive.java) verifies and acquires the source range. [Timeline acquisition](TIMELINE-ACQUISITION.md) describes the signed evidence. |
| Why is an owner waiting? | [OwnerFrontiers](../src/main/java/myos/better/OwnerFrontiers.java) selects a candidate and checks its claim. [OwnerCoverage](../src/main/java/myos/better/OwnerCoverage.java) verifies that its source authorities cover the candidate's timestamp. |
| Which document processed the input? | [OwnerGraphs](../src/main/java/myos/better/OwnerGraphs.java) opens the claim and chooses the execution path. [GraphTimelineIngress](../src/main/java/myos/better/GraphTimelineIngress.java) supplies native admission. |
| What does the parent receive from a child? | [OwnerLocalDelta](../src/main/java/myos/better/OwnerLocalDelta.java) gathers the selected completion, edge generations and content references. [OwnerLocal](../src/main/java/myos/better/OwnerLocal.java) runs and prepares the result. |
| Why can this page be read after a restart? | [CanonicalSnapshots](../src/main/java/myos/better/CanonicalSnapshots.java) owns page manifests. [SnapshotRows](../src/main/java/myos/better/SnapshotRows.java) encodes immutable rows. [OwnerMetadata](../src/main/java/myos/better/OwnerMetadata.java) owns the conditional manifest. |
| Which service applies to a timer, bootstrap or account operation? | [OwnerTimers](../src/main/java/myos/better/OwnerTimers.java), [CoreBootstrap](../src/main/java/myos/better/CoreBootstrap.java) and [functional parity](FUNCTIONAL-PARITY.md) identify the entry points and supported cases. |

## How work enters the service

A bootstrap request resolves the initiating account, the authored participants and their reader or writer bindings. That produces the initial document and its Timeline sources. Later operations enter through signed Timeline history. Its verified provider identity, contiguous entries and exclusive completeness bound give a worker the evidence it needs to order an input. A failed import preserves the last verified prefix. A source-integrity fault can fence an affected owner while unrelated documents continue to run. [Bootstrap implementation](../src/main/java/myos/better/CoreBootstrap.java) · [Timeline imports](TIMELINE-IMPORTS.md) · [Recovery cases](../src/test/java/myos/better/OwnerSourceFailuresTest.java)

A timer registration creates a durable occurrence. Wall-clock time makes it eligible. Before a worker may claim it, ordinary source completeness must also cover its timestamp. This prevents a timer from overtaking a late external entry that belongs earlier in the same document history. The owner publication records timer effects and the durable wake needed after a restart. [Timer eligibility tests](../src/test/java/myos/better/OwnerTimeFrontiersTest.java) · [Timer publication recovery](../src/test/java/myos/better/OwnerTimeCommitRecoveryTest.java)

## Why ordering and channel admission are separate

Suppose two Order bindings use the same remote Timeline. Both contribute to the owner's count for that authority. A third binding to that same Timeline increments its count. Removing the last use removes the authority from the distinct set. The scheduler updates a binding's contributions in the transaction that changes the topology. It propagates a changed distinct set to the containing owners. A child completion or a change to the earliest candidate can independently wake a parent.

To process an input at time 100, each required authority must establish completeness strictly past 100. A provider bound of 100 still allows an entry at 100. A local seal covers the local authority. An account-wide minimum external bound can certify all external sources at once. If an unrelated provider lags, the scheduler checks the owner's own distinct authorities. It orders equal timestamps by Timeline identity and entry identity. Only then can native admission determine which declared channels may receive that entry. [Coverage index](../src/main/java/myos/better/OwnerCoverage.java) · [Ordering and source failure tests](../src/test/java/myos/better/OwnerSourceCompletenessTest.java) · [Routing and refcount tests](../src/test/java/myos/better/OwnerRoutingTest.java)

## Why publication uses a claim and immutable pages

Consider a Payment acknowledgement reaching its Order and Agreement. A worker claims the eligible original against a specific owner head, source positions and lease. It restores the selected predecessor and runs native execution outside the metadata transaction. The Payment produces a completion. At the Order, MyOS resolves every selected path and generation and supplies the original entry and protected completion to Blue. A second path to the same Payment carries its own attachment while the host adopts that completion's records once. The Agreement follows the same process when it receives the Order's result. A graph change outside the supported local path uses ordinary native processing against the pinned graph. [Execution selection](../src/main/java/myos/better/OwnerGraphs.java) · [Clause-only Payment reaction test](../src/test/java/myos/better/ClausePaymentReactionTest.java)

The worker can stage immutable pages before the conditional commit. The commit validates the claim and publishes the new head, history, completion, source changes and related metadata together. A lost race can leave unreachable pages. A reader following the previous head still sees the complete previous state. Publication recovery distinguishes a committed response that was lost from a stale or expired claim. An earlier child publication remains available to a parent after a worker restart. [Claim and commit implementation](../src/main/java/myos/better/OwnerFrontiers.java) · [Expiry, contention and lost-response tests](../src/test/java/myos/better/OwnerFrontiersTest.java)

## Why snapshot writes distinguish replacement from merge

A whole-snapshot write supplies the complete set of member or edge rows. Omitting an old row removes it. A local publication supplies changed rows. Its merge reads the named keys and retains all other entries under the previous radix root. Substituting a replacement for a merge would silently discard sibling history from the new snapshot. Preparation deltas also declare new members explicitly and verify the predecessor snapshot and environment.

The immutable row references support inline JSON, direct content addresses and block manifests for large values. The cache can evict a complete index or body at any time. A cold reader can reconstruct a committed version using its persisted manifest. The storage tests exercise exact historical addresses, prior-release pages, bounded cold lookups and content shared between owners. [Physical row encoding](../src/main/java/myos/better/SnapshotRows.java) · [Previous-release compatibility](../src/test/java/myos/better/PreparationCompatibilityTest.java) · [Snapshot tests](../src/test/java/myos/better/CanonicalSnapshotsTest.java)

An embedded Order's body has its own BlueId. The Agreement also commits to the keys and values in its authored Orders map. Adding a key can change that whole canonical record even when MyOS selects two channels and updates only a few physical index paths. Interpret receiver counts, native execution time, storage time and client observation time separately. [Operations and runtime profiles](OPERATIONS.md)

The earlier document-local experiment has a separate [historical architecture record](HISTORICAL-DOCUMENT-LOCAL-ARCHITECTURE.md). Its outbox model and scale measurements belong to that protocol.

## How owner preparation binds timelines

A native receiver is identified by its member, channel and generation. Owner preparation groups these declarations by the owner selected for this graph revision. Members of a cycle share that owner. Each direct source retains its historical storage key and its native checkpoint, when present. Different lifetimes on the same timeline keep distinct keys. Access-control timelines cover private history and can be shared within the owner.

Building the groups visits each declaration once. New sources can look up their indexed checkpoint and verify its timestamp against the retained original. An existing source keeps its established frontier and does not reopen that original. A full plan still has to enumerate the graph and its declarations. [Source grouping](../src/main/java/myos/better/OwnerTimelineSources.java) · [Owner and checkpoint regression](../src/test/java/myos/better/OwnerTimelineSourcesTest.java)

## How verified input narrows delivery

A signed occurrence can arrive before the host knows whether it carries an operation or which document it addresses. Unknown inputs wake every subscriber to the source. A verified ordinary event wakes raw observers. An addressed operation also wakes subscribed owners with the historical target identity. Coverage-only bindings maintain the ordering bound while suppressing direct delivery. The per-owner relevance check and the reverse lookup share the classification and subscription records. Learning a target later refreshes pending candidates in the same transaction. A conflicting verified target fails that transaction. [Routing index](../src/main/java/myos/better/OwnerRouting.java) · [Late evidence and 1,000 subscribers](../src/test/java/myos/better/OwnerRoutingTest.java)

## Following a rejected signed operation

The account's operation admission records whether its signed entry was published. The input resource identifies how a particular document processed that entry. A native rejection has its own history position and can advance the epoch while leaving business fields unchanged. Read the disposition’s epoch and code when present. Older retained history may supply only terminal status. The immutable processing receipt commits to the outcome digest. The signed-in document history retains the diagnostic behind that digest.

Timeline routes bind their read and write views to the same authenticated session. The HTTP boundary delegates append admission to the existing timeline entry service, which owns publication and ordering.

## Reopening a page of embedded documents

The retained detached-link format stores direct children in an immutable link index. Its direct-child cursor contains the account, parent, retained version and last path shown. Following it opens the same parent version even if another worker has attached or removed an Order. The link index can read one path or the next page without opening the bodies of other children. When following a longer path, the host opens each intermediate child at the version its parent observed. Current canonical graphs keep their adjacency in snapshot pages and their dashboard read path uses GraphDocuments. [Canonical graph reads](../src/main/java/myos/better/GraphDocuments.java) · [Embedding queries](../src/main/java/myos/better/EmbeddingLinks.java) · [Cursor across a restart](../src/test/java/myos/better/EmbeddingLinksTest.java)

The SDK accepts both previously retained flat history identifiers and the newer structured BlueId references. A stable input observation requires the document, receipt and position for the requested signed entry. A terminal rejection can instead carry a disposition. [SDK polling contract](../sdk/typescript/helpers/inputs.ts) · [Observation tests](../sdk/typescript/tests/inputs.test.mjs)


## Measuring a payment through the whole graph

The local `benchmark` command creates Orders by calling the root Agreement. Its Payment channels include the Order key and Payment key. The selected Payment records the amount, its Order forwards the event and the root credits exactly one payment. An unaddressed operation with the same channel name on several members can reach all of them. New benchmark Payments therefore use separate channel names and filters. Reusing an older benchmark account preserves the authored declarations already stored there.

The canonical result reports `graphExecutionMillis`, `graphCommitMillis` and `endToEndMillis`. The first measures the interpreter, the second includes the durable call, and the third ends after verifying the root aggregate. The timings include different amounts of work and are all measured per operation. The owner scheduler has a separate oracle-checked `ownerScaleProbe`. [Benchmark source](../src/main/java/myos/better/Scenario.java) · [Multi-payment restart test](../src/test/java/myos/better/ScenarioTest.java)

## Session shutdown

An acquiring document holds its document lock while the host measures and opens its checkpoint outside the cache lock. The cache records that load before releasing its metadata lock. Shutdown refuses while a load or a pinned execution is active, so a failed shutdown leaves the existing sessions usable. Once close succeeds, a new acquisition fails before it opens resources. Child sessions prepared before shutdown are disposed if their handoff arrives after close. [Session cache](../src/main/java/myos/better/SessionCache.java) · [Deterministic shutdown regression](../src/test/java/myos/better/SessionCacheTest.java)
