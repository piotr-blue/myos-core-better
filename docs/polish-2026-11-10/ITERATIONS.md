# Two-hour local polish — 10 November 2026

Started 05:51:25 UTC (12:51:25 Bangkok); target end 07:51:25 UTC.
AWS benchmark chat holds the exclusive live test window. All work takes place in isolated copies; no deployment/restart/load or actual distribution changes while that window is held.

## Iteration 1 — dynamic cycle birth (keep)

A new cyclic child was incorrectly classified as a new execution unit. The host attempted to register the already-claimed root frontier and threw `Owner exists`. A minimal regression reproduced that error before the fix. Registration now occurs only if the SCC frontier is absent. New member history is still published atomically with completion of the existing claim; existing cursors and fencing remain intact.

Validation: minimal create + two pulse rounds, restart between operations, exact whole-native state/gas/events; existing 4/8-member cyclic and acyclic nested birth tests; matching local SQLite test. Actual sanitized published IncrementalDocuments fixture also passes three births interleaved with pulse rounds and whole replay.

## Iteration 2 — cold operation target admission (keep)

Removed GraphDocuments' count-bounded cache of 128 historical LanguageDocument targets. It had no retained-byte bound and forced full closure validation on a miss. Recover the already-validated exact historical member from its committed admission checkpoint, retaining fresh account/lineage checks. Old histories without checkpoints retain the strict fallback.

This exposed a library bug: Language.Builder.content retained loaded bodies but dropped deferred providers. It now retains lazy providers and unverified records, without fetching their closure or transferring semantic admission. A regression failed before the fix. Protected-target test rejects any attempted fetch of unrelated history while construction and explicit completed-value recognition succeed through fresh host instances.

## Iteration 3 — native preparation deltas for births (keep)

A topology edit previously disabled all native preparation deltas. Added explicit `added` member declarations and inclusion of changed active-reachability members. Existing unaffected rows are retained. Hosts reject undeclared/duplicate births, wrong environments and wrong predecessors. The AWS radix update reads only affected index paths. Local storage currently still reconstructs/serializes the merged preparation map; this remains a measurable limitation.

Validation: births and detachments at 8/128 children match complete exports exactly; subsequent cold replay matches full native observations. Existing 10/1000-member update delta tests pass. Published clause creation/cycles pass.

## Initial local measurement

OrderGrowth reopened the SQLite application before every operation; packages stayed shared. 10/100/1000 existing Orders, two additions and Payment calls per size, whole semantic-outcome comparison outside timing. Unprofiled addition samples: 87.224 / 69.641 / 378.596 ms. Unprofiled Payment samples: 40.089 / 36.889 / 36.840 ms. The second sample at each size had JFR enabled for addition and is not pooled with unprofiled values. This small run locates the scaling path; it is not a robust latency distribution or an AWS measurement.

## Iteration 4 — pure timeline policy projection (keep)

The host repeatedly opened every member body to select the same immutable Timeline read-access
policy. A bounded document-identity → policy-identity index now avoids that projection. It retains
no document, provider, grant state or authorization decision; private historical access still runs
at each input. The existing decoded policy LRU remains separately bounded. Tests cover cold provider
reads, reuse, clearing, changed policies and grants. JFR at 100 children showed admission ~246 ms and
post-execution preparation ~253 ms before, falling to ~3 ms and 24 ms respectively in the sampled late
birth after the change. Whole local birth profiles are separate from unprofiled measurements.

Three unprofiled 100-child local signed-API repeats after this change (and the bounded-cache update):
first10 birth medians164.76/169.65/164.29 ms; last10 medians193.94/197.42/198.59 ms. Increment medians
139.53/138.64/122.50 ms. Real published clauses, exact parent receipts, no HTTP or AWS. Full replay
is checked by the small three-child variants, not by the 100-child performance runs.

## Iteration 5 — bounded cache allocation (keep)

JFR identified repeated re-establishment of the same immutable closure/validation facts in
ContractCache.put. An identical value/weight now refreshes LRU order without replacing its entry.
Distinct equal objects and changed weights still replace correctly. Four tests cover retention,
eviction, disabled caches, oversized inputs and long-capacity overflow. No standalone wall-clock
speedup is attributed to this small change.

## Iteration 6 — recover preparation after local owner updates (keep)

A repeated SQLite growth run disproved the earlier hypothesis that the second slow sample was
primarily JFR overhead: unprofiled additions after Payment updates still restored for ~900 ms at 1000.
Local owner checkpoints were materialized into a full graph without retaining each member's completed
native preparation. The new protected structural-recovery API carries that preparation, verifies the
frozen attachment edges, and does not pretend that a full-graph preparation base was persisted.
Ordinary portable restore remains strict. Missing roots/edges and changed checkpoint hashes fail.

