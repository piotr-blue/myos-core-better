# Three-project polishing and qualification — 10 November 2026

This report covers the two-hour source iteration on `blue-java-better`, `myos-simplified` and
`myos-aws-test-better` (Gradle project name `myos-core-better`). Work began at 05:51:25 UTC.
The changes were built in isolated copies before source promotion. No GitHub access, commits,
Python-implementation changes or package-publication changes were made.

**Result:** the candidate fixes dynamic cyclic child creation, retains lazy evidence correctly,
removes repeated admission and policy work, and preserves native preparation through independent
owner updates. In matched local measurements, adding an Order to a 1,000-Order graph falls from
1,288 ms to 673 ms median. Payment propagation remains about 36 ms. Structural creation is still
size-dependent. This is useful progress, not evidence that every project is already 9+/10.

**Current deployment:** the subsequently resumed candidate is deployed and health-checked; see
[the follow-up](FOLLOWUP.md) for its release identity and AWS acceptance results.

**Deployment during the original two-hour pass:** source qualification was separate from the live AWS benchmark. That campaign
owned the exclusive runtime window on the previous pinned release. This pass does not claim a new
AWS deployment or present local store measurements as DynamoDB/HTTP latency.

The user requested continued work afterward. See the [startup and deployment follow-up](FOLLOWUP.md)
for the newer candidate, repeated initialization measurements and subsequent acceptance status.

## What changed and why

1. **Cyclic child creation.** Creating a child that closes a cycle with an existing execution unit
   tried to register its frontier again and failed with `Owner exists`. New member history is now
   published without resetting an existing frontier, cursor or claim. The frontier's duplicate-owner
   guard remains intact. Minimal restart tests and the actual published Documents-clause example
   exercise creation and subsequent event rounds. Changing an established SCC representative or
   merging established units still requires explicit migration.
2. **Lazy Language evidence.** `Language.Builder.content` retained opened bodies but discarded deferred
   providers. It now retains their unverified records and providers without traversing the closure.
   Values crossing `read` or `admit` retain those providers too. Admission authority is not transferred;
   ordinary reads still perform semantic checks. Regression tests demonstrate the prior failure and
   successful cold resolution without loading unrelated content.
3. **Cold operation targets.** Removed a 128-document historical-target cache that lacked a byte bound
   and forced whole-closure admission on misses. The host recovers the exact historical member from
   its committed admission record. Account, lineage and current-membership checks remain fresh.
   Old publications without admission records retain strict recovery.
4. **Preparation deltas for topology edits.** Births explicitly declare added members. Attach/detach
   updates include the changed active-lifetime rows, while unchanged rows remain shared. Both hosts
   reject undeclared/duplicate births, mismatched bases and different environments. AWS updates only
   affected radix paths. SQLite still merges and serializes the full preparation map; this remains
   one structural scaling cost.
5. **Bounded policy projection.** Cache exact document identity→exact Timeline Access policy identity,
   separately from decoded policy values. Limits are 4,096 entries / 1 MiB for references and 128 entries / 1 MiB
   for decoded policies. No document, provider, grant state or authorization decision is cached here.
   Tests cover changed policies, malformed policies, cold reads, clearing, eviction and recency.
6. **Less immutable-cache allocation.** Re-establishing the same cached value/weight refreshes LRU
   order without replacing its entry. Distinct values and changed weights still replace normally.
   Tests cover weighted eviction, disabled/oversized entries and arithmetic bounds. No isolated
   wall-time improvement is attributed to this small allocation change.
7. **Protected structural recovery in the library and local host.** A parent updated through the
   owner-local path previously lost each member's completed preparation when reconstructing a full
   graph for a later structural edit. `Contracts.Execution.restoreLocalGraph` reuses exact protected
   member checkpoints while checking environment, hashes, root and actual attachment edges. It
   requires a revocable validation scope. It does not invent a persisted full-graph preparation base
   or turn host-owned records into portable proof. Ordinary portable restore remains strict.
