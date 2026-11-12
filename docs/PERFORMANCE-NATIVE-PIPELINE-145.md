# Native Timeline performance: iterations 137–147

The earlier millisecond document/kernel benchmarks do not establish the latency of the native
private Timeline console flow. That flow also publishes and acquires signed entries, closes
cross-source coverage, verifies historical access, routes inputs, publishes state to DynamoDB and
propagates child events. The observed 90-second failure was a real performance defect.

## Changes retained

- 137: reuse immutable language catalogue roots; route this provider's own Timeline HTTP requests
  in process, retaining reader authentication and the normal protocol.
- 138–139: recover acquired Timeline prefixes from protected, atomically published checkpoints;
  skip verified quiet history runs. Standalone archives still reverify history by default.
- 140: traverse shared Compute Definition branches once per read while charging every logical
  occurrence; keep insufficient-gas failure order. Block permanent read-budget failures instead of
  repeatedly retrying them. Test a second Order after the first Payment completes.
- 141: avoid waking idle receivers for quiet coverage alone; retain real-arrival notifications
  across concurrent fanout. Reuse bounded historical target sessions when publishing operations.
- 142: the host-link preview edition (`blue.document-local/3-preview`) accepts an admitted exact
  document for an empty argument pattern without walking its whole logical tree. This deliberately
  changes host-preview gas; canonical Contracts and older host editions retain their rules.
- 143: retain completed negative concreteness facts and share persistent completed validation facts
  in captured views. Do not share incomplete or temporary Timeline-entry authority. Cache only
  decoded immutable read-access policy data, never grants or permission decisions.
- 144: reject a configured-session template experiment. Its simple session-open median was
  1.436 ms versus 1.426 ms before the change. Keep opt-in CPU spans for input preparation,
  execution, embedded-document detachment and emitted events.
- 145: prepare up to 16 consecutive inapplicable source inputs against one frozen receiver and
  publish their dispositions in one transaction. Reuse lineage facts only within that frozen
  selection. Read each fanout page's receiver records with one strongly consistent batch request
  instead of individual requests.

- 146: reuse a verifier-issued entry's completed target-value checks during operation routing,
  only for that entry's exact message and only for the duration of inspection. Ordinary inspection
  after the scope closes still requires its own content. A 1,024-field target/33-input local test
  improved from a 21.029 ms warm median to 9.373 ms.
- 146 rejected experiment: ID-only operation addresses. The canonical Timeline verifier correctly
  rejects that proposed grammar. No new runtime edition, changed operation field, or partial
  protocol extension is included in the delivered code.
- 147: automatic readers request existing coverage or the durable minimum needed by a waiting
  receiver, using the protocol's existing `minimumCompleteBefore` field. New entries are still
  acquired, but quiet sources can reuse their signed checkpoint. Demands are scheduling hints;
  only verified provider evidence closes the receiver's required-source cuts. Manual acquisition
  retains its request for fresh coverage.

## Batching semantics

The batch retains every original input order key and channel assessment. It changes neither the
business document nor any channel execution checkpoint. The last cursor and all assessments are
committed atomically under the original source-progress revision and receiver version.

Selection stops before an applicable input, unknown target, incomplete coverage or unavailable
historical permission. A later preparation failure cannot erase the already established prefix;
that failure is encountered again by the next selection. Competing workers cannot both publish
the same batch. Restart resumes after the committed original occurrence.

`TIMELINE_INPUT#…` remains a publication history, not a guaranteed one-row-per-input table. For a
batch, `assessments.entries` contains every inapplicable original occurrence; the top-level `input`
is the last one. Consumed inputs retain their existing one-input checkpoint mapping.

Tests live under `TimelineIngressTest.BatchedNonparticipation`, with existing original-source,
private historical-access, ordering, concurrency and fanout suites retained.

## Measurements and limitations

The final iteration-145 local median was 10.106 ms. A controlled 33-entry signed-history test reduced receiver calls (including the final idle check)
from 34 to 4, and disposition transactions from 33 to 3. The initial batching-only local run had
warm medians of 17.344 ms before and 11.157 ms after. This uses an in-memory JSON-boundary store,
not DynamoDB; it measures history handling, not the complete user workflow.

The full AWS flow on iteration 143 still took seconds, despite passing correctness checks:

| Operation | Submit, ms | Observed state, ms | Engine, ms |
|---|---:|---:|---:|
| placeOrder | 1,647 | 14,701 | 4,479 |
| preparePayment | 1,489 | 15,441 | 2,616 |
| acceptPaymentInstruction | 1,512 | 8,560 | 355 |
| reportTransfer | 586 | 2,720 | 180 |
| acknowledgeTransfer, through Agreement | 625 | 3,245 | 299 |

Observed state time includes submission, network and polling. Engine time excludes publication,
source acquisition and parent propagation. These are different measurements and must not be
presented interchangeably. Runs append new orders/history and include cold-start effects, so
cross-iteration numbers alone are not controlled speedup measurements.

Batching still scans irrelevant history: it reduces transaction and preparation overhead but does
not make startup independent of history length. Real arrivals still fan out to shared-source
subscribers. Target-indexed durable delivery, observer/broadcast routing and efficient coverage
notifications remain necessary to remove this dependence. Large-graph native Timeline performance
is not qualified by the earlier synthetic document benchmark.

## AWS verification after iteration 145

All five steps completed on the same QA Agreement and shared participant timelines. The speed
requirement was still **not met**:

| Operation | Submit, ms | Observed state, ms | Engine, ms |
|---|---:|---:|---:|
| placeOrder | 1,859 | 19,478 | 3,214 |
| preparePayment | 1,854 | 12,272 | 2,153 |
| acceptPaymentInstruction | 1,493 | 4,142 | 452 |
| reportTransfer | 631 | 2,789 | 203 |
| acknowledgeTransfer, through Agreement | 486 | 3,351 | 275 |

The paid-event listeners took 272 ms in the Order and 472 ms in the Agreement. This run establishes
correctness on AWS, not a successful latency result. Its artifact was
`7dad525977ac1696c5cbce5c7706f86099773b25dda6232f236ce0e42601bdbf`.

## Quiet-source overhead

A ten-second idle sample on iteration 145 recorded **863 storage commit calls**, **158 source
acquisitions**, **184 fanout passes**, and **354 receiver preparation passes**, with no business
execution. This explains why reducing only BEX instruction time cannot fix the observed latency.

Iteration 147 requests the exact missing exclusive coverage bound. If a provider cannot yet close
a future bound, the reader falls back to acquiring currently available coverage so earlier inputs
can continue. The durable demand remains outstanding. The fallback does not claim that the future
bound was proven. Tests retain current private-reader authorization and revocation checks.