Seven samples with reopen-before-each-operation and exact whole-root semantic outcome comparison:
addOrder median at 10/1000 fell from 49.265/1251.813 ms to 42.657/714.858 ms. Payment medians remained
27.070/33.675 ms versus28.970/33.979 ms. The improvement is real; structural metadata work is still
linear and initialization is a different boundary.

The AWS host now retains native preparation alongside the existing per-member admission records,
so static updates propagate it with already-published changed member records. This adds no separate
index or per-document session cache. Full restoration can reconstruct a complete preparation only
when every exact pinned member has a matching row and environment. Older histories without it use
the strict fallback. Initial focused native parity and published-clause tests pass; added corruption
and missing-legacy-data guards plus repeated measurements are in progress.

## Build and documentation hygiene (keep)

All three Java projects have consistent Google Java Format / check gates, pinned tool versions and
strict compilation. The local application previously lacked the formatting gate. Updated current
contribution and test-ownership docs; historical reports are not rewritten. The SDK test copy lacked
two original MyOS qualification helpers; restored those exact support scripts. SDK 63 tests pass,
its16 generated files reproduce from the checksum-pinned generator, console typecheck/build passes.
The isolated copy initially omitted docs/api input used by one console suite; restored that existing
file (not a product defect), and the eight affected Explorer tests pass. Final aggregate runs follow.

## Iteration 7 — exact structural recovery and test claims (keep)

Joined frozen attachment rows to their child checkpoints and decoded a shared child only once.
The seven-sample SQLite medians at 10/1000 are now41.639/684.046 ms for addOrder and 29.669/32.648 ms
for Payment through Agreement. Native restoration was also moved outside the serialized SQLite
connection boundary: immutable selected records are loaded first, computation follows, and the
existing head-version fence still controls publication.

The scale gate exposed a pre-existing incorrect assertion: NativeFanoutTest compared an owner-local
receipt hash with an ordinary whole-graph receipt hash. All four cases also fail in the unchanged
baseline. Inspection showed identical semantic fields but different input/output snapshot commitments,
execution boundary, work and completion records. The test now asserts complete root BlueId, status,
gas schedule, total/per-member gas, changes, ordered event values/occurrences, public events and
rejection diagnostics. It explicitly identifies the owner-local boundary. OrderGrowth already compared
semantic results, but labelled the result exactReceiptParity; corrected that misleading label.
Private storage receipts are not portable whole-execution proofs. Existing same-boundary receipt
comparisons remain intact.

Final local build, ordinary 77, clauses 15 and scale 7 tests pass, including native1/5/100/1000 and
scheduler-only100000. A strict library build exposed a missing Javadoc return tag in GasSchedule;
fixed the documentation, with 2511 library tests passing. The recovery API now explicitly requires
its documented revocable validation scope before accepting protected owner metadata.

## Iteration 8 — AWS-host preparation continuity (keep)

Three unprofiled100-child published-clause repeats after native preparation retention have final10
birth medians199.41/197.16/192.91 ms and operation medians126.32/127.92/121.60 ms. Ordinary creation
is similar to the policy-cache improvement. The specific birth after a child update improves:
child 26 median200.07→164.09 ms; child 51 261.70→179.54 ms; child 76 323.96→194.28 ms. This isolates the
previous loss of member preparation after static propagation. These are local signed-API tests with
serialized in-memory storage, not DynamoDB or public HTTP timings.

Complete AWS-host Java build/qualification JAR/distribution passed locally; console typecheck,
365 tests and production build passed. SDK 63, generated16-file consistency and 48deployment-tool
tests passed earlier in the pass. Final cache-bound guard checks and reproducible archive checks
follow. AWS benchmark remains pinned to the earlier release; no new deployment is claimed.

## Iteration 9 — bound cache retention and remove empty leaf queries (keep)

The document-to-policy projection index has an eviction/recency regression using4200 distinct
provider-backed documents. The first probe used documents with no policy, whose retained absence
facts can answer without provider reads even after eviction; replaced that inadequate observation
with actual collapsed policy lookups. The corrected test proves cold identity eviction and hot
identity reuse. It passes. Limits remain4096 entries/1 MiB for identity references and 128 entries/1 MiB
for decoded policies, with no authorization decisions retained.

Local graph recovery now skips attachment SQL for owners whose validated native edge-generation
map is empty. Any installed native embedding must have such a generation, so these owners have
never had an attachment. This removes empty queries for leaf Payments while preserving every
historical attachment lookup for actual containers. Full local and repeated qualification gates
are being rerun before deciding whether to retain this change.