8. **Cheaper local metadata loading.** Frozen attachment rows join their pinned child states; shared
   children decode once. Owners with no native edge generations need no empty attachment query.
   Native restoration occurs outside the serialized SQLite connection boundary. Immutable selected
   records are safe to compute from; the existing head-version fence still controls commit.
9. **AWS preparation continuity.** Native preparation is stored beside each existing per-member
   admission record and propagated with changed member records. No additional index or resident
   document-session cache was introduced. Structural recovery requires every selected historical
   row to match its member BlueId and environment. Older histories use the strict fallback.
10. **Build and documentation cleanup.** All Java projects have pinned formatting gates, strict
    compilation and reproducible archive settings. Fixed a strict Javadoc warning, updated contribution
    and test ownership guides, and restored two exact original MyOS SDK qualification support scripts.
    The single current semantic gas policy remains unchanged. No new `Core/*` authored type or
    alternative document language was added.

The [iteration log](ITERATIONS.md) records decisions and intermediate measurements. Changes are
retained only after their relevant gates pass. Generated databases, JFR recordings, build products
and dependency directories are excluded from source promotion.

## Matched local performance

The original source copy and final candidate ran sequentially on the same macOS ARM64 machine and
JDK 25. Each size has seven additions interleaved with seven Payments. Every operation reopens the
SQLite application; immutable packages and the JVM are shared. Each Order contains one Payment,
so 1,000 Orders mean 2,001 members including the Agreement. The timer starts immediately before the
operation call and ends after the affected parent commits. Opening the application, package loading,
fixture initialization, inspection and independent whole-native replay are outside that timer.
SQLite uses its normal durable configuration; OS file/page caches were not flushed.

| Existing Orders | Operation | Original median | Candidate median |
| ---: | --- | ---: | ---: |
| 10 | Add Order |51.029 ms|41.731 ms|
| 1,000 | Add Order |1,288.378 ms|672.563 ms|
| 10 | Payment→Order→Agreement |28.015 ms|30.313 ms|
| 1,000 | Payment→Order→Agreement |36.569 ms|36.076 ms|

The 1,000-Order addition median improves 47.8%. The small Payment differences are noise-level;
no Payment speedup is claimed. The first addition precedes an owner-local Payment update and is
faster than subsequent additions. All samples are retained rather than selecting the first or best.
These are bounded engineering measurements, not p95/p99 or production throughput guarantees.
See [raw original samples](growth-original.json), [raw candidate samples](growth-final.json),
[summary](growth-summary.json) and [measurement environment](measurement-environment.json).
The original report's `exactReceiptParity` label was incorrect: its helper compared semantic fields.
That label is preserved in raw historical evidence and corrected in the candidate tool.

The native fanout gate separately reopens a graph with 1/5/100/1,000 children. It executes exactly
one child and one parent, verifies complete root identity, gas and ordered events against uncached
whole execution, and records zero resident document sessions. Its single-sample clocks are diagnostic,
not a latency distribution. The 100,000-source test is **scheduler-only**, with placeholder snapshots;
it does not establish 100,000-document native execution performance.

## Published-clause host measurements

`ClauseGrowthTest` uses real pinned Agreements, Activation and IncrementalDocuments packages,
signed local Timeline inputs and the exact resulting Agreement receipt. It creates children through
the document's operation. The larger 100-child run uses a serialized in-memory conditional store,
not DynamoDB, HTTP or a resident execution-session cache. Operation discovery precedes timing.
Small three-child acyclic and cyclic variants also compare whole native replay. Larger timing runs
check business state and exact input/result correlation; they do not claim exhaustive whole replay.

Three unprofiled 100-child runs after policy projection had final 10-birth medians193.94/197.42/198.59 ms.
After retaining native preparation, the corresponding medians were 199.41/197.16/192.91 ms: ordinary
birth performance is similar. The specific birth after an earlier child update improves across the
three runs: child 26 median200.07→164.09 ms; child 51 261.70→179.54 ms; child 76 323.96→194.28 ms.
This isolates the prior loss of preparation during static child-to-parent propagation.

