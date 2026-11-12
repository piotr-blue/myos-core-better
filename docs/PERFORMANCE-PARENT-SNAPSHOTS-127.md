# Cached parent snapshots

Iteration 127 tests reuse of the host's existing bounded speculative head cache for parent delivery.
Direct operations already use this cache. Parent delivery previously bypassed it and fetched STATE
on every update while holding the parent lock, even after the same host had just published that state.

The candidate also makes child-version gaps refresh their parent snapshot before waiting. Naively
turning on caching without this fallback reproduced a stalled second payment: another host had
already delivered the first update, while the cached parent still pointed at the earlier child
version. The focused failure is retained. Conditional publication, including the IGNORED branch,
still fences the parent version. A peer's unrelated edit or newly attached child cannot be overwritten
or discarded using a stale cached view. No storage schema, public API, runtime edition or package pin
changes are involved.

All 364 host tests pass. The new cases own warm read cost, unrelated peer updates, a cached missing
link, a cached version gap, cold restart, and a stale workflow read-limit failure. The baseline already handles these semantic situations
using fresh reads; its failed new assertions concern read elimination and demonstrating speculative
conflicts, not baseline business defects. The deliberate naive-cache variant fails the gap case;
the retained candidate adds the required refresh.

The first candidate was measured but was not promoted: a subsequent regression showed that a stale
cached workflow could exceed a host read limit even after a peer amended it to a legal bounded
workflow. Parent processing exceptions now refresh the observed parent version and retry when it
has changed. This avoids incorrectly suspending the committed child; an unchanged invalid workflow
still fails normally. Both the failing test and the corrected result are retained. The final binary
is measured again, separately from the first candidate.

## Method and local evidence

The same separately packaged WorkerDiscoveryProbe from126 runs four JVMs in baseline/candidate/
candidate/baseline order, with eight Server workers. Calls and observations use Core directly, so
public HTTP/TLS and browser network travel are excluded. Eight warmups precede the measured
single-payment cases; local runs use four samples per case and AWS uses eight. The independent
producer is another Core in the same JVM, with no consumer hints. This is not a cross-machine test.

Each JVM also constructs64 orders with one payment each, commits all64 payments before starting
fresh consumers, and measures recovery to the exact agreement total. It checks all order totals
and source acknowledgements. Setup and subsequent verification reads are excluded from that duration.

First-candidate local measurements (not the deployed/retained binary):

| Variant | Hinted median | Unhinted median | 64-order recovery |
| --- | ---: | ---: | ---: |
| Baseline | 17.28 ms | 62.49 ms | 130.26 ms |
| Candidate | 17.62 ms | 102.15 ms | 155.67 ms |
| Candidate | 17.41 ms | 100.23 ms | 137.89 ms |
| Baseline | 17.72 ms | 88.11 ms | 127.81 ms |

MemoryStore recovery was slower in both candidate runs. The cache stores serialized bytes, whereas
MemoryStore returns existing object maps; cached access still decodes JSON. DynamoDB already decodes
a transported row, so the intended benefit there is avoiding a serialized network read under the
parent lock. The local result alone does not justify promotion. The final AWS comparison is recorded below.


Final-candidate local measurements, after the exception freshness fix:

| Variant | Hinted median | Unhinted median | 64-order recovery |
| --- | ---: | ---: | ---: |
| Baseline | 16.45 ms | 111.53 ms | 146.30 ms |
| Final candidate | 16.59 ms | 99.45 ms | 140.69 ms |
| Final candidate | 17.18 ms | 109.19 ms | 150.28 ms |
| Baseline | 16.08 ms | 104.39 ms | 138.68 ms |

The final local ranges overlap; no MemoryStore speedup is claimed.


## Corrected candidate on AWS

The repeated baseline/candidate/candidate/baseline campaign completed successfully, restoring the
ordinary service. It used the existing c7i.large instance, DynamoDB and eight workers. Eight measured
payments per hinted/unhinted case follow eight warmups. Every single-payment gas/version array
matches, with final totals1008. All64 order totals and payment/order acknowledgements match, with
backlog total2688. Both variants issue128 backlog commits with zero conditional failures.

| Variant | Hinted median | Unhinted median | 64-order recovery | Backlog get/getAll calls |
| --- | ---: | ---: | ---: | ---: |
| Baseline 1 | 162.48 ms | 191.29 ms | 2939.61 ms | 818 |
| Candidate 1 | 142.68 ms | 176.81 ms | 2914.28 ms | 724 |
| Candidate 2 | 154.29 ms | 196.49 ms | 3006.50 ms | 722 |
| Baseline 2 | 149.05 ms | 191.95 ms | 3024.02 ms | 807 |

Average measured read calls fall from812.5 to723 (11.0%). These are AtomicStore calls, not billed
read units or exact HTTP requests; SDK partial responses/retries can add transport requests. Hinted
medians and unhinted medians are modestly lower on average, but ranges overlap. Backlog completion
is essentially unchanged in these small samples. No sustained-throughput improvement or SLA is claimed.
The shared parent still serializes durable updates. The cache remains bounded and speculative.

Decision: **KEEP the corrected candidate** for avoiding redundant parent reads. Reject the first
candidate's incomplete failure handling. The first campaign remains evidence about that earlier
binary, not a measurement of the final implementation. Ordinary memory-backed execution has no
proven speedup; direct library execution is unchanged.

Baseline host: `b5d15ef9c9c41131a5520488217806ab42cc677c2261b152133c52f9c93b8f4b`.
Corrected candidate: `f338aebdd154c0a5fad4a8d433328c9df6b534dedb85832317752fde1dc946c4`.
Unchanged tool: `9daf3690f88c5df917cc040782fbbc558a6aad83afd3d488f1ff78abbef57be6`.
Unchanged library: `7bcc67ca08ded9fca0f92c9ac13a6026731b035f7284b69be5425da159d54df5`.

## Next measured opportunity

Embedding-link changes stage immutable index pages. `Repository.Blobs.flush` writes those pages
before the conditional document transaction, so each such update has two publication requests.
In the retained126 candidate backlog run,128 publishContent calls accumulated1175ms across workers,
versus2832ms in128 commits. These concurrent totals must not be summed into end-to-end latency.
A later iteration can test combining small immutable-page publications with the document transaction,
while retaining bounded fallbacks, immutable identities and lost-response recovery. That optimization
has not been implemented or qualified here.

Deployment and qualification are complete. Release
`694e90e5da55dbd5319c29917b5c9751ff24c9f826013f68284beeaa15476954`
passed health checks with the existing host-links profile, provider identity and worker/cache capacity.
The signed automatic-directory probe passed on DynamoDB, including a fresh-process restart and an
offline arrival, with expected final count 84. Its two warmups/four samples measured 627.29 ms
median through the parent; this separate functional run is not a paired speedup result.

Live browser verification passed bootstrap, operations, payment/order/agreement propagation,
immutable History, Events, Timeline creation/append/content, reader grant/revoke/regrant and account
isolation. The fresh small QA graph recorded 7.01 ms runtime, 273 ms browser response and 575 ms until
all three updates were visible. The original user graph remains 512 documents (128 orders, 383 payments),
version 247, paidTotal 4956, verified read-only. Exact evidence and source pins are in manifest 127.
