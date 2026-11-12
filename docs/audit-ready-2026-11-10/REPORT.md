# Blue / MyOS audit candidate — 10 November 2026

## Purpose and conclusion

This delivery qualifies the current native Blue library, the local persisted reference host and the DynamoDB application together. It includes their source, reproducible test commands, complete retained test-case results, per-input AWS measurements, exact comparison records and a change ledger. The application checkout is `myos-aws-test-better`; its Gradle distribution and intended product name are `myos-core-better`.

The work targets the next steps identified in `evidence/baseline/REPORT.md`: reduce selected-owner reads, remove eager unrelated-subscriber work, share verified external completeness, and qualify recovery and compatibility. It does not change the authored language, introduce `Core/*` document types, or silently change the gas edition of existing histories.

This is an audit candidate with a deliberately bounded claim. Static acyclic Payment → Order → Agreement updates can execute only their three affected owners and reproduce whole-document state, semantic gas and ordered public event occurrences. The local implementation demonstrates millisecond-scale persisted processing. DynamoDB still has substantial sequential storage overhead; the measurements below must not be described as local-runtime latency or a production SLO. General clauses, structural changes and cycles remain supported through the ordinary native path where required, with different performance characteristics.

## What changed in this iteration

### Indexed delivery

Previously an imported entry could make the scheduler visit every consumer of the timeline, including unrelated Orders. Verified operation messages now select consumers through a historical target-identity index plus broad observers. The target index includes prior document identities, so a request need not address only the latest state.

Unknown classification and unresolved operation targets retain conservative delivery. Broadcast occurrences reach observers. An index match only selects candidates: native timeline admission, operation selection and historical permission checks still run. The optimization does not grant authority from a BlueId or from the mere availability of content.

Unrelated subscribers no longer need their source cursors rewritten for each targeted message. When a later relevant input is selected, historical reception rules still decide what that owner consumes. Tests assert both the selected effects and that unrelated subscriber records stay unchanged across reopening.

### Shared completeness

An external provider's verified exclusive completeness bound is now shared. Advancing that bound does not rewrite coverage on every subscriber and ancestor. Each owner has a deduplicated dependency set; binding reference counts preserve the dependency when one of several occurrences is removed. A local-authority marker represents internal timelines.

The account-wide minimum external bound is used only as a sufficient positive certificate. If it is above the candidate timestamp, all external sources are complete for that candidate. If an unrelated provider lags, the engine checks the selected owner's own dependencies instead. Unrelated lag therefore does not become a global processing barrier. That fallback still costs O(number of distinct applicable external timelines); the report does not claim it has eliminated all possible wide dependency checks.

Topology and authority changes update the dependency sets. Existing metadata gets a one-time atomic index backfill. This migration may visit old consumers once; it is distinct from steady-state per-entry processing.

### Selected metadata reads

`OrderedIndex.getAll` batches only the index paths needed by a known set of keys. `OwnerMetadata` prefetches selected paths and their bounded record bodies. Records of 1–64 KiB can use one authenticated content page, avoiding a separate chunk-manifest lookup; larger values retain the chunked representation. Existing encodings remain readable by the new code.

This removes some avoidable storage round trips without reading unrelated execution bodies. It does not combine the entire processing lifecycle into one DynamoDB request. There remain admission, immutable-content, claim, execution and conditional-publication phases.

The new direct-page value encoding is not understood by older executables. After new writes, rolling back to the old binary alone is unsupported. A rollback needs a compatible reader or a coordinated storage snapshot restore. This boundary is explicit in `COMPATIBILITY.md`.

### Large timeline registration and publication

The separate-provider qualification found two distinct 100-item DynamoDB limits that the shared-timeline fixture could not expose.

First, static timeline alias binding attempted to register more than 99 aliases and a namespace guard in one transaction. Binding now uses pages of at most 99 aliases plus the same immutable namespace guard. Startup completes binding before the worker begins. Reopening and resuming partial binding are idempotent; incompatible namespace configuration is rejected.

