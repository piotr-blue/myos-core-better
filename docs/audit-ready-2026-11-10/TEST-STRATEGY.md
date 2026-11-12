# Test strategy and evidence map

The audit separates interpreter semantics, owner scheduling, physical storage, public API behavior and performance. A test that proves one layer is not counted as proof of all layers.

## Interpreter and accounting — blue-java-better

Run the full `check` task. This includes Language/BEX/Contracts semantics, additive accounting and owner-local execution tests, strict compilation, formatting and Javadoc. Relevant entry points include `LocalOwnerExecutionTest` and the gas/accounting suites. The library sources are unchanged by this host optimization; they are nevertheless rerun in the delivered snapshot.

## Local persisted model — myos-simplified

Run `test`, `clausesTest`, and `scaleTest` separately so tagged qualification is visible. `OrderingParityTest` covers cross-timeline ties, earlier detachment, historical child views, catch-up, event occurrence ordering and cyclic rounds. `GraphShapeParityTest` compares chains, diamonds, shared cycles, connected cycles and cyclic roots under three accounting schedules after runtime reopen. `CrashRecoveryTest` uses killed subprocesses; `OwnerConcurrencyTest` covers independent progress, lease recovery and concurrent workers. Clause suites exercise published activation, documents, payments, products and state transitions.

`BacklogScaleTest` includes 100,000 scheduler rows. It is a storage/scheduler test, NOT a 100,000-document native execution benchmark. `OrderScale` is the real native Payment → Order → Agreement benchmark on separate local timelines and reopened SQLite storage. Its whole-root comparison is outside the measured drain. Run twice in fresh directories and keep each sample.

## DynamoDB application — myos-aws-test-better

Run the full `check` task and build `installDist qualificationJar`.

- `TimelineBindingTest`: 101 static aliases, immutable namespace binding, reopening and incompatible configuration.
- `TimelineWorkTest.LargeSourceSets`: 201 sources, abandoned index preparation, arrivals before receiver publication, restart, membership removal and reattachment.
- `OwnerRoutingTest`: targeted delivery at 10/1,000 subscribers; untouched unrelated source cursors; observers, broadcasts, unknown inputs and historical target identities; duplicate dependency removal; local/external authority transitions; legacy-index backfill.
- `OwnerSourceCompletenessTest`: exclusive external bounds, imported earlier entries, independent local progress, failed range atomicity and coherent publication.
- `OwnerFrontiersTest`: tied order/detachment, frozen history, shared parents, replay, fencing, idempotency, failed claims, bounded retry allowance and descendant deferral.
- `OwnerGraphsTest`: cold native split/whole equivalence, several selected Order indices, additive local eligibility and whole-graph fallback on business failure.
- `OwnerBirthParityTest`: structural creation followed by nested operation and restart.
- `OwnerCycleTest`: four/eight member cycles with chords under published and additive accounting, cold recovery and whole-native comparison.
- `OwnerMetadataTest` and `OrderedIndexTest`: selected batched reads, account isolation, stale transaction rejection, old immutable roots, byte bounds, ordered pagination and 100,000-key path locality.
- Dynamo storage, publication, concurrency and event-outbox suites: conditional transactions, immutable content publication, retry classification and incomplete reads.
- Private timeline ingress and access-history suites: historical revocation, multiple readers, missing guarantees and cold permission recovery.
- `CanonicalClauseTutorialTest`: real published-clause bootstrap and complete payment workflow through two participant timelines, with whole-root comparison. Its old eager-cursor assertion is replaced by the semantic condition that unrelated historical messages cause no native owner turns.

## AWS qualification boundaries

`qualify_owners.py` launches a dedicated temporary worker, disables transport caches, reopens the owner processor per input and reports acquisition separately from durable execution. The JVM and common immutable definitions stay warm. The signed external timeline fixture is synthetic and local to the probe; storage is real DynamoDB. This is not measurement of an independent remote provider's internet response time.

The fixed-target run preserves the previous benchmark's workload. Rotating and separate-source runs emit two equal-valued public root events and verify both distinct ordered occurrences. A separate rotating-target run prevents all results depending on repeated access to the first Order. Exact whole-document BlueId/gas/ordered-event comparison happens after timing using independent storage. A successful sample must execute exactly three owners and report `owner-local`; fallback is an error for this workload.

The deployed HTTPS scripts cover the actual authenticated application, native local timeline submission, committed root history, account isolation, API keys, idempotency and private access. The restart check operates on an existing graph, not a newly initialized replacement. Network and client polling are included in those times.

## Interpretation

Raw samples and all test-case outcomes are retained. Small sample sets are qualification evidence, not statistically established production SLOs. Test failures during development, changed assertions, before/after workload differences and untested scenarios belong in REPORT.md. No successful storage-only test is presented as proof of full native semantics; no warm-JVM measurement is presented as a whole-process cold-start latency.
