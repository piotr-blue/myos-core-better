# Canonical graph execution

The `canonical-graphs` runtime feature processes an embedded graph with the same
`Contracts` scheduler used by an inline Blue document. Storage IDs never enter Blue
content. The console enables this path for participant bootstraps; existing local
`doc-…` documents retain their original execution edition.

## One transaction

1. Select an original signed Timeline entry against a frozen source cut.
2. Bind each receiving channel and validate the member's historical read-access policy.
   Repeated references to the same source are resolved once in this admission. Identical
   permission bindings share a verified decision within that cut, not across inputs.
   Native channel decisions share one deeply immutable admission projection; the whole
   receiver inventory is not copied again for each channel.
   Consecutive inputs that cannot participate are acknowledged in bounded batches. Each
   input keeps its order key and channel assessment; a batch stops before a participating
   input or an unresolved obligation. A raw event listener still receives each occurrence.
3. Run a private `Contracts.Transaction`. Acyclic dependencies propagate toward parents;
   cyclic components use canonical scheduling rounds and the canonical gas budget. Per-member
   edge indexes retain the required containing-reference order. Patch validation checks only
   the path's strict ancestors for managed boundaries, instead of scanning sibling embeddings.
4. Publish newly needed immutable snapshot content and final index pages. The prior catalogue
   supplies unchanged complete closures. Its append-only content index can retain historical
   records; a normal portable export reconstructs the current closure. Ordinary native records
   reference descendants by BlueId instead of repeating their full subtrees. Contract
   definitions retain their literal presentation and cyclic records retain their owning
   proofs. Unchanged record text and index data use bounded caches. Cold reconstruction
   batch-loads pages and records and performs the original integrity checks. Immutable
   page writes have at most four batches in flight per publication; all must finish
   before any mutable head or source position can commit.
5. Conditionally commit the graph head, history, receipt, subscriptions and consumed
   source position together. Only then install the candidate in memory.

A rejected business invocation consumes its established input but keeps the complete prior
Blue snapshot. Missing resources or failed authentication cannot consume it. An uncertain
storage response is resolved through the atomic command receipt. No permanent visited-set
shortcut is used for cycles; nonterminating feedback exhausts its budget without publishing
partial state.

Source notifications distinguish verified non-operation events from potentially actionable
operations. A non-operation event need not wake an idle graph with no raw listener for that
source. Waiting receivers still wake to check new evidence, and an overlapping operation
widens the pending notification again. This is a scheduling optimization, not permission
to skip an input during receiver selection. Missing legacy hints use conservative fanout.

Completed transactions materialize their private snapshot once. Repeated identity, export
and receiver-inspection calls reuse that snapshot. Receiver descriptions are immutable and
belong to one session snapshot; they never retain a prior input's admission decision.

## Identity and addressing

`graph-…` identifies a root in the console; stable member IDs append a hash of the canonical
member key. Ordinary operations address the member's first published Blue identity. A
persisted address history proves its membership. This avoids attaching the growing current
Agreement to every operation. Explicit exact-version requests retain their stricter semantics.

For the same publication, edition and ordered inputs, the inline and durable executions are
checked for equal receipts, snapshot identities and complete canonical snapshots. Storage
pages and their hashes are implementation details. They do not replace BlueIds or proofs.
Different clause publications can produce different identities and gas; comparisons therefore
use the same publication on both execution paths.

## Incremental Documents

`../blue-packages/clause-documents-incremental` publishes the `IncrementalDocuments` namespace.
The clause remains Blue/BEX. Concrete Document/Documents clauses declare refresh scope so
that a child's event refreshes that entry. Whole-collection changes and custom base types
without that scope use full reconciliation. Existing Documents publications remain available.

`examples/clause-tutorial/agreement-incremental.blue` contains Activation and an incremental
Documents clause. `placeOrder` starts an Order with Activation and Payment; `preparePayment`
starts a Manual Receipt Instruction. Its acknowledgement updates the Order and Agreement.
All three reuse the same two participant Timelines.

## Reproduce

With Java 25 and the sibling `blue-java-better` checkout:

```sh
./gradlew check
./gradlew canonicalGraphProbe
```

The local probe distinguishes targeted delivery from broadcast. Its normal run changes one
Payment, its Order and the Agreement; it asserts exactly three changed members. Setting
`blue.probe.broadcast=true` deliberately delivers to every Payment. The earlier iteration-198
numbers measured that broadcast case and must not be read as single-Payment locality results.

For the deployed authenticated native path:

```sh
python3 scripts/reproduce_canonical.py --email piotr@bluecontract.com --orders 2
```

The password is prompted, never written into the report. `MYOS_API_KEY` is also supported.
The report separates engine time, server admission through durable commit, signed-entry
through commit, submission time, and client-observed completion. Commit telemetry is a bounded
process-local diagnostic, not canonical evidence; a historical response after restart may
have no telemetry. No timing changes document content or gas.

For an operator running the same AWS test stack, `scripts/qualify_canonical.py` uploads the
separate qualification JAR and runs direct-inline equality, DynamoDB persistence, 2/8-member
cycles, bounded feedback through 1/2/8-member cycles, rejecting-peer rollback and execution
after cold restoration. It uses the deployment's account-checked AWS configuration:

```sh
./gradlew installDist qualificationJar
python3 scripts/qualify_canonical.py --orders 1,128,1024
```

This second probe uses trusted operator inputs. It does not stand in for the authenticated
native Timeline test. Deployed measurements and artifact identity are recorded in
[NATIVE-LATENCY-CURRENT.md](NATIVE-LATENCY-CURRENT.md).

## Boundaries

- Graph atomicity applies within one graph, not across independently committed accounts or roots.
- New members on the existing source set join the graph's current cursor. A genuinely different
  source set requires historical rebasing; that operation is not implemented and fails closed.
- Canonical commitments, export and some validation still visit graph-wide metadata. Sparse
  writes and duplicate-read removal do not establish constant complexity at arbitrary sizes.
- A cold graph needs reconstruction. The prototype session cache has a graph-count bound;
  further byte-based retention and long-history qualification are needed.
- The console history for a member currently shows graph transaction history. It is not a
  separately committed per-member event stream.
- Cycles can legitimately perform multiple rounds of business work. Cost depends on affected
  components and their required rounds.