Second, creating a document with 100 distinct timeline subscriptions attempted to publish all reverse subscription rows with the document in one transaction. Reverse pointers are now immutable candidate indexes prepared in bounded batches. The receiver record remains the authoritative membership set and is committed with the document. Fan-out checks that record before waking any receiver. An interrupted preparation leaves inert pointers, and publication schedules catch-up so arrivals during preparation are not lost. Removal changes authoritative membership; reattachment can reuse the historical pointer.

This design trades extra historical index rows for bounded atomic publication. Historical pointers can add read work under repeated subscription churn; an eventual safe compaction policy remains future work. They are not execution state and cannot resurrect a removed subscription. Tests cover abandoned preparation, restart, removal and reattachment with 201 sources.

### Kept and rejected experiments

| Iteration | Decision | Evidence / reason |
| --- | --- | --- |
| Target/observer routing and shared completeness | Keep | Tests cover conservative fallback, historical targets, removal and unrelated lag; AWS acquisition no longer grows like the previous consumer scan in the measured shared-source case. |
| Batched selected index paths and direct small record pages | Keep | Persistent-index, tenant-isolation and stale-publication tests pass; source changes stay local and bounded. |
| Redundant per-owner hot read packs | Reject | Correctness tests passed, but AWS processing remained roughly 0.6–0.7 seconds and the additional records complicated publication. Source experiment and raw measurements are retained under `evidence/rejected-read-pack`. These packs are absent from the delivered code. |
| Static binding pages | Keep | The 100-source AWS experiment found the limit; 101-alias regression covers reopening and incompatible configuration. |
| Prepared immutable subscription pointers | Keep | The later 100-source creation experiment exposed the separate publication limit. Receiver authority preserves atomic visible membership; interruption/removal regressions and the final AWS rerun qualify it. |
| Console packaging repair | Keep | One required authored input was accidentally omitted from staging. Restoring it made the existing source/asset manifest verification pass; no verification was bypassed and no UI redesign was made. |

The library and local-host production sources were not changed in this host-optimization iteration. Their full suites and persisted benchmarks were rerun against the delivered snapshot. `CHANGES.json` identifies changed application paths and their before/after hashes; `CHANGES.patch` shows the diff.

## Processing model and why sibling execution is unnecessary

Consider an Agreement that embeds Orders, each embedding a Payment. The host stores independent owner histories and immutable content. An embedded value in an authored Blue document remains content-addressed Blue state; a storage alias or owner address is not substituted into that authored value.

1. A local timeline append is accepted under the internal authority fence, or an external signed prefix is imported with verified completeness and access evidence.
2. The scheduler records obligations and selects the next original entry in Coordination order: timestamp, timeline identity and entry identity. Equal timestamps on different timelines are valid. Processing at T requires exclusive completeness above T on every applicable source.
3. The selected Payment restores its own protected native checkpoint. Unchanged sibling Payments and Orders remain references.
4. The native interpreter admits and executes the operation. Semantic gas follows the stored policy. The owner publishes its new state, emitted occurrences, receipt and progress through a conditional durable cut.
5. The Order selects that historical Payment completion. It attaches the prior/current state references, applicable events and causal metadata delta, runs its own handlers and publishes its result.
6. The Agreement consumes that Order result in the same way. An unrelated Order's execution state is not restored merely because it is embedded next to the changed Order.
7. Immutable pages become durable before a conditional manifest/head publication points to them. A stale claim or competing publication cannot expose half of an owner transition.

An independent child can progress while a parent is behind. A parent must consume the historical result selected for its own frontier, not the child's latest state. The same child result can serve several parents. Removing an embedding can suppress delivery to that parent without cancelling the child's independent history.

Cycles are not forced through an acyclic scalar shortcut. Native strongly connected owning units execute together. Ordering and whole-native parity tests cover cycles with four/eight members and chords, as well as local graph-shape cases. Those tests establish semantics; they do not establish the scalar fast-path latency for arbitrary cyclic workloads.

Initial creation visits and initializes the initial graph. Full dashboard expansion and complete export also naturally touch more data for a larger graph. Neither is hidden inside the operation-only timing claim.

## Gas and the comparison boundary

The measured owner-local path explicitly selects `ADDITIVE_PROTOTYPE`, persisted as `contracts-additive-prototype-3`. The live default remains `COMPOSITIONAL_V1`. A document's saved accounting edition is retained across restart and idempotent creation retries; this iteration does not silently reinterpret existing histories.

