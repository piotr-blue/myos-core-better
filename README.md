# MyOS Core Better

[Architecture and design decisions](docs/ARCHITECTURE.md) · [Documentation map](docs/README.md) · [10 November qualification report](docs/polish-2026-11-10/REPORT.md)

A MyOS-compatible console and API backed by `blue-java-better` and DynamoDB. Authored documents
use the published Blue Language, BEX, Contracts and Coordination packages. The host adds durable
timeline acquisition, ordering, storage and scheduling; it does not add an alternative document
language or a `Core/*` execution package.

**Experimental: correctness and performance qualification are still in progress.** The current
canonical preview is [available here](https://d2buikczdox1rt.cloudfront.net). It shares the existing
sign-in pool and uses isolated document storage. Existing accounts can sign in; documents from the
older deployment have not been silently migrated. The older deployment and histories remain intact.

The [10 November implementation and measurement report](docs/semantic-release-2026-11-10/REPORT.md)
is a dated snapshot. Use the [documentation map](docs/README.md) for maintained guides and known gaps.

For release and runtime commands, use the [current operations guide](docs/OPERATIONS.md).

Start with [the canonical preview walkthrough](docs/canonical-preview.md). It uses the published
Activation, Documents and Payment clauses through normal signed Timeline inputs. The console
reuses MyOS components for documents, operations, timelines, history, bootstrap and API keys.

## Build and test

Requires JDK 25 and the sibling `../blue-java-better` checkout:

```sh
./gradlew check installDist
build/install/myos-core-better/bin/myos-core-better serve
```

Without `MYOS_TABLE`, the process uses an ephemeral test store. Set `MYOS_TABLE` and `AWS_REGION`
to select DynamoDB through the standard AWS credential chain. AWS hosts use instance roles.
Set `MYOS_OWNERS_FAST=true` for independently scheduled canonical owners. Both this adapter and
the retained whole-graph storage adapter use the same native Contracts processor.

There is one accounting policy: `GasSchedule.STANDARD` (`contracts-semantic-gas`).
It charges semantic business work, including the full BEX instruction set, and excludes
physical expansion, hashing, storage and proof assembly. The same causal execution has
matching gas, document identity and ordered events whether children run inline or independently.
See the [policy and test map](../blue-java-better/docs/SEMANTIC-GAS.md).

Saved histories bind that identity. Retired experimental histories remain unchanged and require
explicit replay into a fresh history; they cannot accept operations under the new policy.
There is no environment variable or console option for selecting an older policy.

## Processing model

Timeline acceptance/acquisition is independent of document processing. Internal timeline entries
share a serialized completeness authority; each imported external timeline carries its own verified
complete prefix. An owner selects the next original by Coordination order only when all current
sources cover it. Historical child outcomes are consumed at the receiver's source cut, never by
substituting a child's newest state.

Acyclic owners progress independently. Cyclic components execute as native atomic units. A newly created member can close a cycle with
the currently claimed unit without resetting that unit’s history or source positions.
A conditional storage publication commits state, history and scheduling deltas together, after
immutable content has become durable. Fresh mutable execution sessions are restored for attempts
and discarded; shared immutable caches are bounded. Fast evidence stores references to verified
input cuts and native results. It is not a portable zero-trust proof.

The library exposes one canonical Contracts execution model. Host ownership records and private
storage format editions are not Blue document types. Old experimental histories with incompatible
semantics are rejected rather than relabeled. See [ordering and recovery](docs/owner-frontiers.md).

## Qualification and limits

The local suites cover ordering, rollback, restart recovery, stale claims, conditional publication,
tenant isolation, internal and external timelines, and real clause workflows. Published clause
qualification compares whole-root document IDs, scalar and per-member gas, status, diagnostics and ordered events,
in addition to checking Payment → Order → Agreement propagation.

Owner-local bounded updates avoid restoring quiet sibling documents. Topology changes and unsupported
local edits conservatively use whole-graph execution. End-to-end AWS timings
also include signed admission, durable storage and scheduling; in-memory kernel numbers are not
presented as equivalent. Arbitrary changes to established cyclic ownership components, complete
MyOS API parity and production high availability remain unfinished.

The campaign report is in the sibling library at
[`../blue-java-better/docs/CANONICAL-CAMPAIGN-2026-11-08.md`](../blue-java-better/docs/CANONICAL-CAMPAIGN-2026-11-08.md).
Reproducible commands and measurement boundaries are in the preview guide. Earlier results and
experiments remain explicitly historical: [previous host guide](docs/HISTORICAL-HOST-README.md),
[earlier AWS report](docs/owner-aws-report-2026-11-08.md).

## Current published-clause AWS journey

```sh
./gradlew installDist qualificationJar
python3 scripts/qualify_clauses.py --orders 20
```

This bounded qualification creates a temporary worker in the configured test AWS stack, then
terminates it. The default uses a focused catalogue with the exact deployed Documents, Payment, ManualPayments,
Activation and Orders package identities, and
[the clause-only current Documents fixture](examples/clause-current/agreement.blue).
Each Order creates a Payment Instruction; an acknowledged receipt updates the Order and Agreement.
All embedded documents reuse the two participant Timelines. Independent whole-root replay checks
all submitted inputs after the journey. Shared immutable definitions and G1 are explicit defaults.
The report records source/catalogue hashes, runtime artifacts and resource settings.

Per-operation JFR is enabled for diagnosis. This focused catalogue excludes unrelated deployed packages and server Timeline/timer configuration.
These timings exclude HTTP and include profiler cost;
they are not a throughput benchmark. Use `--historical-clauses` only for the retained older catalogue
and IncrementalDocuments fixture, and `--private-definitions` to disable shared definitions.
`--audit ACCOUNT ROOT` replays an existing isolated `clause-qa-` history using the selected catalogue.
The native `OwnerScaleProbe` exercises owner execution with Contracts workflow fixtures; it does
not by itself qualify the published Documents clause at the same document count.

## Local published-clause growth check

```sh
./gradlew clauseGrowth -PgrowthCount=100 -PgrowthProfile=true
```

This explicit task creates children through the published IncrementalDocuments clause, submits
signed local Timeline inputs and waits for the exact Agreement receipt. It uses an in-memory
conditional store and never calls AWS. The ordinary suite runs three children, including cyclic
root–child event rounds, and compares complete native replay. Larger runs report correctness of
observed business values; they do not silently claim an exhaustive whole-root replay. Reports and
optional JFR recordings are written under ignored `build/`.
