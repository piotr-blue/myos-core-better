# One semantic policy and faster published-clause processing — 10 November 2026

This report covers the candidate developed from the auditor feedback in the 10 November handoff.
It separates measured improvements from unfinished work. The implementation retains the existing
Language/Contracts architecture, SQLite local reference host, and DynamoDB hosted architecture.
No Python implementation or Blue package publication was changed.

## What changed

### One policy, not a renamed limited prototype

`GasSchedule` has one member, `STANDARD`, persisted as `contracts-semantic-gas`. There are no
PUBLISHED, COMPOSITIONAL_V1 or ADDITIVE_PROTOTYPE execution alternatives. The console request,
local CLI and deployment script no longer offer a policy selector. Full BEX expressions,
statements, functions and effects use the canonical BEX tariffs. Contracts charges semantic
business work; expansion/collapse, identity hashing and physical bookkeeping are uncharged.

The exact policy, examples, overflow rules and requirement-to-test mapping are in
[SEMANTIC-GAS.md](../../../blue-java-better/docs/SEMANTIC-GAS.md). This changes accounting from the
older releases. It does not claim old gas totals or byte-identical old receipts. Updating the
published specification and obtaining auditor approval remain separate work.

Old histories are not rewritten. Missing/retired policy identities reject execution. Pending jobs
are durably held with a replay diagnostic, rather than retried every five seconds. Original
source and inputs must be replayed into a new history. Existing history remains stored. A normal
operation request is rejected before appending if its target history requires replay.

### Full BEX with an explicit evaluation stack

The recursive evaluator was replaced with a continuation machine. Short-circuit branches,
function calls, loops, effects and error boundaries preserve evaluation order. Statement compilation,
deep equality and pointer overlay traversal also use explicit work stacks. Tests include 20,000-deep
expressions, 5,000 nested statements, 20,000-deep equality and 12,000-segment pointer updates.
Physical limits remain distinct from semantic gas failure. This is not a claim that every
presentation/helper routine in the entire library is arbitrarily stack-safe.

The existing canonical BEX vectors remain the primary instruction-level behavior/gas oracle.
The host whole-versus-split oracle independently replays original inputs without borrowing measured
source completions or protected preparations, but uses the same native interpreter. It cannot
independently expose every defect shared by both interpreter executions.

### Ordinary clauses now use independent-owner execution

The old local-write guard only accepted a very narrow scalar case. Safe add/replace/remove changes
of ordinary data containers now remain owner-local, including inherited data. The checks still
exclude changes to Contracts declarations, embedded paths, contextual constraints and generalization
repairs. Full Language validation, protection rules and update events remain enabled.

| Real published operation | Selected behavior |
|---|---|
| `placeOrder` | Structural birth at `/orders/order-N`; canonical graph path |
| `preparePayment` | Payment birth at `/payment`; source graph path; parent consumes retained birth result |
| `acceptPaymentInstruction` | Owner-local ordinary inherited data update |
| `reportTransfer` | Owner-local Payment → Order → Agreement propagation |
| `acknowledgeTransfer` | Owner-local Payment → Order → Agreement propagation |

No authored clause was simplified, no authorization was omitted, and no example-specific behavior
was installed in the engine. Newly adopted child identities are also recorded in the parent's
historical target index, so a subsequent operation can address the child's new state correctly.

### Less physical work

1. Local append classifies a known target inside the same acceptance transaction, before scheduling
   consumers. Unknown targets and broad observers keep conservative delivery and native admission.
2. Timeline directory selection batches independent known keys. Mutable source/control records
   remain freshly read and checked; they are not treated as cached authority.
3. Verified immutable String-valued radix pages can be reused across attempts within a bounded,
   tenant-scoped cache. Mutable values and current-head authority are excluded.
4. Restoring a retained completion supplies pinned metadata plus lazy content providers; it no
   longer eagerly reconstructs its entire content closure.
