# Two-machine qualification

The retained deployment was exercised on two c7i.large machines in eu-west-1a and eu-west-1b,
sharing the isolated DynamoDB table. This is a bounded correctness and recovery qualification,
not a claim of production availability, region failover, or sustained throughput capacity.

Release: `68eed31031466dc6da2971af30d91fca79b85fb0dca8550ebdd0f2214b77df36`.
Run: `cluster-b8378a1751934cd5`. Primary: `i-0d892c8d2e30807a2`.
Temporary peer: `i-0d45080ddecbfca7b`, subsequently deleted with its CloudFormation stack.

## Concurrent commands

Four accounts each contained an agreement, two orders and four payments: 28 documents total.
Each machine submitted the same 128 commands, concurrently through its own loopback HTTP service.
The 256 submissions intentionally mixed duplicate command races with distinct commands against
the same documents. Both machines ran independent session caches and propagation workers.

Every duplicate returned exactly the same committed receipt. Every payment, order and agreement
total matched the expected effects of 128 unique commands. Both machines returned identical fully
propagated graph views. Reusing a command identity with a changed payload was rejected on both.

Submission phases lasted 3.235 and 3.223 seconds. Individual call medians were 161.51 and 185.51 ms.
These include contention and storage I/O, and include idempotent duplicate calls. They are not
single-document engine times or an estimate of unique-operation throughput under sustained load.

## Recovery on the other machine

Both worker services were stopped. A standalone process on the primary committed an agreement,
an order with three payments, and a payment operation, then exited. Its audit showed a pending
payment version and a root with zero initialized orders and zero payment total.

Only the peer's service was restarted. It discovered the durable work and advanced payment → order
→ agreement. The root reached version 4, initializedOrders 1 and paidTotal 42. Every level was checked.
The primary service was then restored and health-checked; the peer stack and its disk were deleted.
This demonstrates recovery without the producing process or machine's memory. It does not inject
a network partition, kill a process during a transaction, or qualify a prolonged AZ outage.

## Reproduction and safeguards

Build `./gradlew check installDist qualificationJar`, deploy a verified release, then run
`MYOS_AWS_PROPERTIES=/path/to/authorized.properties /usr/bin/python3 scripts/qualify_cluster.py`.
This explicitly creates a temporary paid EC2 peer, uses the experiment's restricted IAM profile and
no-ingress security group, and deletes that peer in its cleanup path. A three-hour shutdown timer
limits compute exposure if the controller disappears; manual stack deletion is still required in
that case. The existing primary is protected by a bounded deployment lock and restored on exit.
Reports and command IDs are written under `reports/cluster-<run>-*`.

The first launcher attempt was rejected: a template replacement accidentally changed `MYOS_TABLE`
and started its peer in memory mode. Both workloads were cancelled, the primary was restored, and
that temporary stack was deleted. The corrected launcher has a regression test for the exact
environment variable and requires `durable: true` in its health checks. No results from the rejected
attempt are counted as distributed-storage evidence.

Hermetic launcher checks need no credentials:
`/usr/bin/python3 -m unittest discover -s scripts -p test_qualification.py`.
