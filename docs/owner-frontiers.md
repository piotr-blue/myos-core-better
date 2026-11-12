# Independent document owners on DynamoDB

New documents use this implementation when `MYOS_OWNERS_FAST=true`. Their host handles
begin with `graph-fast-`. Previously created `graph-*` histories retain their original
execution path. Host handles and scheduling metadata do not become Blue document fields.

## What executes

`OwnerGraphs` uses the canonical `Contracts.Execution` persistence adapter from blue-java-better. Native Contracts discovers the
embedding graph and strongly connected components (SCCs). An acyclic document is one
owner; a pre-existing cyclic component is one owner containing multiple native members.
Each attempt restores its immutable protected snapshot, executes, and discards its session.
The shared caches contain bounded immutable pages, package definitions and calculation
facts; processing does not require every document to remain in memory. Execution restoration
currently batches the rooted content closure: testing the snapshot-pinned lazy provider against
real AWS clauses increased latency. Lazy presentation and the library provider API remain
available, but they are not advertised as faster execution on this rooted adapter. Account-wide
semantic facts and persisted preparation are disabled for this path. Existing checkpoint content
catalogues are inherited when publishing deltas. The exact before-checkpoint is reused by identity,
and unchanged operation/address projections are retained. Loading a current execution closure uses
indexed identity lookups; it does not enumerate every body in the historical catalogue.

A Payment owner can advance independently of an Order or Agreement that embeds it. Each
receiver retains its own source cursor and lifetime. It consumes the appropriate historical
result, rather than the child's newest state. Several parents can consume the same history.
If an earlier root operation removes an embedding, later results for that lifetime are no
longer applied to that root. The independent child still has its own existence and history.

The current receiver adapter restores the rooted native view and runs the canonical attempt.
It reuses validated BEX calculations but does not yet substitute a child's complete native
turn. This preserves the canonical shared meter, failure rollback, document identity and
ordered emissions. It also means rooted processing still has graph-size costs. Do not
advertise constant-time processing for arbitrarily large rooted graphs.

## Three independent mechanisms

1. **Owned timelines.** `HostedTimelines` serializes accepted appends and advancement of a
   per-account local exclusive completeness bound through the same durable authority. It can
   enroll only a verified archive at the identical owned timeline head. Future local entries
   cannot precede the established bound. Per-timeline time is strictly increasing; different
   timelines may have the same timestamp. Access-control histories remain individually
   verified sources, not automatically part of the data-timeline authority.
2. **External timelines.** Import pins the provider, signed descriptor, exact timeline and
   reader-key custody. Discovery verifies custom provider data subtypes by their exact
   identity. Independent polling acquires signed contiguous ranges and their exclusive
   `completeBefore` boundaries. Manual Retry resets acquisition; a blocked document also
   asks the acquisition worker to advance the insufficient source. Acquisition asks for a
   useful current range, not one timestamp at a time. A provider failure retains the last
   verified prefix and blocks only owners depending on insufficient coverage.
3. **Document execution.** The indexed merge selects the earliest available original by
   `(timestampMicros, timelineBlueId, entryBlueId)`. Every required source must cover that
   timestamp, strictly below its exclusive bound. A local fence cannot stand in for an
   external guarantee. A ready claim fixes the source cut, owner version and lease token.
   Native execution happens outside metadata publication. CAS publishes the resulting
   snapshot, gas, ordered emissions, receipt, source/lifetime deltas and notifications together.

Example: internal input T=100, external X complete-before=101 and Y complete-before=150.
Both already cover the candidate; neither needs refreshing. If X has bound 100, execution
waits for X. A fetched X entry at 99 enters the merge first. Equal-time entries use the
canonical identity tie-break, never arrival order or queue delivery order.

## Durable routing and recovery

Verified range acquisition stores operation classification and an optional exact target
alongside each immutable archive entry. Unknown information is conservative. Operation-only
receivers can skip verified ordinary events and unrelated explicit targets. Raw event
observers still receive ordinary events. Permission/control coverage dependencies contribute
a completeness bound without pretending to be native message receivers.

Older protected archives reconstruct the native operation marker and exact target selector
without admitting or reopening historical target bodies. Targetless requests remain conservative.
Verified empty checkpoint intervals are skipped through the archive index. The migration
position is durable and shared by subscribers. Restart or eviction of the
bounded verifier cache must not cause every newly created access document to execute old
unrelated originals. Genuine native checkpoints also advance a newly attached source cursor;
initial access grants without a checkpoint do not get an invented checkpoint.