The intended law charges semantic operations independently of physical expansion, collapse, caching and result reuse. An owner's inclusive total includes the applicable child's semantic cost once; it is not legitimate to sum every already-inclusive ancestor total. For example, in the local fixture Payment costs 59, Order's inclusive cost is 182 and Agreement's inclusive cost is 273. The own contributions are 59 + (182 − 59) + (273 − 182) = 273. Counting 59 + 182 + 273 would double-count child work.

The parity assertion is exact canonical BlueId, gas and ordered public event occurrences for the same input history under the same schedule. Physical host receipts may differ between whole execution and decomposed execution. Different externally obtained checkpoints may also lead to different physical evidence. This is a fast evidence mode, not a claim of unique deterministic proof bytes across independent providers.

The oracle uses independent storage and does not reuse the measured host's protected source results. It uses the same native Blue interpreter, however. It tests partitioning, delivery, persistence and accounting composition; it is not an independent implementation of every language rule. Compatibility with the original `blue-java` gas under a different edition is not claimed. The new tariff still requires specification/auditor acceptance.

Resource protection remains separate: semantic gas limits, checked arithmetic and rollback tests coexist with bounded host retry attempts, memory, time and storage limits. A failed physical execution attempt is not invented business gas. The delivered library contains the tariff specification and its overflow, budget, rollback and owner-composition tests.

## Test strategy and evidence boundaries

`TEST-STRATEGY.md` maps each invariant to its suite. `TEST-RESULTS.json` lists every retained case, class, duration and verdict. JUnit XML retains the same outcomes; large console payload dumps are removed from XML, not test outcomes. Build logs document executed versus up-to-date tasks.

The local SQLite benchmark runs twice in fresh directories/JVMs, with storage reopened for every input and no resident document sessions. Common immutable package definitions and the JVM are reused within a run. The first sample is retained. The measured drain excludes the independent whole-root oracle; append-through-commit is also reported. Its physical counters were disabled, so zero counter fields do not mean zero SQL work.

The dedicated AWS probe uses real DynamoDB on separate temporary c7i.xlarge workers in eu-west-1, Corretto 25, two active processors, 1 GiB heap and a 1.5 GiB service memory limit. Transport caches are disabled and the owner processor/storage reader is reopened for every input. This is storage-cold execution with a warm JVM/common types, not a fresh operating-system process for every operation.

The probe's signed external timelines are synthetic sources produced on the worker. Acquisition includes archive verification and durable host updates, but not an independent provider's internet response time. The shared-source, rotating-target and separate-source cases test distinct behaviors. Quiet external sources certify a future empty prefix before the measured active inputs; that setup time is retained separately.

Every successful measured AWS input must commit exactly three owners and use `owner-local`. A fallback is an error for this fixture. The fixed-target fixture emits internal child events but no final public root events. The rotating and separate-source variants deliberately emit two equal-valued root events and assert both distinct ordered occurrences, including identity and ordinal fields. The full expected/actual comparison records are retained.

Fixtures use fresh source identities and signing keys. Equality is exact within each replay. Literal BlueIds are not expected to match across independent runs that author different timeline identities. Small sample sets and nearest-rank p95 values are qualification statistics, not a statistically established production percentile.

The authenticated HTTPS test covers the actual deployed private MyOS timeline path, checks the exact submitted entry in committed Agreement history, verifies all three totals and retries the same idempotency key. It is a different fixture from the signed public-source probe and has a different gas total. The restart test restarts the actual service and then calls an already-existing graph; it does not hide the cold operation behind reinitialization.

## Measurements and deployment

### Final automated results

| Layer | Cases | Failures / errors / skips |
| --- | ---: | --- |
| blue-java-better-test | 2,536 | 0 / 0 / 0 |
| myos-aws-test-better-test | 841 | 0 / 0 / 0 |
| myos-simplified-clausesTest | 15 | 0 / 0 / 0 |
| myos-simplified-scaleTest | 7 | 0 / 0 / 0 |
| myos-simplified-test | 92 | 0 / 0 / 0 |

