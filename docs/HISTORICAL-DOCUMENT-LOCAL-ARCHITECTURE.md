# Document-local MyOS

A separate experiment, not a migration of the existing MyOS deployment. The runtime reuses
blue-java-better and a separately identified document-local protocol. No SQLite, global account
journal, canonical graph receipt, credential copy, or existing deployment modification.

## Chosen strategy

Each document has a tenant-scoped durable record, a version and an immutable index of embedding
links. Updating one payment executes that payment, commits its state and outbox atomically, then
notifies its order. The order's existing Embedded Node Channel runs, commits locally, and notifies
the agreement. Work follows the affected ancestor edges. Other orders are not loaded or executed.

DynamoDB conditional transactions fence concurrent writers and atomically publish state,
idempotency records, links and outbox. Official AWS SDK retries own transport retries. Duplicate
commands compare request identity; duplicate delivery is harmless. Outbox work is replayable after
process death. The durable store is authoritative; bounded session/content caches are disposable.

A parent with 10,000 children must not rewrite a 10,000-entry map. An immutable radix index stores
links in bounded pages. A link update rewrites only its index path; the parent's state holds the
new root pointer. Immutable pages can be staged before the conditional commit: unreachable pages
are invisible. Full document expansion visits the requested content and is deliberately separate
from processing. An expanded graph can be passed separately to the Language API for canonical identity calculation.
The host does not compute that graph-wide identity on the processing path.

Global atomicity is replaced by ordered per-document commits and eventual parent propagation.
Reads state their version/progress; benchmarks measure both local execution and durable end-to-end
completion. A parent updated by every child remains a serialization point. Batching can amortize
storage calls but cannot make arbitrary parent business rules parallel. Strongly connected graphs
require an atomic execution partition; the initial distributed protocol rejects cycles rather
than pretending asynchronous events reproduce canonical cyclic scheduling.

## Alternatives considered

- Canonical full-graph execution preserves existing proofs but retains graph-wide work.
- A plain per-parent JSON map removes proofs but still rewrites all sibling links.
- PostgreSQL with indexed edges and an outbox is reasonable. DynamoDB fits the existing AWS setup,
  conditional per-document publication and independent tenant partitions without a database host.
- SQS FIFO can schedule work, but cannot replace the atomic outbox or durable idempotency records.
  The initial host queries sharded durable work; adding a queue is a wake-up optimization.

## AWS boundary

The user selected the existing MyOS deployment credentials. Their verified account is 581571886327;
this experiment uses eu-west-1 and stack myos-better-20261105. One c7i.large host has no inbound ports
and is accessed through authenticated AWS Systems Manager. IAM grants only the experiment's table
and artifact prefixes, plus SSM. The table is encrypted and has point-in-time recovery; the S3 bucket
blocks public access. No public service authentication or production HA is claimed by this isolated
private deployment. Resources are retained for inspection; review date 2026-11-12. A cleanup runbook
must list retained data explicitly; expiry tags alone do not delete anything.

## References

