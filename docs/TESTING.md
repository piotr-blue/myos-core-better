# Test strategy and ownership

The service executes canonical `Contracts` through `blue-java-better`. Independent owners are a
storage and scheduling boundary, not a second document language. A strongly connected component
is one execution unit. Published native transitions, gas and ordered events remain the oracle.

## Local gates

```bash
./gradlew check
python3 -m unittest discover -s scripts -p 'test_*.py'
(cd sdk/typescript && npm ci && npm run check)
(cd core-ui && npm ci && npm run typecheck && npm test && npm run build)
```

Use JDK 25 and the committed dependency locks. `check` compiles qualification tools, runs the Java
suite, enables compiler warnings as errors, and verifies Google Java Format. `./gradlew format`
updates formatting. The composite build needs the sibling library; Java tests do not read the
Python implementation. Java HTTP and SDK tests bind loopback sockets. Ordinary gates do not
contact AWS, create cloud resources or use production credentials. The SDK regeneration check
uses a checksum-pinned generator, downloading it only when its local cache is absent.

## Responsibility map

Tests are named after the component or invariant they own. Put new edge cases in the responsible
suite; add a new suite only for a distinct boundary. Performance thresholds do not belong in
ordinary correctness tests: use structural read counts, exact results and manual clocks instead.

| Boundary | Principal suites | Required guarantees |
| --- | --- | --- |
| Independent scheduling | `OwnerFrontiersTest`, `OwnerRoutingTest`, `OwnerLocalAuthorityTest`, `OwnerSourceCompletenessTest` | Total ordering, completeness, independent consumers, lease/generation fences and durable progress |
| Native composition | `OwnerGraphsTest`, `OwnerBirthParityTest`, `OwnerCycleTest`, `OwnerCycleBirthTest` | State, canonical identity, gas and ordered events agree with whole native execution; restart is safe |
| Published clauses | `CanonicalClauseTutorialTest`, `ClauseIntegrationTest`, `ClauseGrowthTest` | Pinned packages execute through real signed inputs; exact parent receipt and business state agree |
| Durable evidence | `CanonicalSnapshotsTest`, `CanonicalAdmissionsTest`, `CanonicalEvidenceTest`, `StoredLanguageRecordsTest` | Immutable predecessors, verified admission recovery, bounded changed-page writes and rejection of mismatched evidence |
| Target addressing | `GraphOperationAddressTest`, `GraphOperationCatalogueTest`, `DocumentLineageTest` | Historical lineage and current membership are checked; no cross-account reuse or loading unrelated historical bodies |
| Timeline ingress | `GraphTimelineIngressTest`, `TimelineIngressTest`, `TimelineArchiveTest`, `TimelineCoverageTest` | No skipped entry, incomplete prefix, forged provider content or duplicate accepted transition |
| Private timeline access | `TimelineReadAccessTest`, `TimelineAccessHistoryTest`, `PrivateTimelineIngressTest`, `ReaderCustodyTest` | Policy decoding may be reused by exact identity; historical grants and reader authorization are always evaluated at the input |
| DynamoDB boundaries | `DynamoTransactionTest`, `DynamoFailureTest`, `DynamoContentBatchTest`, `DynamoReadBatchTest`, `DynamoWorkPageTest` | Conditional publication, partial batches, retries, missing/corrupt content, complete cursors and tenant isolation |
| Indexed storage | `OrderedIndexTest`, `RadixIndexTest`, `OwnerMetadataTest`, `LinkIndexTest` | Stable immutable cuts and bounded selected reads rather than full enumeration |
| Wakeups and concurrency | `PropagationWakeupTest`, `PropagationFairnessTest`, `DocumentLocksTest`, `CoreConcurrencyTest` | No lost wakeup, starvation or stale publication; process locks do not replace durable fences |
| HTTP and console contracts | `Core*Test`, `Console*Test`, `ServerTest` | Account scoping, bootstrap, operations, history, timelines, API keys, invitations and response shapes |
| Operational tooling | `scripts/test_*.py` | Artifact pinning, deployment-window ownership, shell correctness and cleanup; mocked AWS calls only |

The library owns language semantics, BEX evaluation, Contracts rollback, semantic gas and native
transition conformance. This host tests persistence and integration of those results. Avoid
reimplementing the language oracle in host tests.

## Dynamic graph coverage and limits

A newly created child may close a cycle with the currently claimed unit. Its history is new, but
an existing SCC frontier must not be registered again or reset. `OwnerCycleBirthTest` checks this
with a create operation followed by event rounds, reopening the host between operations.
`ClauseGrowthTest` also exercises three such births using the published IncrementalDocuments
clause and compares the final graph with whole native replay.

Merging two established SCCs or changing their stable representative still requires an explicit
migration. The host refuses that case. These tests do not claim unrestricted dynamic topology
migration, remote-provider consensus, deterministic proof bytes across fetch schedules or full
compatibility with every historical gas schedule.

