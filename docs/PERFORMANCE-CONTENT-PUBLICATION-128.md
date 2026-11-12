# Immutable embedding pages and document publication — iteration 128

Iteration 127 is the deployed baseline. The previous turn made progress: the corrected parent-cache
change was deployed, verified with AWS restart/browser/graph checks, and recorded in manifest 127.
This iteration tests removing the separate page-publication round trip from ordinary parent updates.

## Candidate

`AtomicStore.commitWithContent` allows a store to publish immutable pages before or together with
conditional records. `DynamoStore` adds small page sets to the existing document transaction. It
retains the page-first path when the combined request exceeds 100 items or the conservative 3.5 MB
host bound. It validates pages and duplicate keys before either path performs I/O. No persisted
format, execution edition, package identity or document gas calculation changes.

The existing document/source version conditions still decide publication. Content keys are derived
from their bytes; writing those same bytes again is safe. A page conflict can still cancel a
transaction and uses the existing bounded retry. The repository caches newly staged pages only
after successful publication. Failed requests leave no trusted cache entry; uncertain replies use
the existing durable command receipt and session rollback/recovery. Timeline content publication
continues to use its existing separate path.

AWS allows at most 100 distinct items and 4 MB per transaction. The candidate retains our tighter
payload margin. See [TransactWriteItems](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html).
Transactional page writes consume more capacity than the previous nontransactional page batches:
AWS performs a prepare and commit write per item. This is a latency/capacity tradeoff, not an I/O cost
reduction claim. Concurrent transactions writing identical pages can also introduce conflicts.
See [AWS transaction capacity and conflicts](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html).

## Correctness evidence

All 374 host tests pass. `DynamoContentPublicationTest` covers one-request publication, unchanged wire
keys and conditions, empty pages, item and byte fallback bounds, invalid pages/overlapping keys,
retryable conflicts, uncertain results, and fallback failure before reference publication.
`ContentPublicationTest` covers rejection without cache pollution and payment/parent lost-reply
recovery followed by cold restart. Existing propagation publication tests continue to cover source
advance, competing delivery, deduplication and combined acknowledgements. These focused tests have
no wall-clock assertions. The unchanged library retains its 2343-test evidence from iteration 121.

## Measurement method

Four fresh JVM forks alternate baseline/candidate/candidate/baseline with exact host hashes and an
unchanged qualification JAR/library. Eight real workers run the same operation fixture. Local runs
have four measured payments per hinted/unhinted case; AWS runs have eight. Both use eight warmups.
An additional 64-order graph commits one payment per order while consumers are stopped, then starts
fresh consumers and verifies every order total, the root total and durable acknowledgements.

The worker probe calls Core directly and observes the running workers; it excludes public HTTP and
browser travel. The unhinted producer is another Core in the same JVM, not a separate machine.
Backlog timing includes consumer startup/discovery and root observation. It excludes setup and the
subsequent complete validation reads. These small campaigns do not establish a throughput SLA.

`MeasuredStore` counts the combined publication as a commit. Its commit timing includes fallback
page writes when a store uses the default implementation. Separate `publishContent` counters therefore
cannot be interpreted as exact transport calls for the new API. Wire-shape tests establish the small
DynamoDB path's single request. The local MemoryStore uses the safe default page-first path.

## Local comparison

| Variant | Hinted median | Unhinted median | 64-order recovery |
| --- | ---: | ---: | ---: |

| Baseline | 11.43 ms | 82.88 ms | 140.62 ms |
| Candidate | 17.28 ms | 85.41 ms | 144.90 ms |
| Candidate | 11.26 ms | 128.21 ms | 136.79 ms |
| Baseline | 11.77 ms | 115.27 ms | 137.87 ms |

All local final totals are 672 and recovery totals are 2688; gas/version arrays match. The timing
ranges overlap, so no local speedup is claimed. The completed AWS results follow.

Baseline: `f338aebdd154c0a5fad4a8d433328c9df6b534dedb85832317752fde1dc946c4`.

Candidate: `1eab9c53bf85ce89edbd7843ac467e8b270bfc941672045cd294682dd63c3e86`.

Tool: `9daf3690f88c5df917cc040782fbbc558a6aad83afd3d488f1ff78abbef57be6`.

## AWS comparison and decision

The exact-artifact campaign completed successfully on the existing c7i.large/DynamoDB deployment,
with eight workers, and restored the ordinary service. Each fork passed with final total 1008 and
recovery total 2688. All measured gas/version arrays match. Each backlog makes 128 document commits,
with zero conditional/retryable conflicts in this sample.

| Variant | Hinted median | Unhinted median | 64-order recovery | Separate page-publication calls |
| --- | ---: | ---: | ---: | ---: |
| Baseline 1 | 166.20 ms | 183.40 ms | 3173.52 ms | 128 |
| Candidate 2 | 155.39 ms | 217.16 ms | 2847.17 ms | 0 |
| Candidate 3 | 138.59 ms | 164.81 ms | 2497.40 ms | 0 |
| Baseline 4 | 176.44 ms | 188.97 ms | 3326.18 ms | 128 |

Mean recovery falls from 3249.85 to 2672.28 ms (17.8% shorter). Both candidate recovery
samples are below both baseline samples. Hinted median calls are also lower; unhinted samples are
mixed and do not justify a general latency claim. No latency thresholds are added to tests.

Decision: **KEEP** for the bounded DynamoDB recovery improvement. The single combined request saves
an intermediate publication round trip. Transactional commit time itself rises because it includes
the pages; this is expected. Do not add concurrent worker phase totals as a per-payment breakdown.
The candidate does not establish a cost saving, sustained-throughput improvement or SLA. The extra
transactional page capacity and possible shared-page contention remain explicit tradeoffs.
The root still serializes updates, and cold index reads are not eliminated.

The unchanged library artifact is
`7bcc67ca08ded9fca0f92c9ac13a6026731b035f7284b69be5425da159d54df5`.

Deployment and qualification are complete. Release
`0347a23bcb93bc4a20ec4b46776ef5e359374efbe9d83642b2dacc6c470abc60`
passed health checks with the existing profile, provider identity and worker/cache settings.
The automatic-directory probe passed on DynamoDB, including a fresh-process restart and offline
arrival, with final count 84. Its two warmups/four samples measured 555.00 ms median through the
parent; this separate functional qualification is not a paired speedup measurement.

Live browser checks passed bootstrap, operation dialogs, payment/order/agreement propagation,
immutable History, Events, Timeline creation/append/content, reader grant/revoke/regrant and tenant
isolation. The fresh small QA graph recorded 6.03 ms runtime, 279 ms browser response and 583 ms until
all three updates were visible. The original user graph remains 512 documents (128 orders, 383 payments),
version 247 and paidTotal 4956, verified read-only. The ordinary console still uses host-links;
Timeline append does not automatically call a business operation.

The actual checkout's stale root deployment metadata is updated to this verified release, preserving
its old value in the evidence archive. Runtime sources and exact artifacts are pinned in manifest 128.