A separate sampled JFR run showed late-birth admission around 246 ms and post-execution preparation
around 253 ms before policy projection, versus roughly 3 ms and 24 ms afterward. Profiled runs are not
pooled with unprofiled distributions. [Repeated local clause samples](clause-samples.json) retain
both sets of unprofiled runs.

The documented [published Payment demo](published-payment-demo.json) also runs after this pass:
two Orders and two Payments reach an Agreement total of 400 after reopen. Its one-run initialization
is 764 ms; Order creation 168–304 ms; Payment preparation 298–387 ms; the remaining Payment steps
124–186 ms. This more involved authored clause workflow is separate from the minimal 36 ms Payment
fixture above. No cross-workload speedup is implied.

## Correctness: semantic results versus private receipts

Two old qualification tools wrongly required owner-local receipt hashes to equal whole-graph hashes.
Both failures were reproduced in the untouched baseline. The native outcomes agreed, but private
receipts commit different snapshot, work and completion boundaries. A parent consuming a published
child result is not the same physical proof record as executing the whole graph in one invocation.

The shared qualification helper now checks complete root BlueId, status, gas schedule, total and
per-member gas, changes, rounds, ordered events and occurrence identities, public events, rejection
and diagnostics. It rejects unknown execution-boundary values. For the same whole-graph boundary,
it additionally requires complete observations and exact receipt identity. Library same-boundary
cache/preparation tests remain strict. A fast host receipt is not advertised as a portable zero-trust
proof of the entire graph.

`rootedParity` passes 0/10/20 embedded documents with 1/4/8 workers: 186 inputs compared to independent
whole-native processing, plus restart checks requiring no repeated work. Rooted gas already includes
child work once. Summing that rooted total with the separate child invocation's gas would double-count
that work. The source state, semantic tariff and native event ordering are not changed by these fixes.

The additional [ring campaign](rings.json) uses eight four-member cycles feeding one ledger,
24 preloaded inputs and 48 atomic invocations per run. At 1/4/8 workers, backlog wall times were
1,330.5/711.9/724.3 ms, with observed concurrent native calls of 1/4/8. All three histories agree;
the first is also compared to whole-native replay. The [mixed-source campaign](mixed-offers.json)
uses four Offers, four external timelines, five internal timelines and two shared consumers.
Its three schedules finish in 540.3/276.5/266.4 ms and agree. External import is a signed local
simulation, not a remote cloud measurement. These single runs per worker count demonstrate
concurrency and repeatability, not a throughput guarantee.

Final Java test totals are 2,511 for the library, 77 ordinary + 15 clause + 7 scale tests for the
local host, and 855 for the AWS host. The SDK has 63 passing tests, its 16 generated files reproduce,
the console has 365 passing tests plus typecheck/build, and deployment tooling has 48 hermetic
passing tests. The optional 100-child host task explicitly skips its cyclic parameter; the ordinary
three-child cyclic case runs in the full suite. JARs and distributions reproduce byte for byte.

## Why structural creation still grows

Ordinary leaf updates restore the causal owners and reuse frozen child results. A topology edit can
change ownership, active lifetimes, subscriptions, initialization and cycles. The conservative native
fallback still reconstructs graph metadata and validates the complete attachment cut. The new path
reuses completed member admission, but it still visits those metadata rows. SQLite also serializes
merged preparation maps. Canonical hashing can read sibling reference values while updating a parent;
that is separate from restoring sibling document bodies.

A separate final diagnostic profile measured one post-Payment addition at 1,001 existing Orders:
707 ms end to end, comprising 396 ms restoration, 151 ms native execution and 74 ms preparation,
plus about 86 ms outside those phases. Its 349 execution samples repeatedly reach canonical
record encoding, normalization and identity calculation. Sampling stacks may be truncated, so
sample counts are not used as phase percentages. See [phase record](structural-profile-run.json)
and [sample summary](structural-profile.json). This profile is separate from the seven unprofiled
comparison samples. I did not remove commitment checks or claim that a small string-comparison
micro-optimization would fix graph-wide reconstruction.