5. The old “after 64 lookups, preload every reachable record” heuristic was removed. Request count
   does not justify opening unused workflows or sibling bodies. Portable export still explicitly
   reconstructs the requested closure.
6. Event-content retention builds one final catalogue revision rather than an intermediate root
   per record. A regression compares the exact final index to sequential construction and reads
   every historical event field after reopening.
7. A child completion carries content keys tied to its committed page, rather than copying all
   newly encountered content bodies upstream. The parent selects those exact committed addresses
   and updates its own content index. Missing keys and cross-account access fail. The complete
   parent remains reconstructible after a cold restart.

The seventh change matters on the first transfer: the previous handoff carried roughly 3,400–3,900
new content records (about 4 MiB) through each parent. Published types and event dependencies can be
large even when the business event is small. Their records now remain at immutable addresses.

### Final refinements and rejected experiments

8. `OrderedIndex.update` prefetches the known write paths by depth. A 100,000-key test changes
   64 distributed keys in at most four batch-read rounds, with no point reads and no mutation of
   the prior root. This changes physical I/O, not index ordering or conditional publication.
9. A newly added owner can share its **committed predecessor's immutable content catalogue**;
   its own owner metadata, admissions and topology remain separate. Portable export still yields
   the exact records and fields. The materialized predecessor of a structural transition likewise
   reuses its retained content instead of serializing about 13,000 records again.
10. Scheduling selection reuses one read-only metadata cut when no seal changed it. The claim
    still reads fresh state and rejects stale selections. Tests cover a seal appearing after the
    read and another worker claiming the selected work.
11. Independent-owner admission and execution can borrow the verifier-issued original entry's
    immutable value facts. This avoids validating a large historical operation target twice.
    The entry/message identity is checked; permissions, source completeness, receiver lifetimes
    and current membership are still checked normally. One owner attempt can borrow one original
    occurrence. Closing the attempt revokes its values and every derived semantic-memo branch.
    There is no document-session cache or alternate authorization path.
12. JFR separates source selection, policy restoration, prefix selection, permission checks,
    operation grammar and checkpoint work. These physical diagnostics never enter gas or receipts.

The historical-target alternative fetched another protected checkpoint. It reduced the grammar
phase from roughly 80–110 ms to 4–35 ms, but shifted validation into execution and added reads;
its total transfer latency was worse. It was removed. A bounded occurrence-position cache was
also removed because it did not show a useful end-to-end gain. Grammar-only reuse was superseded
by one attempt-scoped evidence lifetime.

A broader catalogue-sharing experiment let initially created siblings inherit each other's
indices. The cold 1,000-Order median regressed to **922.562 ms**, versus **737.383 ms** with the
batching-only candidate. The final implementation shares only a committed predecessor during
subsequent creation; initial owners get their own closures. Existing 1/10/20/65/1,000-Order parity
scenarios now assert that an initial owner's catalogue contains its own state and excludes a
sibling's state. The final cold median returned to **754.726 ms**. This is a physical storage-layout
regression and correction, not a gas adjustment or an omitted correctness check.

## What the AWS round trips do

For a three-owner causal input, the bounded scale probe records six conditional publications:
claim and completion for Payment, then Order, then Agreement. Claims protect concurrent processing;
completions atomically publish history, source progress and scheduling changes. Removing these
transactions blindly would weaken crash/concurrency behavior.

| Dependency boundary | Data needed | Reuse/batching rule |
|---|---|---|
| Select/claim | Current account manifest, readiness, source completeness, claim revision | Fresh authority; selected index paths only |
| Restore | Claimed immutable page, owner checkpoint, selected child receipts | Reuse exact immutable page; never latest unrelated child state |
| Admission | Current source adoption/control pins; historical permission facts and signed input | Batch known directory keys; retain ordinary verification |
| Execute | Selected declarations and called workflows | Demand-driven immutable content; no unused closure preload |
| Prepare | Changed member/admission/content indices, evidence and emitted event content | Borrow committed child addresses; batch immutable writes |
| Publish | Current claim token, expected metadata revision, host control version | Conditional transaction; contention retries metadata publication |