**Total: 3,491 passing automated cases.** Strict library compilation/Javadoc and application formatting/build checks passed. Two additional persisted SQLite runs passed all 30 whole/split comparisons. The final AWS artifact passed 50 whole/split comparisons (30 fixed target, 10 rotating, 10 separate timeline). Each measured native AWS transition applied exactly three owners. These 80 explicit benchmark comparisons are separate from the automated-case count.

The clean delivery layout was also rebuilt: application classes, test classes, qualification classes, installed distribution and formatting passed; local application/test classes compiled. Some compilation outputs came from Gradle’s content cache. This packaging check did not execute an additional suite, and is not added to the case count. The console’s exact authored-input and asset manifests verified.

### Local persisted processing — two fresh runs

| Run | Orders | Initial creation ms | Median drain ms | Median append-through-commit ms | Drain min–max ms |
| --- | ---: | ---: | ---: | ---: | --- |
| A | 10 | 228.09 | 32.83 | 37.65 | 29.03–53.25 |
| A | 100 | 620.72 | 23.57 | 26.63 | 22.61–26.29 |
| A | 1,000 | 12482.11 | 31.29 | 34.94 | 28.68–32.02 |
| B | 10 | 224.65 | 30.48 | 35.26 | 27.92–52.78 |
| B | 100 | 621.94 | 24.08 | 27.37 | 23.24–26.48 |
| B | 1,000 | 12177.67 | 30.32 | 33.56 | 27.88–32.06 |

Five samples per size in each run; none discarded. Inclusive Agreement gas is 273. The first sample and runtime opening costs are retained separately in the raw files. These are real SQLite measurements, not an in-memory storage model. They do not predict remote DynamoDB latency.

### Final AWS fixed-target qualification

| Orders | Independent documents | Creation ms | Processing median ms | Acquisition median ms | Combined median ms | Processing p95 / max ms |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 10 | 21 | 1447.86 | 853.57 | 166.75 | 1020.86 | 987.36 / 987.36 |
| 100 | 201 | 2848.36 | 800.25 | 201.91 | 1016.48 | 880.06 / 880.06 |
| 1,000 | 2,001 | 37940.80 | 824.59 | 196.61 | 1020.35 | 884.96 / 884.96 |

Ten samples per size. Gas is 228 for every input. The combined value is the median of per-sample sums, not the sum of the two displayed medians. With only ten samples nearest-rank p95 is the maximum. The measured combined median is approximately 1.02 seconds at every size. This is not a mathematical constant-time claim: selected index paths, history depth, document width and network behavior can still vary.

| Orders | Previous acquisition median ms | Final acquisition median ms | Previous combined median ms | Final combined median ms |
| ---: | ---: | ---: | ---: | ---: |
| 10 | 179 | 166.75 | 837 | 1020.86 |
| 100 | 305 | 201.91 | 873 | 1016.48 |
| 1,000 | 1,823 | 196.61 | 2,478 | 1020.35 |

The previous report measured five samples per size; this final run measured ten and used a new temporary worker. The three final scenarios ran concurrently on separate workers, with separate namespaces in the same DynamoDB table. These are before/after observations, not a controlled isolated microbenchmark. At 1,000 Orders measured acquisition fell from about 1.82 seconds to 0.20 seconds; combined median fell from 2.48 seconds to 1.02 seconds. The final fixed-target processing itself is slower than the previous 661 ms median (now about 825 ms), and the 10/100-Order combined medians are also worse. No overall per-owner storage-speed improvement is claimed from those figures. The important demonstrated change is removal of the previous measured subscriber-count growth in acquisition.

### Other final AWS workloads

| Scenario | Orders / external timelines | Creation ms | Quiet-prefix setup ms | Processing median ms | Acquisition median ms | Combined median ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| aws-rotating | 10 / 1 | 1300.87 | 0.01 | 661.15 | 138.04 | 799.19 |
| aws-rotating | 1,000 / 1 | 36423.13 | 0.00 | 746.98 | 172.92 | 914.20 |
| aws-separate | 10 / 10 | 1173.46 | 1363.27 | 696.18 | 156.89 | 853.07 |
| aws-separate | 100 / 100 | 3207.19 | 8057.68 | 705.41 | 169.61 | 875.02 |