`OwnerMetadata` stages immutable content before publishing one conditional account manifest.
Fanout larger than a DynamoDB transaction is therefore not partially visible. Lost responses
are reconciled using the retained claim/publication identity. Expired or stale workers cannot
overwrite a newer owner head. Errors retain the pending original and an inspectable diagnostic. Incompatible historical
attachments are isolated instead of throwing out of the account scheduling loop.
A child’s established nonparticipation advances its receiver cursor without scheduling a native
turn. This also handles classification arriving while that child was already claimed: a late
no-change result cannot reintroduce an original the parent has already processed.
All DynamoDB immutable page writes are acknowledged before the transaction that exposes their
metadata root. They are deliberately not combined: separate `GetItem` calls are not an atomic
snapshot during an in-flight transaction. Outbox publication also flushes attempt-local buffers.
See the [DynamoDB isolation rules](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html#transaction-isolation).
Polling discovery applies bounded backpressure instead of dropping page-tail registrations.
Fast-mode evidence is a protected reference to admitted source cuts and native receipts; it
is not a portable cryptographic proof to an untrusted third party.

## AWS services and limits

The deployed service uses the existing EC2/systemd workers, DynamoDB conditional writes and
immutable content pages, Secrets Manager-backed provider/reader custody, Cognito and
CloudFront. S3 holds versioned deployment artifacts. The original Lambda/API Gateway external test provider uses an older incompatible protocol
edition. The current-edition qualification provider uses a separate process and HTTPS endpoint;
its imports traverse signed HTTP acquisition and never use the owned-timeline shortcut. SQS could carry wake-up hints later, but queue order,
queue emptiness and eventually consistent index scans cannot prove completeness.

The account manifest currently serializes metadata publication. Independent computation is
possible, but throughput for one hot account is bounded by this publication boundary. Failed
CAS attempts can leave unreachable immutable pages; storage reclamation is separate work.
Established SCC merge/split and attachment of an old source behind an advanced receiver are
rejected explicitly where no finite historical migration is available. Initial cycles and
ordinary forward attachment/removal are supported. These limitations must not be presented
as complete arbitrary graph migration support.

## Verification strategy

- `OwnerFrontiersTest`: canonical ties, removal, source independence, retained history,
  lifetimes, claims, restart, lease fencing, lost responses and terminal behavior.
- `OwnerLocalAuthorityTest`: owned timeline enrollment, append/fence races and restart.
- `OwnerSourceCompletenessTest`: exclusive imported bounds, mixed local/external coverage,
  invalid ranges and atomic publication failures.
- `OwnerGraphsTest`: 1, 10 and 20 Orders compared with whole native execution after reopening;
  exact root identity, gas, ordered events and receipt; rejection followed by a valid input.
- `OwnerCycleTest`: pre-existing cyclic graphs, including four- and eight-member cases.
- `CanonicalClauseTutorialTest`: real Activation, Documents, Orders and Payment clauses,
  independent owners, shared participant timelines and unrelated historical access entries.
- `TimelineArchiveTest`: protected cold recovery, legacy routing reconstruction and immutable
  occurrence lookup. `CoreTimelineDiscoveryTest`: custom provider type and identity mismatch.
- `OwnerScaleProbe`: explicit isolated DynamoDB measurements with fresh attempt sessions and
  a whole-native oracle. `scripts/reproduce_canonical.py`: authenticated public API clause
  journey and end-to-end latency. Recorded root execution includes restore/admission I/O; it
  is not CPU-only. The probe uses fresh mutable sessions but bounded immutable caches warmed
  during initialization, so those figures do not establish cold-storage performance.

Run checks with Java 25:

```sh
./gradlew -PblueLibrary=../blue-java-better check
```

The AWS qualification is explicit and creates isolated test data. It provisions a fresh temporary
8 GiB EC2 worker and terminates it on exit. It refuses the live application host. An existing
dedicated worker can be selected with `--instance`; it must have at least 8 GiB and no active
MyOS service. The benchmark JVM has a 1 GiB heap, a 1.5 GiB cgroup memory limit and a two-CPU
quota. The live app currently runs on `m7i.large` (two vCPUs, 8 GiB).

```sh
./gradlew -PblueLibrary=../blue-java-better installDist qualificationJar
python3 scripts/qualify_owners.py --orders 1,128
```
