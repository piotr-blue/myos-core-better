# Worker notification experiment — iteration 124

**Decision: revert both candidates; keep deployed release 123.** The experiment reduced idle
DynamoDB index reads, but did not improve the current console's normal payment path. No SQS dependency,
notification API or worker scheduling change is retained in the production source.

The original worker uses immediate local hints and durable index discovery. Eight otherwise idle
workers were issuing about 93–109 work-index queries per second in this bounded AWS probe. Two
alternatives were tested with real Server workers, DynamoDB transactions and the same
payment → order → agreement workflow:

1. DynamoDB Streams → EventBridge Pipe → SQS advisory wakeups, with slower index recovery.
2. The same recovery path plus a bounded asynchronous SQS publisher after a confirmed transaction.
   The document thread never waits for the notification network call. Failed/uncertain transactions
   send no immediate hint; Streams and the durable index recover their outboxes.

Messages contained only the table, document address and outbox revision. Queue receipt deletion
never acknowledged the business outbox. Duplicate/lost hints, overflow, crashes, unavailable queues,
partial batch failures and shutdown were covered locally. All 359 candidate host tests passed.

## AWS results

Each campaign ran polling / candidate / candidate / polling in four fresh JVMs on the same host,
with eight workers, eight warm-up payments, four local samples and four independent-producer samples
per JVM. The normal service was paused for isolation and restored after each campaign. These are
small functional/performance trials, not throughput or tail-latency qualifications.

| Candidate | Idle index queries/s, control → candidate | Same-worker completion, control → candidate | Independent-producer completion, control → candidate |
| --- | --- | --- | --- |
| Stream notifications | 92.7–93.3 → 4.0 | 160.9–178.7 → 178.6–183.2 ms | 239.2–250.9 → 402.0–452.2 ms |
| Immediate async notification + stream recovery | 98.3–109.3 → 4.0 | 162.3–171.2 → 189.3–201.8 ms | 252.4–270.4 → 206.1–237.6 ms |

Ranges are the two run medians for each mode, not statistical confidence intervals. Completion
includes durable payment publication and observing the expected agreement total. It is not the leaf
runtime alone. The independent producer has its own Core and no local hint queue shared with the
consumer. Both run in the same JVM in this probe; this is not a two-machine network qualification.

All runs produced the exact expected total 672 and matching leaf gas/version sequences. The immediate
publisher sent all 32 offered hints per candidate run without dropped or failed sends. The first
stream-only design clearly regressed independent-producer latency. The second reduced it, while
same-worker latency regressed in both runs. Savings in idle queries do not justify enabling that
tradeoff on the present single-host console path. More repetitions could refine these estimates;
this evidence does not establish a universal impossibility or a production SLA.

## What this teaches us

Idle scans are avoidable I/O, but reducing them alone is not proof of lower request latency. Adding
another managed transport also adds SDK work, connections and duplicate notifications. This experiment
does not isolate which of those caused the same-worker regression. A future iteration should first
measure those costs, including stale-hint reads, and keep local execution independent of remote
notification fanout. Durable outbox recovery and fairness under continuous local work must remain.

This was a scheduling experiment, not a change to BEX, Contracts, gas semantics, document identity,
parent business logic or the number of embedded documents. The library runtime is unchanged from121.
The existing 512-document user demonstration and the console remain on release123.

Raw samples, candidate source/artifact pins, comparison summaries, restoration checks and cleanup
records are collected by manifest124 under `reports/overnight-2026-11-05/`. The earlier business
measurements in [the payment walkthrough](PAYMENT-WALKTHROUGH.md) remain separately pinned to104;
they must not be relabeled as results of this experiment.

## Restoration checks

The restored runtime and qualification JARs match123 byte for byte; the342-test host check passes
with Gradle's matching cached test results. Browser checks pass for payment propagation, operation
dialogs, immutable History, Timeline creation/content and Sharing grant/revoke. The existing user
graph remains512 documents, version247 and paidTotal4956. The experiment's CloudFormation stack
is deleted, and the original table is active with its stream configuration restored. Only these
reports are promoted; the candidate runtime and SQS dependency are absent from shipping source.