Preparation deltas declare new members explicitly. Hosts reject undeclared or duplicate births,
wrong predecessors and wrong environments. Detaching a member updates its active-lifetime row;
unchanged member rows remain shared. This is a physical storage optimization, not a gas change.
Static owner updates retain native preparation alongside their existing per-member admission rows.
This avoids an additional index and preserves preparation through child-to-parent propagation.
Graph fallback may reuse it only when every selected historical member matches its exact BlueId
and execution environment; older records without this information use strict ordinary recovery.
`CanonicalSnapshotsTest` owns these member/environment guards; the small published-clause growth
regression checks that a child update still leaves preparation available for the next birth.

## Measurements

Qualification source is under `src/qualification`; it is compiled by `check`, packaged separately
by `qualificationJar`, and never executed by a normal build. Live tools are explicit and require
an isolated account, artifact pinning, a bounded run and the deployment coordinator's window.

For local published-clause growth:

```bash
./gradlew clauseGrowth -PgrowthCount=100 --rerun-tasks
# Optional sampling; keep its results separate from unprofiled runs:
./gradlew clauseGrowth -PgrowthCount=100 -PgrowthProfile=true --rerun-tasks
```

The ordinary suite runs three acyclic and three cyclic births. Larger growth runs skip the cyclic
parameter explicitly: broadcasting around a growing SCC is a different workload from changing
one leaf. Results are written to `build/clause-growth-false.json` and (when run)
`build/clause-growth-true.json`. Each sample measures signed local API submission through the
exact Agreement receipt and records submission time separately. Operation discovery precedes
the measured interval. Storage is an in-memory implementation that serializes records across its
boundary; there is no HTTP, DynamoDB or network latency. Larger runs assert business state and
exact receipts; runs with at most ten children additionally perform full native replay.

Always state whether the JVM, packages, metadata caches and storage were warm. Reopening a
storage-backed owner is not the same as restarting the JVM. Report initialization, per-addition,
leaf processing, propagation and HTTP completion separately. Retain raw samples, include failures
and excluded samples with their reasons, and compare identical fixtures and boundaries. A fast
small local result does not establish production AWS latency or throughput.

## Inspect unexercised code

Run `./gradlew jacocoTestCoverageVerification` to instrument the ordinary JUnit suite in its own JVM. Open `build/reports/jacoco/test/html/index.html` to inspect missed branches. The module-wide regression floors are 86% line and 73% branch coverage. A branch exercised by a test still needs an assertion that distinguishes correct behavior. Ordinary tests and JMH run without this instrumentation.

## Check whether tests detect changes

Run `./gradlew mutationTest` to run the focused PIT suite. Open `build/reports/mutation/index.html` and inspect each surviving mutation in context. A surviving change can expose a missing assertion or behaviorally equivalent code. Investigate the affected invariant before changing a test.

Select another production boundary with `-PmutationClasses=CLASS_GLOB` and its tests with `-PmutationTests=TEST_GLOB`. Quote wildcard arguments in the shell. PIT reports the selected classes only. It runs in forked JVMs and stays outside the release artifact. [PIT command-line options](https://pitest.org/quickstart/commandline/)

## Recovery across process termination

Start a disposable local DynamoDB-compatible endpoint on loopback. The runner uses the AWS CLI with test credentials, creates a unique table with the deployment key schema, and removes the table when the run exits. Put JDK 25, the AWS CLI and Python 3 on PATH.

```bash
python3 scripts/test_process_recovery_local.py --endpoint http://127.0.0.1:4566 --trials 8 --output build/process-recovery.json
```

The supervisor kills a JVM immediately before its conditional financial command commit or after DynamoDB returns success and before the caller receives the reply. A fresh JVM reads the stored total, resubmits the same acknowledgment twice, and checks its receipt, amount and version. The two cases distinguish an interrupted operation from a lost response. This is a recovery check against an emulator. The timings of the emulator do not characterize AWS.

To race competing command bodies, run `./gradlew commandReplayProbe -PconflictingCommands=true -PfirstSeed=120000 -PseedCount=12 -PcommandsPerSeed=12`. Each host submits one of two amounts under the same command key. After reopening the store the probe checks the winning input, full response, ledger total, rejected body and version. A second account repeats the same keys with different amounts. Supply the same disposable loopback DynamoDB endpoint and table options as the identical-input probe to exercise independent SDK clients.

To exercise metered owner initialization, add `--scenario owner-init` to the process recovery command. The worker reserves gas and is killed at the conditional publication that creates the owner and settles that reservation. On restart the probe first attempts recovery while the original two-minute lease is live. After expiry it checks the single published event, version, gas debit, remaining reservation and equality with a fresh native receipt. Testing both commit boundaries requires at least two trials. Both JVMs load a temporary snapshot of the same run-time classpath.