Consequently, the current addition result is 673 ms at 1,000 Orders versus 42 ms at 10. It would be false
to call this constant-time growth. The next architectural step is a native, validated topology delta
that admits newly created members and updates affected edges/lifetimes without rebuilding the full
metadata cut, including explicit rules for established SCC migration. Removing checks or applying
host-authored patches to pretend a native result occurred is not an acceptable shortcut.

## AWS boundary during the original pass

During the original pass, the benchmark chat retained its exclusive window throughout source work. It continued on bundle
`ff18c6420e3671fed0b6b71eb1af5c09354714262e65f016131af81ee7020863` and the original library
`cf05f188e18c51a97e22f6f9b097916712c2b883cf35c4b98e913ab5c4e915df`.
No deployment, restart, live profiling or additional live load was performed by this pass.
Source-only promotion was coordinated separately and does not change the running service.

The service remains EC2/JDK 25 with DynamoDB state and conditional publication, S3 artifacts,
CloudFront, Cognito and the existing operational services. This pass does not add a cloud service.
The 1 MiB public source limit remains unchanged. Large growth is exercised through ordinary sequential
creation; a bulk expanded-source rejection is not counted as successful document initialization.
The live URL is unchanged: [canonical preview](https://d2buikczdox1rt.cloudfront.net).

## Verification and reproduction

See [verification totals](verification.json), [reproducible archives](archive-reproducibility.json),
[changed-source manifest](source-changes.json), and [rooted parity evidence](rooted-parity.json).
The source manifest identifies exact candidate hashes; it is not a Git commit or deployed release.

```bash
# Library: formatting, strict compiler/Javadoc, tests, benchmark compilation and archives.
cd ~/data/blue-java-better
./gradlew build

# Local host: ordinary, published-clause and explicit native/scheduler scale gates.
cd ~/data/myos-simplified
./gradlew build clausesTest scaleTest
./gradlew rootedParity
./gradlew orderGrowth -PqualificationOrders=10,1000 -PqualificationSamples=7

# AWS host: local release only. None of these commands deploys to AWS.
cd ~/data/myos-aws-test-better
./gradlew build qualificationJar installDist
python3 -m unittest discover -s scripts -p 'test_*.py'
(cd sdk/typescript && npm ci && npm run check)
(cd core-ui && npm ci && npm run typecheck && npm test && npm run build)
./gradlew clauseGrowth -PgrowthCount=100
```

Use JDK 25, the committed wrappers/locks and the pinned sibling package directory. The SDK generator
uses a checksum-pinned local JAR or downloads it when missing. Keep SDK and UI build commands
sequential because UI setup rebuilds the SDK output. Loopback HTTP tests require permission to bind
local ports. Fresh qualification output directories prevent accidental reuse of previous stores.

## Assessment and next gates

These are engineering judgments about the present evidence, not independent auditor scores.
The [next performance design](PERFORMANCE-NEXT.md) identifies the concrete follow-up boundaries and acceptance gates.

| Category | Blue library | Local host | AWS host |
| --- | ---: | ---: | ---: |
| API clarity and ownership |9|8.5|8.5|
| Test organization and reproducibility |9|9|9|
| Qualified semantic correctness |8.5|8.5|8.5|
| Processing efficiency across the requested workloads |8.5|8|7|
| Release/operational readiness |8|8|6.5|

I would not rate all three 9+ overall. The remaining gates are concrete:

1. Deploy the exact candidate only after the live benchmark releases its window, then run paired
   DynamoDB/HTTP workloads with the same fixture, artifact pinning, exact result checks and phase costs.
2. Replace graph-wide structural recovery with validated native topology deltas. Retain static
   changed-owner locality, event identities, gas, rollback and historical-cut guarantees.
3. Qualify established cyclic-unit migration, failure/lease races and scale under concurrent tenants.
4. Finish the explicit MyOS API compatibility and production HA/operations gaps; choose release
   coordinates/license before calling the library publicly publishable.

The current source is substantially better tested and faster on structural recovery. It remains an
experimental candidate with named limits, rather than a renamed production release.