- [DynamoDB transaction limits and isolation](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html)
- [SQS FIFO ordering and visibility](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/FIFO-queues-understanding-logic.html)
- [Caffeine design](https://github.com/ben-manes/caffeine/wiki/Design)

## Verified iteration checkpoint

The initial local host tests cover a complete three-level workflow, host recreation after a child
commit, duplicate commands, tenant isolation, two concurrent host instances, a conditional conflict,
and a response lost after a successful commit. A bounded session cache is fenced by stored document
versions; speculative sessions are discarded after an uncertain or failed publication. Its reuse
count bounds retained runtime history as well as its entry and estimated-byte limits.

Live AWS qualification found and corrected an empty DynamoDB expression-values map, a missing
ConditionCheckItem IAM action and mixed throttling/conflict cancellation handling. Local tests alone
did not expose those integration failures. The large run completed 20,000 orders with three
payments each: 80,001 durable documents. Every measured payment checked the root aggregate. See
[AWS performance](AWS-PERFORMANCE.md) for controlled comparisons and exact release identifiers.

Host commands have stable identifiers and request hashes. The local runtime commits that identifier
and a compact payload-identity commitment in its checkpoint while preserving the event visible to authored BEX. Original inputs are retained separately in the
host command audit record. Successful parent
delivery advances a pinned child link. Parent business rejection suspends the source, records a
failure and retains the outbox; it is inspectable and explicitly retryable. Retries do not roll back
the already committed child. Transient storage errors leave work durable for automatic recovery.

Large clause schemas can expand into a much larger BEX read view than their compact checkpoint. A
materialized projection is therefore a bounded read cache, not the authoritative execution state.
The candidate admission threshold is 64 KiB: once a document exceeds it, later writes persist only
its checkpoint and embedding metadata. Full reads reconstruct the projection; selected child reads
restore and select only the requested field. This trades explicit large-read cost for smaller writes
and history. Small counters and ordinary orders retain the existing projection cache. A new candidate cache is
now bounded during construction as well as by its final serialized size; oversized optional data
is discarded before the complete projection is built. Old rows with
stored projections remain readable. Performance qualification records both operation and read costs.


New root records carry a trusted `mayHaveParents=false` routing fact. Under this edition's fresh-child
model, such roots cannot acquire a parent later, so their dispatch version commits with their local
state and public-event history. They need no empty downstream delivery cycle. Child records and
unknown legacy records keep their outbox path. A future existing-document attachment API must
upgrade this routing fact atomically before installing an edge.

Public views have explicit resource bounds and stable child pagination. These bounds constrain API
expansion and do not redefine canonical gas or silently truncate the returned document.


Workflow embedding reads have a separate cumulative attempt budget with the same default numeric
bounds as API views: 256 child visits, 262,144 projected values/index entries, 4,194,304 UTF-16 code
units and embedding depth 64. Repeated reads count again; nested intermediate projections may also
consume budget. Configure MYOS_WORKFLOW_READ_DOCUMENTS, MYOS_WORKFLOW_READ_VALUES,
MYOS_WORKFLOW_READ_CHARACTERS and MYOS_WORKFLOW_READ_DEPTH explicitly when a deployment needs larger
bounds. Raising them requires a corresponding memory/concurrency qualification; they are not a heap
reservation. Ordinary local field reads do not walk or charge unrelated children.
API and child-read projections are bounded while the library constructs them. Workflow collection
reads also pass the remaining output allowance before constructing the local part for the host
callback. Literal Contracts data is counted without first exporting its entire tree. Output nesting
is capped at 256, separately from embedding depth. These limits bound returned structure; Language
verification, retained checkpoints and other runtime allocations still require memory capacity.

An external workflow limit returns HTTP 413 / WORKFLOW_READ_LIMIT_EXCEEDED with no committed command
receipt or state change. A propagation limit suspends that source with HOST_RESOURCE_LIMIT and an
auditable reason; increase capacity or change the parent workflow, then explicitly redrive.
Exact references in copied child values are supplied lazily from the pinned child checkpoints and
verified by the Language kernel. They survive parent checkpoint restoration without importing the
child's independent execution state. No supporting evidence from unrelated siblings is loaded.


Optional cache admission may also stop at the library's independent physical-work allowance. This
omits the cache; it does not veto already-valid execution. Only the optional read is covered by this
fallback. Mandatory initialization, checkpoint construction and durable publication still must pass,
and validation/provider failures are not swallowed. API and workflow reads continue to report their
resource refusals normally.


## Combining delivery metadata with parent publication

For the current fresh-tree protocol, a source cannot acquire a new parent after creation. A delivery
with exactly one parent can therefore publish that parent's business state and acknowledge the
source version in one conditional transaction. Source business state is unchanged. The acknowledgment
still deletes only the selected immutable outbox, advances the expected DISPATCH revision and either
checks or idles the observed WAKE revision. Clearing WAKE also checks the source head version.

The plan is local to one delivery attempt. A conditional conflict causes ordinary parent retry and
separate source completion; it does not reuse stale source fences. Existing receipts, rejection,
multi-parent/paginated fan-out, duplicate transaction keys and DynamoDB's100-action boundary retain
the separate path. An uncertain committed response is resolved through the parent receipt; stale
acknowledgment attempts cannot advance the source twice. Durable discovery recovers missing hints.
Adding an existing-document attachment API requires a topology fence before this optimization can
remain valid under concurrent parent additions. Distributed cycles remain unsupported.

`MYOS_COMBINE_ACKS=false` selects separate acknowledgment publication for rollback/comparison.
This is a host storage strategy, not a new Language or Contracts semantics edition. The private
health endpoint and benchmark result report the active policy. Probe session metrics describe only
the measured window; target enumeration/lookup remains outside timing and may warm index caches.