Five samples per size. These variants cost 264 gas, because the Agreement emits two additional public events. Both occurrences survive with distinct identities and correct ordinals even though their payloads are equal. Rotating targets use a deterministic stride across different Orders. The separate-source case successfully creates and runs 100 distinct external timeline subscriptions after fixing both discovered transaction limits. Its roughly eight seconds of quiet-prefix preparation at 100 sources is outside the operation timing and is not omitted from the report.

### Why AWS remains slower than SQLite

The final 1,000-Order fixed-target sample medians expose the remaining physical work:

| Measured store method | Calls per input, median | Elapsed ms, median |
| --- | ---: | ---: |
| Individual immutable content reads | 156.5 | 354.94 |
| Batched immutable content reads | 35.5 | 96.51 |
| Mutable record reads | 36 | 85.06 |
| Conditional publications | 6 | 120.23 |
| Immutable content publications | 6 | 65.32 |

These metrics are inclusive adapter observations. Summing independent medians is not an exact reconstruction of one sample. Network/storage phases dominate: only three owners execute, yet the host still performs many remote accesses. The selected metadata changes are not a complete remote-read consolidation. The rejected read-pack experiment did not materially solve that either. Future work should profile the exact sequential dependency chain and reduce remote round trips without caching entire unrelated documents or weakening admission/publication guarantees.

### Authenticated deployed HTTPS results

| Orders | Creation ms | Median submit-to-Agreement-history ms | Min–max ms | Final three-document total |
| ---: | ---: | ---: | --- | ---: |
| 10 | 1572.86 | 1598.95 | 1500.17–1671.76 | 210 |
| 100 | 3741.44 | 1573.62 | 1492.43–1614.10 | 210 |

All ten calls passed, including exact parent-entry observation, `owner-local` mode, all three business totals and repeated idempotency keys. This private native-timeline fixture costs 224 gas; it is not the public-source probe’s 228-gas fixture. Client network and polling are included. Console assets were reused and manifest-verified; no new visual/browser interaction suite was run in this iteration.

### Actual whole-service restart

The existing 100-Order Agreement advanced from version 6 to 7; Payment, Order and Agreement all reached 252. Gas was 224, the root remained `owner-local`, and the repeated idempotency key returned the original submission. Submission took 1593.50 ms and the exact committed parent history was observed after **5416.53 ms**. This is a first operation after a real process restart, with JFR profiling enabled.

| Owner phase | Restore ms | Admission ms | Execution ms | Preparation ms | Commit ms | Total duration ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Payment | 57.62 | 233.17 | 131.86 | 121.20 | 156.22 | 700.75 |
| Order | 27.54 | 41.53 | 53.34 | 81.29 | 96.90 | 300.69 |
| Agreement | 34.49 | 38.27 | 99.74 | 170.92 | 98.46 | 441.94 |

The three distinct recorded owner durations sum to about 1,443 ms. The remaining client-observed interval includes submission, source acquisition/scheduling, gaps and network/polling; this trace does not assign every remaining millisecond to one cause. The prior restart observation was 7,257 ms without this exact profiling setup; no controlled cold-start improvement claim is made. The first JFR export contained an identical chunk twice because dump and stop wrote the same destination. The raw SSM result is retained; the normalized summary deduplicates only identical complete event records, records the duplicate count and retains three distinct applied owner executions. The script now avoids that double export.

### Existing published-clause workflow and API compatibility

The normal compositional-policy bootstrap passed in 3711.78 ms. The final Agreement has `paidTotal: 200` and emits `Agreement Payment Received`. This is the generic published-clause path, not evidence of scalar fast-path eligibility.

| Operation | Submit-to-Agreement-history ms |
| --- | ---: |
| placeOrder | 6012.52 |
| preparePayment | 7138.82 |
| acceptPaymentInstruction | 5559.55 |
| reportTransfer | 5999.61 |
| acknowledgeTransfer | 5665.41 |

The qualification also passed these recorded checks:

```json
[
  "Cognito login",
  "anonymous rejection",
  "cross-origin write rejection",
  "bootstrap retry",
  "cross-account isolation",
  "two shared participant timelines",
  "Payment to Order to Agreement propagation",
  "operation retry",
  "retained events",
  "immutable initialization history after later operations",
  "latest applied history",
  "API key creation, authenticated read and revocation"
]
```

These multi-second general-clause times are still unacceptable for a claim of universally millisecond execution. They are retained as a compatibility result and an explicit remaining performance limitation.

### Deployed artifact and cleanup

Preview: **https://d2buikczdox1rt.cloudfront.net**. Stack `myos-better-canonical-20261108`, instance `i-052007ebaf704c0ad`. Deployment command `6f991618-c0e0-4ffb-ba57-c02b8a54a86c` completed successfully. Health confirmed durable canonical processing, native admission, automatic timeline processing, two workers and the preserved compositional creation default.

Distribution SHA-256: `beb5e840f3803b363ce1bece2099f008f736772861108188ca0ccfcd3ac4694b`.

Application JAR SHA-256: `dd5d053e3ab28d0a95b3a7b92fcc336446a72b35159195ca326140949aad4615`.

Library JAR SHA-256: `3ef25e2fd16a1d1d128760de362c042e4506c97e1a7165b1d1a040ab58e60581`.

All three final worker command manifests record those exact JAR hashes. Their raw result files include every comparison and timing sample. All ten temporary workers used during this iteration, including failed/rejected experiments, were independently verified as **terminated**. Synthetic qualification account data remains isolated in the existing table; no user documents were removed. The live preview remains running. No GitHub operation was performed.


## Residual work and appropriate audit claims

- The selected-owner fast path is qualified for its supported scalar/acyclic workload. Unsupported combinations, structural changes, termination and owning cycles use native fallback as required. Do not advertise arbitrary clause workloads with the fast-path numbers.
- The shared-source import problem is reduced, but DynamoDB still executes many sequential reads and conditional phases. Matching SQLite latency requires further storage/layout work, not merely another gas change. The rejected read-pack experiment is evidence that a plausible cache/layout change alone was insufficient.
- The coherent scheduling manifest remains an account-level contention point. Sustained multi-worker contention, many-account load and production throughput/SLO qualification remain separate work.
- A lagging account-wide external bound falls back to the relevant owner's distinct dependency set. Large sets of unique external providers need their own scale qualification; the successful 100-source case is not proof of 100,000 providers.
- Initialization remains O(initial graph size). A full inline private-channel 1,000-Order HTTP request exceeds the existing 1 MiB input limit in the previous report. This candidate does not implement content-addressed large upload sessions, nor claim a successful 1,000-Order public-HTTP creation.
- The direct metadata encoding needs a compatible rollback reader/storage plan. The append-only subscription index needs a safe compaction design if high membership churn is expected.
- Tests cover selected crash/retry, tenant and permission boundaries. They do not replace a security review, production backup/restore drill, independent protocol implementation or proof-system audit.
- Audit the authored package/spec boundary, gas edition, original-entry ordering, historical result selection, coherent publication, legacy migration and fallback eligibility first. Performance tuning must preserve these invariants.

## Bundle and reproduction

The archive contains the three clean source projects and a bounded `blue-packages` directory of pinned publication inputs required by the examples/tests. That directory is explicitly a test-input subset, not a replacement full package repository. `blue-packages/INPUTS.json` records hashes. Essential runtime `.bluepkg` resources, Gradle wrapper JARs, the verified console distribution and its authored inputs are included.

Build output trees, local databases, `.git`, downloaded dependency caches, `node_modules`, raw JFR recordings and credentials are excluded. Small test TLS fixtures are clearly test-only and remain where their tests require them. AWS account/instance/command identifiers in evidence are references, not signing secrets. QA credential files and AWS properties are not exported.

Run `audit/reproduce-local.sh` with JDK 25. Dependencies must be available through Gradle or its cache; no Python implementation checkout is needed. AWS commands require the operator's own authorized credentials and create isolated synthetic test data. See `REPRODUCE.md` for the exact local, dedicated-worker, HTTPS and restart commands. `SHA256SUMS` at the bundle root binds every delivered file; the ZIP has a separate SHA-256 sidecar.
