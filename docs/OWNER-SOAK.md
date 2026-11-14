# Sustained owner qualification

An ordinary build does not run this workload. A small local smoke test is:

```sh
./gradlew ownerScaleProbe -PownerCounts=1 -PownerSamples=3 -PownerPauseMillis=10
```

For durable qualification, explicitly select `-PownerTable=YOUR_TEST_TABLE` and a unique
`-PownerAccount=YOUR_RUN_ID`. Run on a dedicated test worker with the intended AWS credentials,
matching region and heap. Record that environment and enforce an external execution deadline;
the Gradle task itself does not provision or terminate a worker.

`OwnerScaleProbe` accepts `-Dblue.probe.samples=720` and
`-Dblue.probe.pauseMillis=15000` for a roughly three-hour campaign on one retained JVM.
Select `-Dblue.probe.orders=1000` and `-Dblue.probe.rotateTargets=true` to distribute
operations across the existing Orders and Payments. The pause occurs outside both acquisition
and durable-processing measurements. The default pause is zero. Invalid or zero sample counts
fail before opening the store; there must be at least one measured input.

The equivalent Gradle properties are `ownerSamples`, `ownerPauseMillis`, `ownerCounts` and
`ownerRotate`. The `-D` options above are for launching the qualification JVM directly.

The probe retains inputs and observations for its final whole-native parity oracle, so process
heap includes that bounded audit evidence as well as host caches. It reports sample heap and GC
observations without forcing collection; these are not exact retained-heap or leak measurements.
Keep all raw samples, the final parity result, the JVM bound, the isolated account and artifact
hashes. A successful short campaign does not establish long-term production reliability.

For a shorter AWS size comparison, the maintained launcher provisions and terminates an isolated
worker. Select the public catalogue explicitly when matching the deployed preview:

```sh
python3 scripts/qualify_owners.py --orders 1,1000,10000 --samples 20 \
  --public-definitions --report-dir reports/owner-scale-unique-run
```

If any selected size exceeds 1,024 Orders, every size in that invocation uses the same larger
worker, 8 GiB JVM heap, 12 GiB process-memory limit and four-CPU quota. Smaller invocations use
1 GiB heap and two CPUs. Compare within a recorded environment; do not combine these timings
with a separate two-CPU run. The launcher records the catalogue mode and executable hashes.
Initialization, signed source acquisition and durable owner processing remain separate metrics.