The real clause journey additionally includes signed timeline submission, access/control processing,
acquisition and asynchronous worker discovery. Its end-to-end time must not be confused with the
native execution duration reported for only the Agreement.

## Tests and measurements

All raw selected evidence is adjacent to this file. Generated credentials, access keys and bearer
credentials are excluded. Test counts are cases, including parameterized invocations; they are not
claims about exhaustive arbitrary documents.

- Library: **2,503 passing cases**, including canonical BEX/Contracts and semantic composition.
- Local host: **75 passing cases**, including restart, shared-budget rejection, cycles and clauses.
- Hosted service: **848 passing integrated cases**; the final integrated summary is in
  `host-tests.json`. The additional sibling-catalogue assertions passed in the owner parity suite.
- Deployment/qualification scripts: **48 passing unit tests**.
- Real published workflow: three complete Order/Payment journeys, **15 independent whole-root
  semantic comparisons**, Agreement total **600**. The unchanged workflow reuses two participant
  timelines.

The semantic comparison includes document BlueId, status, total gas, disjoint gasByMember, ordered
events, public events, diagnostic and rejected charge. Physical receipts differ with storage and
execution boundaries; their own commitments are still verified. Tests do not normalize away
business failure or event order. Two equal-valued public occurrences remain two occurrences.

### Restarted local storage

The local runtime/database is closed and reopened before each input. The JVM and common immutable
definitions remain warm; there is no retained mutable document session.

| Orders | Three append-through-commit samples (ms) | Median (ms) |
|---:|---|---:|
| 10 | 75.663, 46.610, 39.451 | 46.610 |
| 1,000 | 42.740, 40.010, 39.030 | 40.010 |

Exactly three owners process each input and whole-root semantic parity passes. Local, AWS worker
and public-console fixtures are not identical: gas is compared against whole execution of the
**same fixture**, not against a different host's example total. The local example reports
Payment gas 43, Order inclusive gas 103 and Agreement inclusive gas 150. Therefore the disjoint
costs are 43 + 60 + 47 = 150; adding inclusive totals would double-count child work.

### AWS cold-storage scale screen

The dedicated worker is capped at two CPUs and a 1 GiB JVM heap. Owner storage readers/caches reopen
before each input; targets rotate deterministically. SDK/content transport caches are disabled for
this probe. Common immutable package definitions and the JVM remain warm. The oracle runs after
the timed operations. Five samples per size are a bounded screen, not a tail-latency SLA.

The final candidate has these five durable-processing observations:

| Orders | Samples (ms) | Median (ms) |
|---:|---|---:|
| 10 | 714.882, 637.875, 621.062, 564.883, 569.410 | **621.062** |
| 1,000 | 778.102, 714.423, 715.979, 773.115, 754.726 | **754.726** |

All ten semantic comparisons passed; exactly three owners executed and gas was **145** each time.
Two equal-valued public occurrences remained two ordered occurrences. The graph grows 100×,
while this sample's median grows about 22%. Cold index depth and remote reads remain; this is not
mathematically constant time. Initial graph creation remains proportional to the number of owners.

The earlier deployed handoff candidate measured 650.457/797.788 ms. The batching-only screen measured
668.724/737.383 ms. These are bounded observations on different workers, not randomized paired
confidence estimates. Both regressions are retained: an early destination-read regression at
899.573 ms, and the initial sibling-catalogue regression at 922.562 ms. The final source JARs are
hash-identical to the JARs deployed to the preview; see `qualified-artifacts.json`.

### Real published clauses in AWS

