# Next performance iteration: remove work, preserve semantics

These are proposed changes, not features implemented by the polishing pass. They follow the final
profile and current source review. Do not introduce another gas policy or an authored execution type.

## 1. Make structural creation an explicit native delta

**Evidence:** an addition after a Payment still spends roughly 396 ms restoring the historical graph
at 1,001 Orders, despite preserving completed member preparation. `Repository.restore` calls
`restoreLocalGraph`; that boundary reconstructs metadata, verifies checkpoints and validates edges.
The host then prepares a complete structural result. Existing static owner-local processing avoids
this work, but refuses edits that change topology.

The native engine should issue a bounded topology result for a supported structural edit: changed
owner state, newly initialized members, edge additions/removals with generations, lifetime/channel
deltas, gas and ordered emissions. The host should persist that result through its existing selected
member/edge indexes. It must not infer a native transition from a changeset itself. The result has to
carry sufficient native evidence for another parent to consume the same child outcome independently.

Start with acyclic addition to a collection, then removal and replacement. Qualify shared children,
attachment lifetime cutoffs, already-processed child history, rollback and initialization emissions.
Use the current whole-document interpreter as a differential oracle for every step. Test arbitrary
clause behavior; do not special-case Order or Payment. Keep one shipping processing semantics.

Cycle handling needs an explicit extension: a new edge may require finding the affected SCC, and
merging established units requires atomic migration of their frontier/claim ownership. A logical
cycle that actually executes every member cannot have constant work in the number of affected
members. The target is work proportional to affected semantics and index paths, not graph-wide scans
of quiet siblings. Never silently choose a new stable representative and reset consumed positions.

## 2. Persist pure policy projections with immutable member metadata

The new process cache bounds document→policy identities to 4,096 entries/1 MiB. It removes repeated
work in the measured workload, but cannot be the foundation for cold processing across millions of
accounts or graphs larger than its capacity.

Consider retaining the exact Timeline Access policy identity/decoded requirements beside the existing
admission/preparation record for that member BlueId. Compute it only from a successfully admitted
native result. Reuse it only for that exact immutable member. The reader should use already-selected
metadata pages, avoiding a new full graph index or an extra read per quiet sibling. Missing older
records must use strict recovery; any backfill must be derived from the exact historical body.

This is only a pure content projection. Reader grants, revocation, account/lineage authorization and
source completeness remain input-specific checks. Test identical policies in different accounts,
changed policies, revoked grants, cache purges, older publications and malformed stored metadata.
Measure storage reads with every process cache cold, in addition to warm latency.

## 3. Stop rebuilding private whole-graph commitment material on the fast path

The final profile frequently reaches private canonical record encoding and Language normalization/
identity work. Existing encoded fragments and immutable record layouts already remove some repeated
work; another string-comparison tweak does not remove the outer graph reconstruction.

Prefer immutable per-owner checkpoint and topology records that the fast path can reference directly.
Materialize a complete portable inspection/proof artifact only when explicitly requested. Preserve
native event occurrence identities and logical gas. Owner-local and whole-graph private receipt hashes
already describe different physical boundaries; never relabel one as proof of the other.

Do not skip integrity checks merely because a caller supplies a digest string. Any reuse must be
bound to protected, immutable storage provenance and the exact runtime environment. Keep corruption,
wrong-predecessor, missing-edge, cross-account and revoked-scope tests. This change must make ownership
and lifetime simpler to audit, not add another private execution language.

## 4. Qualify the exact candidate on DynamoDB before choosing another AWS service

Deploy only after the current benchmark window is released. Pin host/library/qualification artifacts,
keep the fixture and instance class fixed, and separate initialization, first touch after process
restart, later operations and sequential child creation. Wait for the exact input's parent receipt;
a later arbitrary head is not proof of that input's business completion.

Measure signed admission, scheduler selection, restore, execution, result preparation, immutable
content reads/writes and conditional commit separately. Record DynamoDB request counts, bytes and
conditional retries alongside wall time. The current local host uses a serialized in-memory store;
its 100-child results are not a prediction of networked storage latency. Do not change infrastructure
merely to hide an unmeasured code/storage bottleneck.

## Acceptance gates

- Whole and independent processing agree on complete document identity, total/per-member semantic
  gas, status, rollback and ordered events/occurrences at every tested input.
- 10/1,000/10,000 quiet-child workloads show bounded changed-owner and storage-read counts for a
  leaf update. Structural creation visits newly affected owners and topology paths rather than all
  prior members; initialization of N new documents is still expected to do O(N) work.
- Repeat after process restart with no resident document sessions, and with immutable package data
  preloaded separately. Also report end-to-end service startup; do not mix its cost into operation data.
- Purge optional caches, vary worker counts, replay preloaded history, introduce delayed external
  timeline completeness and equal timestamps, and verify deterministic outcomes.
- Keep one Blue representation and current semantic gas policy. Any necessary canonical encoding
  change requires its own explicit compatibility analysis; hashing a large parent reference map or
  exporting a large response can still depend on its size even when quiet child bodies stay closed.
