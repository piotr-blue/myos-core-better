# Source selection and atomic execution

Internal `TimelineInputs` connects verified acquisition to `Core` execution for one receiver and an
**fixed required-source set**. Its original qualification uses signed timeline evidence and a separate
test-only HMAC channel. The newer [TimelineIngress integration](TIMELINE-INGRESS.md) derives required
sources from exact receiving declarations and executes through the published bindings. It currently
supports public sources and stable receiving lifetimes, not a complete automatic provider/HTTP service.
Source authentication and business authorization remain separate.

## One receiver step

1. Load source-progress revision and freeze a verified view of every required archive.
2. Find each next unconsumed entry. Empty batches advance a cursor without inventing execution.
   The cursor contains an acquisition position and entry offset.
3. Select the minimum `(timestamp, timeline BlueId, entry BlueId)`, with ASCII identity ordering.
   Every required source, including quiet ones, must establish `completeBefore > timestamp`.
4. Bind original identity/time to the receiver version. Supply content from its immutable acquisition
   view. Native business admission still runs.
5. Execute Contracts/BEX. Atomically publish document state, history, events, command receipt, source
   cursor and input disposition.

Later positive source coverage preserves an already closed cut. Receiver changes or competing progress
publication invalidate the attempt and require reselection. Lost commit responses use durable receipts.

`READY` means the candidate is closed across configured sources. `WAITING` identifies insufficient
bounds. `IDLE` means no acquired candidate remains; it does not certify completion through arbitrary T.
`CONSUMED` requires the receiver write in the same transaction. `INAPPLICABLE` records a nonexecuted
occurrence and checks receiver version. `COVERAGE` skips only empty/exhausted positions, never a waiting
entry. Applicability and skip authorization belong to the unfinished protocol-specific feeder.

Unverified input cannot reserve a command ID or advance progress. Verified business failure retains
its failure receipt but leaves the occurrence pending. Resolving it needs a deliberate feeder policy.

## Validation reuse and measurements

The first implementation had good medians but reopened predecessor history every 32-use session
renewal. The fix uses the opaque `TimelineVerifier.Entry`'s detached validated header through
`DocumentRuntime.Session.admit(command, entry, reader, evidence)`. This fact lasts for one invocation.
Identity/time remain original; native authorization still runs and message content is verified on
demand. No wire record constructs an Entry or installs checkpoint authority.

The archive retains at most 256 recent entry headers, not bodies or predecessor history. This is an
entry-count bound, not a byte-weighted memory budget. Eviction falls back to ordinary validation.
Cold restore still verifies all retained evidence. Older backlog entries outside this window can
still reopen predecessor validation and need further work.

Four alternating baseline/candidate JVMs reverse size order for pair two. Every historical entry is
signed, acquired and executed. After each size, 16 sequential warmups and 16 samples read the original
message amount in BEX and update the document. Timed calls include native admission, BEX, checkpoint
loading/export and atomic JSON-boundary MemoryStore publication. Acquisition, construction/signing,
selection and readback are outside the call; acquisition/selection are reported separately. Each
window contains a measured session renewal.

| Prior entries | Renewal before, two JVMs | Renewal after, two JVMs | Allocation before | Allocation after |
| --- | --- | --- | --- | --- |
| 1 | 14.55 / 5.00 ms | 8.28 / 1.95 ms | 15.48 / 12.39 MB | 5.68 / 3.89 MB |
| 128 | 18.08 / 15.20 ms | 3.06 / 1.88 ms | 51.02 / 54.05 MB | 4.77 / 3.75 MB |
| 1,024 | 84.80 / 93.55 ms | 2.13 / 2.30 ms | 392.79 / 390.26 MB | 5.20 / 5.21 MB |

Candidate median calls range 1.20–3.51 ms; the first JVM group remains colder. Ordinary-call allocation
sometimes increases: at 1,024 entries the median rises from about 2.13 MB to 2.94 MB while eliminating
the large renewal allocation. Gas arrays, checkpoint identities and sizes match every paired run.
Final checkpoints remain about 14.7 KB. This is local qualification, not AWS latency, throughput or
published native-channel conformance. Raw evidence is in `source-reuse-summary-93.json` and its arrays.

## Remaining integration and test ownership

The native ingress derives current declaration sources and uses established target lineage. Historical
embedding obligations remain unfinished. Public access-control dependencies and private permission
selection are available through the opt-in [historical-access policy](TIMELINE-PRIVATE-ACCESS.md),
with transport/scheduling described in the service guide. Source-set and receiving-lifetime changes are rejected
pending explicit historical rebase. The new native path retains original-order mappings beside local
command checkpoints; older records need migration. The host is not a protocol-complete receiving-history service.
The header window does not solve cold recovery
or arbitrary old backlogs. Distributed sharing/cycles and original MyOS integration remain separate.

`TimelineInputsTest` owns ordering, closure, offsets and conditional dispositions.
`TimelineExecutionTest` owns actual BEX, separate admission, original-message reads, atomic publication,
lost responses, competing receivers, restart and cache eviction. Public library tests remove old
predecessors and compare exact gas/checkpoints with full-history execution.

`DocumentLineageTest` owns the new atomic [document identity index](DOCUMENT-LINEAGE.md), including
coverage gaps, account/version bounds and constant-read historical membership. This supplies a
prerequisite used by native target routing. `TimelineIngressTest` owns the real published-binding path;
`TimelineExecutionTest` retains the separate test-only native channel for generic ingress regression.
