# Retained AWS baseline — runtime 231, qualification 230

This is the retained 2026-11-06 baseline, not the current deployed artifact. See
[dashboard parity iterations](DASHBOARD-PARITY.md) for later deployments and measurements.
The workflows tested in this baseline were correct. **The requested
near-constant, low-millisecond end-to-end performance is not achieved.** The evidence
below deliberately separates ordinary signed console operations from simpler operator
qualification. Both run the canonical Contracts engine; neither substitutes mock state
updates for Blue execution.

## Deployment and verification

- [Open the console](https://d2ya9daeiur7nz.cloudfront.net), using your existing account.
- Tested on 2026-11-06, eu-west-1, c7i.large: 2 vCPU, 4 GiB RAM, 2 GiB service heap,
  Corretto 25.0.5, DynamoDB. Qualification runs use a separate 1 GiB JVM on that instance.
- Runtime SHA-256: `87a7a8fc263eeb67e77daef47c4e610dfe68ce6e1e1e37433b691a4a8f3bcd9c`.
- Successful deployment SSM command: `6c5a2357-5b7f-4120-a4be-5a48925aa720`.
- Successful scale/cycle SSM command: `9f246166-6aed-4ca3-ab76-f5d00020dd85`.
- Qualification JAR SHA-256: `d54da50a80ec459de728ac2436f78f53302a91743c46dfed573efe09179a849b`.
- 2,482 library tests and 509 host tests passed, no failures, errors or skips. Formatting,
  strict compilation, Javadoc and distribution builds passed. All 53 installed JARs
  matched the tested build; console assets matched their source manifest.
- No profiler was active on AWS during these measurements. Native journeys and scale qualification
  ran sequentially. The ordinary deployed service remained running during qualification.

The [small JSON evidence file](performance/2026-11-06/aws-canonical.json) retains every
sample, the observed timings and the deployment/qualification identifiers. No access keys,
passwords, session cookies, document bodies or gas traces are included.

## Real signed Timeline workflow

The source uses published Activation, IncrementalDocuments and Payment clauses. It creates
an Order, then a Manual Receipt Instruction. Acknowledgment updates Payment → Order →
Agreement through Blue workflows. Every submitted entry completed `APPLIED`. All three
documents reused the same two participant Timelines. Final paginated reads independently
verified exactly eight and 208 Orders, including every newly requested path. An unrelated Order and Payment kept
their BlueIds while later Orders and their Payment changed.

### Fresh Agreement: eight Orders

[Open the fresh Agreement](https://d2ya9daeiur7nz.cloudfront.net/#documents/graph-0464b702957e04068682bcf6c5a13b03201fd734e0dba957). Final total: **400** minor units,
from two completed Payments. Bootstrap took **7246.2 ms**.
The first Order call after deployment took **5864.1 ms**
observed, of which **779.5 ms** was engine work. These first-use
costs are included in the evidence; they are not discarded as successful warm timings.

The last Order and its Payment:

| Operation | Engine ms | Admission → commit ms | Signed entry → commit ms | Client observed ms | Including lookup ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| placeOrder | 308.2 | 428.7 | 755.6 | 1336.9 | 1782.8 |
| preparePayment | 465.0 | 584.1 | 1433.7 | 2395.6 | 2838.1 |
| acceptPaymentInstruction | 67.6 | 162.4 | 880.7 | 1705.6 | 2189.6 |
| reportTransfer | 130.7 | 228.5 | 865.5 | 1525.6 | 1970.9 |
| acknowledgeTransfer | 202.0 | 317.3 | 564.2 | 1092.7 | 1540.5 |

### Existing Agreement: 200 → 208 Orders

[Open the 208-Order Agreement](https://d2ya9daeiur7nz.cloudfront.net/#documents/graph-a3795cde70d9cca1a179871662c6a57ef1222a11c633b270). Final total: **2,000** minor
units. This graph has 208 Orders, with Payment Instructions created for selected Orders;
it is not 208 fully paid Orders. The eight new Order calls had median engine time
**673.8 ms**. The final Order/Payment:

| Operation | Engine ms | Admission → commit ms | Signed entry → commit ms | Client observed ms | Including lookup ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| placeOrder | 954.4 | 1133.1 | 1450.0 | 2034.0 | 2579.1 |
| preparePayment | 686.2 | 855.6 | 1834.9 | 2847.4 | 3394.6 |
| acceptPaymentInstruction | 387.0 | 563.6 | 1611.8 | 2522.8 | 3074.3 |
| reportTransfer | 228.3 | 352.3 | 1134.0 | 1786.6 | 2331.7 |
| acknowledgeTransfer | 460.1 | 623.6 | 875.2 | 1569.9 | 2065.8 |

The initial catalogue read was 257.3 ms. This is an observed read,
not a guaranteed cold-recovery benchmark: background recovery may have warmed the graph.

### What each clock means

- **Engine:** canonical execution, validation and result preparation.
- **Admission → commit:** engine plus snapshot persistence and atomic head/input commit.
- **Signed entry → commit:** also includes source acquisition, readiness and scheduling.
- **Client observed:** submission through confirmation by the remote client; includes network
  travel and polling. The client was outside eu-west-1.
- **Including lookup:** additionally includes fetching the operation catalogue and current head.

`Submitted` in the console confirms signed publication, not business completion. Inspect the
committed history/state. Per-version performance telemetry is bounded and can disappear after
restart; the persisted business outcome remains.

## Larger graph qualification against inline execution

This separate test uses simpler Agreement/Order/Payment documents and **trusted operator
inputs**, avoiding Timeline acquisition. It runs two warmups and five measured calls per size.
Each call targets one Payment. Exactly three member identities change: Payment, its Order and
Agreement. Every receipt and graph identity equals ordinary inline execution; the complete
persisted snapshot is byte-equal at the end of each scenario.

| Orders | Total documents | Changed members/call | Median engine ms | Median durable ms |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 3 | 3 | 27.0 | 77.9 |
| 128 | 257 | 3 | 66.2 | 125.0 |
| 1,024 | 2,049 | 3 | 400.6 | 589.4 |

Creation is measured separately and is outside the per-operation medians above:

| Orders | Inline prepare + initialize ms | Persisted graph creation ms |
| ---: | ---: | ---: |
| 1 | 1049.3 | 1039.8 |
| 128 | 2902.3 | 3824.2 |
| 1,024 | 70364.3 | 76732.8 |

Creation includes admitting and initializing every embedded document. It remains expensive;
the steady-state results must not be used as startup estimates. Source-object construction in
the probe occurs before these timers.

These measurements still show graph-size overhead. They are five-sample medians, not p95/p99
or concurrent throughput claims. They cannot be substituted for signed console timings.

## Cycles, feedback, rollback and restart

The AWS qualification passed:

- Two- and eight-member cycles: seven inputs, matching inline receipts and full snapshots.
- One-, two- and eight-member feedback cycles: a `Ping` circulates through embedded listeners
  in canonical rounds and stops at the declared bound. The root count reaches exactly three.
- A two-member cycle with a rejecting peer: the outcome is `FAILED`, and every member's business
  state rolls back. The failure itself is a committed, inspectable outcome.
- All those cases reopen their persisted graph in a fresh host object and process another input;
  receipts and complete snapshots still equal the inline reference. This is cold object/cache
  restoration, not an EC2 reboot or a process-crash injection test.

Feedback timings are individual correctness samples, not a performance distribution.
The regular regression suite additionally covers missing/forged admission, permission changes,
publication failure, stale transactions, escaped collection paths and unbounded-cycle gas failure.

## What changed

The [iteration record](CANONICAL-ITERATIONS-197-210.md) documents retained and corrected candidates.
The main changes are:

1. A separate `IncrementalDocuments` Blue package refreshes only the affected entry when its
   declared dependencies are entry-local. Collection/configuration changes and custom global
   dependencies use full reconciliation. Existing `Documents` publications are unchanged.
2. Canonical graph transactions preserve the same Blue state, including cyclic sets. Original
   signed inputs, history, state and input consumption commit atomically. No Order-specific
   code is installed in the engine.
3. Changed content is persisted through a retained content catalogue and batched immutable
   writes. Ordinary snapshots reuse descendant BlueIds; cyclic records retain their proofs.
4. Topology plans, declarations, immutable metadata and proof encodings are reused within their
   valid ownership scopes. Unchanged documents avoid repeated parsing and validation walks.
5. Native admission is verified per input. Its immutable result is shared within that invocation.
   Local Timeline transport reuses exact materialization without importing producer-only evidence.
6. Nonparticipating input dispositions are batched without losing their order keys. Verified
   non-operation notifications no longer wake idle graphs that have no matching observer;
   waiting receivers and raw event observers still wake.
7. A completed transaction materializes its snapshot once, instead of independently copying the
   whole graph for identity, export, reception and commit.
8. Per-member input installation uses indexed edges in cached decoded-pointer order. Patch
   validation looks up strict ancestor paths instead of scanning sibling embeddings.

The first signed Order call was 25.3 s in deployment 226, 12.7 s in 227 and 5.8 s in 229.
These are separate first-use runs, not an isolated, controlled speedup measurement. Release 231
first-use and warm costs are reported above; startup is still too expensive.

## Why growth has not disappeared

The entry-specific clause scan is removed, but canonical execution still prepares graph-wide
metadata, idle-unit commitments and a complete result commitment. Some snapshot copies and
validation passes are also graph-wide. Changing one descendant can change its containing
Blue documents and their identities. Native acquisition and durable writes add separate costs.

A separate local JFR diagnostic recorded 40 steady-state calls on the 2,049-document graph.
Its frequent first application/library frames included string escaping (`LanguageCodec.appendQuote`),
canonical string comparison, evidence retention (`ContractLanguage.retain`), trie lookup,
encoding-buffer writes and `ContractRun.preflight`. This supports continued work on shared
serialization, evidence and validation; it does not identify one isolated business handler as
the cause. The profile is separate from the AWS timing runs and is not a wall-time breakdown.
The JSON evidence contains a compact sample summary, not the raw recording.

Code inspection also identifies a startup multiplier: `ContractGraph.freshKey` hashes the
current snapshot when there is no invocation cause. Repeating that while the initial graph
grows can cause quadratic work. This was not isolated as a percentage of the 76.7 s startup,
but it is a concrete remaining path to address. Changing the key derivation requires an
explicit compatibility/versioning decision; quietly caching a different value would change
canonical allocation results.

Thus the business effects are local—three changed members in the scale test—while total work
is not yet fully local. A flat commitment over all members cannot be advertised as a constant-cost
changed-path update. Eliminating it requires a specified incremental commitment/receipt mode
and corresponding validation, rather than silently dropping obligations from the current profile.
No signature, permission, gas or cyclic validation was disabled to obtain these numbers.

This is not yet a production-scale qualification. Tens of thousands of documents, concurrent
accounts, long histories, byte-bounded graph session retention and source-set rebasing remain
unqualified or unfinished. The current graph path fails closed for unsupported source-set rebasing.

## Reproduce without AWS administration

From your local checkout:

```sh
cd /Users/piotr/data/myos-aws-test-better
python3 scripts/reproduce_canonical.py --email piotr@bluecontract.com --orders 2 --report canonical-2.json
python3 scripts/reproduce_canonical.py --email piotr@bluecontract.com --orders 128 --report canonical-128.json
```

The script prompts for your password; alternatively it accepts `MYOS_API_KEY`. Each run creates
fresh documents and two Timelines in your account. It prints their URLs, checks committed
outcomes, exact Order paths/count, all three totals, shared Timeline bindings and unchanged unrelated identities. The
128-Order run creates real records and takes longer; it is not needed to try the two-Order demo.

For manual operation, follow the [console walkthrough](AWS-CLAUSE-WALKTHROUGH.md).
**Bootstrap document → Clause tutorial** loads the [same YAML](../examples/clause-tutorial/agreement-incremental.blue).
On an existing Agreement, use a fresh Order key, create its Payment, accept it, report a transfer
and acknowledge that transfer. Agreement `paidTotal` should rise by 200. History and Events
show the propagation; this demonstration does not transfer bank funds.

## Reproduce the AWS scale and cycle qualification

This uses the existing account-checked deployment credentials and creates isolated test records:

```sh
cd /Users/piotr/data/myos-aws-test-better
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
./gradlew installDist qualificationJar
MYOS_AWS_PROPERTIES=/Users/piotr/data/myos-aws-test/aws-access.properties \
  python3 scripts/qualify_canonical.py --orders 1,128,1024
```

The runner verifies the deployed release identity, uploads only the qualification JAR, and runs
on the existing test instance. Results are saved under `reports/canonical-qualification-*`.
It does not redeploy or create another EC2 instance.