The leaf-query optimization passes all local gates. The separate rootedParity campaign exposed the
same obsolete receipt-boundary assertion as NativeFanout; unchanged baseline also fails immediately.
Qualification now shares one NativeOracle.compareOutcome: compare all observations and receipt bytes
when both use the whole-graph boundary; compare complete semantic outcomes and native event occurrence
identities when the actual record is owner-local. Unknown execution-boundary values fail. This preserves
strict same-boundary receipt tests and removes the false cross-boundary proof-identity claim.
RootedParity now passes0/10/20embedded documents with 1/4/8 workers, replaying every input independently
and reopening after each run without repeated work (186compared inputs total). Whole native output,
gas and ordered event occurrences agree in all runs. Rooted gas includes descendant work once; summing
that rooted total with the separately recorded child invocation gas would double-count shared work.

## Final matched baseline measurement

Ran the unchanged source copy and final candidate sequentially on the same machine, JDK 25,
7 samples at 10 and 1000existing Orders, reopening SQLite before every operation and comparing whole
native results outside the timed interval. Median addOrder:10orders51.029→41.731 ms;
1000orders1288.378→672.563 ms (47.8%reduction). Median Payment→Order→Agreement:
10orders28.015→30.313 ms;1000orders36.569→36.076 ms. Small Payment differences are noise-level;
no Payment speedup is claimed. Raw samples are retained in comparison-original/report.json and
comparison-final/report.json. Original labels incorrectly said receipt parity; the unchanged helper
actually checked semantic outcomes. The final helper accurately distinguishes both boundaries.
The first add occurs before an owner-local Payment update and is faster than subsequent structural
recoveries; all 7 samples are reported rather than selecting the best one.

## Iteration 10 — repeatable connected and external-source qualification (keep)

Eight4-member cyclic settlement groups feed one ledger,24 preloaded original entries,48 expected
atomic invocations per run. Three runs with 1/4/8 workers pass identical histories, final roots and
ordered emitted occurrences; the first independently replays the whole native graph. A4-Offer
mixed internal/external-source campaign also passes three schedules and the whole-native oracle.
Both reopen without repeated work. Timing reports retain asynchronous overlap, storage and phase
metrics separately. Qualification report wording now matches the shared semantic/receipt comparator.
No external cloud provider was contacted: the external side is a signed local acquisition simulation.

## Final profiling decision

A bounded final profile after a Payment measured the next1,001-Order addition at 707 ms:
restore396 ms, native execution151 ms, preparation74 ms, remainder~86 ms. There are 349 execution
samples; canonical record encoding, normalization and identity computation remain frequent.
Do not turn sample counts into wall-time percentages because stacks are truncated and sampling
excludes blocked work. Keep the verified recovery improvements; do not remove checkpoint/edge
checks or introduce an unbounded document cache to hide the remaining structural cost. The next
substantial change needs native topology deltas, not another semantic gas version.

All release archives reproduced byte for byte. Library documented examples and the local actual
Activation/Orders/Payment demo pass; Agreement paidTotal400 after reopening. Demo initialization
~764 ms, creation168–304 ms, Payment preparation298–387 ms and remaining steps124–186 ms. This more
complex clause workflow is reported separately from the minimal36 ms Payment scenario.

## Iteration 11 — documentation links and release honesty (keep)

A post-promotion link audit found four broken links in the reused SDK README. Removed inherited
claims about absent publishing/Verify workflows, a missing CLI demo and PostgreSQL-specific host
behavior. The guide now points at this host's preview, adapted API contract and test strategy,
uses the repository's verified JDK 25 toolchain, and distinguishes account API keys from User
session context. Local npm pack --dry-run succeeds without publication. This is documentation
cleanup only; generated API sources and the passing SDK/console artifacts are unchanged.

## 12. Continued startup locality — retained

After the user resumed, removed eager whole-snapshot wire materialization from identity calculation,
reused unchanged topology for activation bookkeeping, and deferred frozen owner-alias construction.
The complete library gate passes 2,513 tests; three matched runs improve 1,000-Order startup from
12.70s to 8.74s, with unchanged static Payment latency and semantic results. Both failed/timeout
10,000-Order attempts remain explicitly reported. See FOLLOWUP.md for methods and deployment status.

## 13. Final candidate deployment and bounded HTTPS acceptance — retained

Deployed 66d9d21516b6 after both hosts passed, preserving current infrastructure/custody/runtime.
Minimal Payment propagation passes at 10/100 Orders; published two-Order Payment journey passes;
the previously failing cyclic birth plus five rounds passes; ten sequential counter births and
six targeted updates pass. Exact-entry receipts, historical immutability, API-key revocation and
tenant isolation are checked. Full client latency remains about 1.54s for the minimal fixture,
despite root execution around 26ms; no fresh 1,000-child AWS speedup is claimed.

Generated reports/class files/profiler artifacts are excluded from publication. Two stray probe
classes were retained under ignored build/cleanup. Final archive rebuilds are byte-identical.