Boundary: application API submission through the exact submitted-entry Agreement receipt and
expected state; includes asynchronous admission, acquisition, scheduling and DynamoDB; excludes
HTTP/client distance. JFR stage measurements are enabled consistently in these runs. Each row is
one operation per newly created Order. These are different temporary workers, not randomized
control/candidate pairs, so percentages are observations rather than isolated causal estimates.

| Operation | Earlier single-policy baseline, 2 samples (ms) | Final candidate, 3 samples (ms) |
|---|---|---|
| placeOrder | 5,865 / 3,505 | 5,092 / 2,615 / 2,676 |
| preparePayment | 5,388 / 4,973 | 4,063 / 2,723 / 2,780 |
| acceptPaymentInstruction | 4,008 / 3,853 | 1,637 / 1,398 / 1,279 |
| reportTransfer | 4,816 / 4,448 | 1,261 / 1,122 / 1,133 |
| acknowledgeTransfer | 4,074 / 4,470 | 1,320 / 1,278 / 1,287 |

All 15 original inputs matched independent whole-root replay: document BlueId, status, gas,
gasByMember, ordered/public events, diagnostic and rejected charge. Agreement paid total was 600.
The reportTransfer median is **1,133 ms**. The earlier reference-only handoff measured 1,979 ms;
the earliest baseline observations were 4,816/4,448 ms. These runs used unchanged published clauses.
The first placeOrder still costs about 5.1 seconds on the dedicated worker; later structural births
cost around 2.6–2.8 seconds. Those costs are visible and are not represented as steady-state updates.

## AWS deployment and architecture

Deployment completed successfully on the existing canonical preview,
https://d2buikczdox1rt.cloudfront.net, in eu-west-1. The health check reports durable canonical
processing, native admission, automatic timelines and only `contracts-semantic-gas`.

- Release SHA-256: `ff18c6420e3671fed0b6b71eb1af5c09354714262e65f016131af81ee7020863`
- SSM deployment command: `5da3d78a-bee0-4598-b48f-7be740c2d905`
- Instance: `i-052007ebaf704c0ad`
- Final application JAR SHA-256: `99ebbeb798a7a4f705fff1eed108a4d9dafb253c9ca7007f2358f00f02f9424c`
- No temporary qualification workers remain active.

The **final deployment's** HTTPS tests, from this client in Asia to eu-west-1, passed:

| Fixture | Initialization | Submit → exact committed parent receipt, ms |
|---|---:|---|
| 10 Orders | 1.551 s | 1,601.9 / 1,617.8 / 1,586.1 |
| 100 Orders | 4.175 s | 1,722.3 / 1,179.4 / 1,180.8 |

All six calls used owner-local mode, updated Payment/Order/Agreement totals correctly, and passed
duplicate-submission checks. The first run after deployment includes further SDK/JVM/content
warming; this small screen cannot establish a tail-latency distribution. These times include client
network distance and polling, unlike the dedicated cold-storage worker measurement.

The final live published-clause example created three Orders and paid the first and third. All
eleven original inputs committed successfully; the final Agreement total was **400**. All documents
reused the two participant timelines, and the untouched Order/Payment identities remained unchanged.
Bootstrap took **2.818 s**.

| Operation | Final live HTTPS observations (ms) |
|---|---|
| placeOrder | 2,727 / 1,779 / 1,775 |
| preparePayment | 2,615 / 2,425 |
| acceptPaymentInstruction | 2,107 / 1,859 |
| reportTransfer | 1,678 / 1,696 |
| acknowledgeTransfer | 1,898 / 1,840 |

Operation catalogue lookup is measured separately in `live-clauses.json`; it is not included in the
submit-through-parent-receipt column above. Its full value is also recorded in
`observedWithLookupMillis`. The public results do not establish a universal sub-second product SLA.
The example was created in an isolated QA account. Use the reproduction command below to create
one that is visible in your own account.

### Reproduce on your own account

Open the preview URL and sign in normally. Or run:

```sh
cd ~/data/myos-aws-test-better
/usr/bin/python3 scripts/reproduce_canonical.py \
  --url https://d2buikczdox1rt.cloudfront.net \
  --email piotr@bluecontract.com --orders 3 \
  --report canonical-reproduction.json
```

Enter the password at the private prompt. The script creates a fresh Agreement and two timelines,
prints its console URL, creates three Orders and pays the first and third. It checks committed
history for each exact entry and writes the timings/results to the report. No AWS keys are needed.
Old pre-policy documents require explicit replay; use a fresh bootstrap for this release.

CloudFront serves the existing console and proxies the API to one EC2 host through a private VPC
origin. Cognito provides sign-in. The Java 25 application runs under systemd, using a 2 GiB maximum
heap and a 3 GiB service memory limit. DynamoDB stores durable timelines, immutable content pages,
owner history and conditional scheduling metadata. S3 stores release bundles and qualification
artifacts. Secrets Manager protects provider/reader signing custody; SSM manages deployment.
There is no SQLite, Redis, SQS or Lambda in the hosted processing path. SQLite is only the local
reference host's durable database.

Immutable package definitions load before readiness. Bounded immutable caches can speed repeated
lookups, but mutable document sessions are restored per attempt and discarded. Startup/catalogue
loading in the dedicated clause runs took roughly 14–16 seconds before bootstrap, and bootstrap
roughly 4–5 seconds. Those costs are separate from the operation table. A first operation can still
pay JVM/SDK/crypto/content warming. The older 5.42-second profiled client observation is not a
measurement of package loading alone; these new measurements do not retroactively attribute its
unrecorded interval.

## Remaining limits and assessment

The qualified static acyclic update path merits **9/10 for locality**: only the causal owners run,
quiet siblings do not contribute gas, persisted cold-reader tests stay in the same latency range,
and whole/split semantics match. This does **not** justify rating the entire hosted product 9/10.
Real clause transfers now take about 1.1–1.3 seconds in the dedicated AWS screen; structural
creation still costs multiple seconds. Signed admission/storage/scheduling add substantial fixed
cost. A fair broad hosted-efficiency rating is **8/10**. I cannot honestly certify an overall
9/10 yet: the measured locality is strong, but structural mutation and remote-I/O overhead remain.

Next useful work remains bounded structural-birth execution and fewer repeated authority reads within
one pinned attempt. It must preserve permission revocation, original-input ordering, concurrent
claims, cycles, rollback and portable reconstruction. Account-wide metadata remains a potential
throughput bottleneck; this campaign does not establish sustained multi-account capacity, HA,
100,000-document native execution, or a million-document SLA. Established cyclic components use
canonical atomic execution; arbitrary topology changes can require broader work. External timelines
with distinct lagging completeness guarantees remain a different workload from a shared-source
static graph. Historical pointer compaction and a supported migration/replay tool remain unfinished.

Do not roll back only the binary after incompatible new histories have been written. Preserve the
new history and deploy a compatible repair, or restore/replay into isolated storage. No old receipts
were rewritten during this work.

## Exact verification and release record

Final source builds passed `check` in all three repositories. The hosted check contains 848 cases;
the additional direct initial-catalogue exclusion assertions then passed within the existing owner
parity scenarios. Deployment scripts passed 48 unit tests. The source promotion checks original
hashes before replacing files and preserves backups; it did not modify Python, Blue package
publications or the original `blue-java` repository. No GitHub operation was performed.

The final dedicated AWS screen passed 10 cold size comparisons and 15 published-clause comparisons.
Both temporary workers terminated; the AWS inventory confirmed no active qualification workers.
The deployed console then passed six static operations and the eleven-operation published-clause
journey. JAR identity was compared between the qualification workspace and the actual deployment
workspace before upload. SSM reported Success and the service readiness check reported only
`contracts-semantic-gas`.

Retained evidence includes the rejected historical-target and sibling-catalogue experiments.
Those candidate implementations are not runtime options. There is one deployed implementation.
