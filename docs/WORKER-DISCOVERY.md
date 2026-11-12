# Worker discovery: measure delivery as well as idle reads

The service's workers use durable outboxes and disposable local hints. The current `drain` path
periodically discovers remote work even under continuous local traffic. When no source completes,
it also tries every work shard. Shard reads and page cursors are coordinated, while conditional
publication and fresh row reads protect delivery. The work index is eventually consistent; a scan
returning no work is not a global completion proof.

Iteration 98 tried a distinct background `poll` path. One worker would perform a discovery pass per
250 ms, enqueue its results and let other workers process hints independently. The original explicit
drain remained available. Eight new tests and the two-host concurrency case covered shared scan
budgets, failed reads, stale rows, queue overflow, blocked discovery, pagination and fairness. The
candidate passed all 187 host tests, but its measured delivery latency was worse, so it was reverted.

Four separate JVMs ran baseline/candidate/candidate/baseline with eight actual `Server` workers and
the same agreement/order/payment fixture. After eight warmup payments and four seconds of settling,
each measured three idle seconds, four local payments and four payments from a separate `Core`
instance with no consumer hints. Each payment waited for the exact agreement total. Input delays,
fixture and completion condition were identical; this was a local MemoryStore comparison.

| Measurement | Baseline, two JVMs | Rejected candidate, two JVMs |
| --- | ---: | ---: |
| Idle work reads/second | 97.66 / 108.47 | 31.91 / 15.97 |
| Local payment completion, median | 11.61 / 11.76 ms | 16.86 / 19.92 ms |
| Unhinted payment completion, median | 35.14 / 108.08 ms | 296.62 / 284.82 ms |
| Unhinted delivery after leaf commit, median | 31.00 / 97.65 ms | 289.16 / 275.04 ms |

The new interval delays discovery; existing exponential worker backoff also affects when discovery
can run. Lower query counts did not justify that delay. Four payment samples per case are not a
latency distribution or throughput qualification, but the explicit delay and both candidate runs
justify rejecting this policy. Exact gas/version arrays and all final business totals matched. No
production runtime change or AWS deployment resulted from this experiment. The candidate sources,
build result and raw arrays are retained in ignored iteration evidence, outside the maintained code.

`WorkerDiscoveryProbe` remains as a separately packaged comparison tool. It runs real server workers,
records idle I/O and process CPU separately, checks every root total, and distinguishes hinted
delivery from work published through another host instance. Its remote producer is another `Core`
in the same JVM, not a separate machine. It does not change the completion condition to improve timing.

The AWS launcher is covered by hermetic shell-generation tests. It was subsequently run in
iteration126 for the read-batching comparison; see [the retained measurements](PERFORMANCE-PROPAGATION-READS-126.md). It compares baseline/candidate/candidate/baseline in four JVMs, pins both host JARs,
the tool and every common dependency, excludes alternate host JARs from the classpath, uses fresh
synthetic accounts, and restores the isolated main service on exit. After building the tool and
distribution, the explicit command is:

```bash
python3 scripts/run_worker_discovery.py --baseline-host /absolute/path/to/baseline-host.jar \
  --workers 8 --idle-seconds 5 --samples 8
```

Run it only with the existing isolated experiment's account configuration. An ordinary Gradle build
only compiles the tool. Follow the returned SSM command until terminal; an observation timeout does
not establish that a job stopped. Before keeping another policy, measure unhinted delivery and
backlog recovery as well as idle I/O. No polling policy alone removes the need for durable discovery.
