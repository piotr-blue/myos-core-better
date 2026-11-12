# Parent-delivery read batching

Iteration 126 examines storage round trips, preserving the existing scheduling policy and wire data.
A source wake-up, document head and dispatch acknowledgement used to require a separate GetItem and
BatchGetItem. The candidate reads all three together. Discovery reads at most 25 sources in a group
(at most 100 records, including legacy indexed outboxes), then executes the same ordered delivery and
conditional publication logic. Index values remain hints: only strong reads provide processing input.

A read batch is not a transactional snapshot. It may become stale before publication. Existing
source/parent revision fences, command receipts, lost-response recovery and monotonic wake revisions
remain necessary. Tests cover a child advancing after the read, a competing worker completing the
same work, blocked/missing current rows, batch failure and unchanged legacy delivery hashes.

The focused read-cost tests fail against the previous implementation, then pass with this candidate.
The existing baseline correctly refuses stale blocked work; the failed assertion there concerns
unnecessary separate reads, not a newly discovered business-correctness defect. All 358 host tests
and three hermetic launcher tests pass. Library runtime and package definitions are unchanged.

## Method

Four separate JVMs run baseline/candidate/candidate/baseline with eight actual Server workers.
The probe performs eight warm-up payments, waits four seconds, measures two idle seconds, and then
measures hinted and independently published payments. The producer is another Core in the same JVM,
with its own hints and no workers. Operations and observations call Core directly; actual Server
workers perform propagation. These timings exclude public HTTP/TLS and browser travel. Completion
means the expected agreement total, not just leaf commit.
The single-payment gas/version arrays are compared across all four variants; final durable
acknowledgements are checked for every payment and order.

A separate recovery workload creates an agreement with 64 orders and one payment per order. The
producer fully initializes the graph, commits 64 real payment operations and stops before delivery.
Fresh consumers discover the durable backlog without any producer hints. Recovery timing includes
worker startup and root observation, excludes setup and later validation, and checks every order total
and both levels' acknowledgements. The shared agreement serializes root updates. This is a bounded
recovery measurement, not a sustained throughput qualification or a cross-machine notification test.

## Local results

MemoryStore, four measured payments in each hinted/unhinted case. Run medians and backlog duration:

| Variant | Hinted payment | Unhinted payment | 64-order backlog |
| --- | ---: | ---: | ---: |
| Baseline | 17.30 ms | 36.67 ms | 136.89 ms |
| Candidate | 17.22 ms | 94.23 ms | 139.99 ms |
| Candidate | 18.00 ms | 26.45 ms | 146.42 ms |
| Baseline | 17.33 ms | 30.68 ms | 152.97 ms |

MemoryStore shows no clear speedup, as expected for a change intended to reduce network round trips.
The local backlog completes in roughly 137–153 ms across all four runs. These short runs are
functional evidence, not a latency distribution. AWS uses eight measured payments per case.

## AWS results and decision

The exact baseline/candidate/candidate/baseline campaign completed successfully on the existing
c7i.large instance with DynamoDB, eight workers, eight measured payments per case and 64 backlogged
orders. It restored the ordinary service and its exact runtime profile. No new AWS infrastructure
was created. Both variants produced final single-payment totals of 1008 and backlog totals of 2688;
all recorded gas/version arrays matched, and all payment/order acknowledgements reached CURRENT.

| Variant | Hinted median | Unhinted median | Backlog completion | Backlog read calls |
| --- | ---: | ---: | ---: | ---: |
| Baseline 1 | 162.26 ms | 201.70 ms | 2964.34 ms | 1007 |
| Candidate 1 | 164.28 ms | 217.98 ms | 3212.48 ms | 812 |
| Candidate 2 | 162.77 ms | 186.37 ms | 3185.67 ms | 803 |
| Baseline 2 | 171.58 ms | 206.18 ms | 3250.13 ms | 1008 |

Read calls here mean `AtomicStore.get` plus `getAll`, including the root observation loop. The
candidate average falls from 1007.5 to 807.5 calls (19.9%). SDK retries/partial batches can produce
additional HTTP requests. These counters do not measure billed read units, bytes or total AWS cost.
Both versions performed exactly 128 backlog commits with no conditional failures. Discovery reads
and source/parent publications are unchanged in design. Fewer read calls did not translate into a
clear completion improvement: the shared agreement still serializes its 64 durable updates. The two
candidate backlog durations lie within the baseline range; two runs cannot establish equivalence or
a precise regression bound. Single-payment sample ranges also overlap.

Decision: **KEEP** the small batching change for reduced read-call overhead with preserved behavior;
do not describe this as a latency or throughput speedup. Further work needs to isolate root delivery
and publication costs. The global work index, worker idle polling and shared/cyclic graph semantics
are unchanged. In particular, this iteration does not claim account-specific queue partitioning.

Baseline host SHA-256: `9687eeba3e566992da7e345de7ad91ebdc50fecdfea8ae05d195c109cc30f32a`.
Candidate host SHA-256: `b5d15ef9c9c41131a5520488217806ab42cc677c2261b152133c52f9c93b8f4b`.
Qualification tool: `9daf3690f88c5df917cc040782fbbc558a6aad83afd3d488f1ff78abbef57be6`.
Library runtime remains `7bcc67ca08ded9fca0f92c9ac13a6026731b035f7284b69be5425da159d54df5`.
Raw local/AWS samples, command status, source hashes and the retained diff are in manifest 126.

