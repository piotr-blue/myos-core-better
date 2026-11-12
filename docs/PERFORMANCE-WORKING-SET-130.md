# Fixed active working sets and session restoration — iteration 130

The previous iteration 129 qualified the current worker loop on large stored graphs, but its two
populations had different active working sets. This iteration keeps the deployed runtime unchanged
and separates those variables. Decision: **KEEP the qualification tools and evidence**. There is no
new runtime speedup or deployment claimed here; the ordinary service remains release 128.

## Controlled AWS comparison

`GraphLoadProbe` now accepts an optional sixth argument, the number of active orders. Zero keeps the
previous full-population sampling. A positive value selects that many evenly spaced paths from the
complete stored order inventory, before timing. The launcher exposes `--active-orders` and requires
that the requested set fits both synthetic populations. The same seeded selection sequence then
has the same overlap pattern in both graphs. The actual document IDs and evolving stored values
remain different, so this is not bit-identical input or cross-fork gas-equality evidence.

Four fresh JVMs used small/large/large/small order, deployed runtime manifest and artifacts, eight
workers, eight concurrent callers, a 2 GiB maximum heap and the existing 256-entry/512 MiB/32-use
session policy. Each fork performed 16 sequential warmups, 64 sequential payments and 64 concurrent
payments. All four runs selected 16 distinct orders and 37 distinct payments in each measured phase.
The same 128-order and 20,128-order accounts were retained; no new large graph was created.

| Stored orders / run | Sequential runtime median | Sequential leaf response median | Sequential root completion median | Concurrent 64-payment completion | Concurrent batch rate |
| --- | ---: | ---: | ---: | ---: | ---: |
| 128 / 1 | 4.98 ms | 29.40 ms | 113.62 ms | 3.014 s | 21.24/s |
| 20,128 / 2 | 4.61 ms | 28.52 ms | 114.66 ms | 2.963 s | 21.60/s |
| 20,128 / 3 | 5.02 ms | 28.04 ms | 114.41 ms | 2.940 s | 21.77/s |
| 128 / 4 | 5.65 ms | 31.12 ms | 120.00 ms | 2.949 s | 21.71/s |

All 512 measured payments, 64 warmups and 12 durable receipt replays passed. Each phase independently
checks every selected payment/order delta, exact agreement total, source acknowledgement and duplicate
receipt. The root remains edition 3; sampled orders/payments remain edition 2. The signed SSM command
completed successfully and restored the exact ordinary host-links health profile. User-account graphs
were not selected or mutated by this campaign.

These samples show no additional per-operation penalty from storing 20,128 rather than 128 orders
when active-set size and overlap are fixed. They do not establish a sustained-load capacity or SLA.
Each sequential phase restored 31 sessions with zero capacity evictions; concurrent phases restored
18–26 with zero evictions. Contrast iteration 129's full-population large-graph concurrent phases:
130 restores and 71 evictions. Warm-cache behavior depends on the active set, not only stored size.

Reduced restoration did not make this concurrent workload faster than the earlier dispersed workload:
it also concentrates calls on fewer payments and parents. Concurrent phases now record 22–38
retryable conditional failures and 223–246 commit attempts, while all sequential phases have exactly
192 commits and zero retries. These are correctly recovered conflicts, not failed commands. The
agreement still serializes its durable updates. The evidence points to both cache misses and shared
parent contention as separate concerns; it does not attribute all latency to either one.

Every measured phase made zero content/contentAll calls after untimed inventory and baseline reads.
Full inventory enumeration itself took 166–176 ms small versus 11.36–12.74 seconds large. That is an
explicit whole-graph setup operation, excluded from every payment time. Target selection, baseline
reads and final verification are also excluded and warm caches. Per-call concurrent timings exclude
the executor queue; batch completion includes it and waits for the final root. These direct Core
calls exclude public HTTP/browser travel and admission. Overlapping worker timing sums cannot be
subtracted from batch latency. Cold lookup and end-to-end browser latency remain separate questions.

## Local restoration profile

Opt-in `-Dmyos.probe.recordingDir=/temporary/path` records only the measured local phases after target
selection and before verification. AWS use is rejected. Recording setup/teardown are outside elapsed
times; JFR overhead still affects the recorded workload. Environment, initial system-property and
system-process events are disabled and their zero event counts are checked. Raw recordings and raw
event exports remain temporary diagnostics; only aggregate method counts and recording hashes are
retained in the project evidence.

The bounded memory-only profile used 256 stored/active orders, 256 sequential and 256 concurrent
payments, eight workers/callers. All results and receipt checks passed. Full-depth stack inspection
finds `DocumentRuntime.restoreCheckpoint` in 36 of 114 sequential CPU samples and 23 of 89 concurrent
samples. Within those restoration samples, `ContractLanguage.addEvidence` appears in 24 and 15;
strict body certification appears in 9 and 5. These overlapping, small sampled counts identify
verified checkpoint evidence admission as a concrete next investigation; they are not a critical-path
breakdown or precise CPU percentages.

The default allocation view initially made `ProjectedData.copy` appear dominant. Its largest sample
carried a 7.37 GB weight; first samples on threads included allocation preceding this short recording.
That is insufficient evidence for a projection rewrite. No allocation percentages or measured total
allocation savings are claimed. Inspection uses full stacks rather than JFR print's five-frame default.

Any restoration optimization must preserve independent validation of supplied response bytes,
identity/limit binding, deterministic output/gas, isolation and bounded retention. Reusing a claimed
BlueId alone is not sufficient. Enlarging caches or bypassing validation is not part of this change.

## Validation and reproduction

All 26 launcher/control-script tests pass. Fresh 8-order (one active order), 256-order (16 active),
and mixed-edition 32-order (16 active) local runs pass. The all-active profiled case preserves default
selection. Gradle formatting/build succeeds; promoted artifacts are checked against the staged ones.
Only qualification tooling and documentation change, retaining release 128's 374 host tests and
library 121's 2343 tests. No runtime source change needs a new deployment/browser acceptance run.
An initial test command named two nonexistent test modules; that invocation failure is retained,
and the complete actual script discovery suite subsequently passed.

Run after building `qualificationJar installDist` and configuring the authorized AWS properties:

```bash
python3 scripts/run_graph_load.py \
  --small-account bench-fresh-128-iteration63 --small-orders 128 \
  --large-account bench-fresh-20000-iteration64 --large-orders 20128 \
  --payments 3 --operations 64 --clients 8 --active-orders 16
```

This explicit operator qualification temporarily stops the ordinary service under the deployment
lock, uses only existing `bench-*` accounts, and restores service on exit. Do not submit again while
a command is pending; inspect its saved command ID. It does mutate the selected synthetic fixtures.

Evidence is in `reports/overnight-2026-11-05/manifest-130.json` with per-fork raw results, command status,
local results, sanitized profile summary, source ledgers and exact artifact pins. The next engine
iteration should measure evidence admission independently and evaluate a bounded reuse strategy,
while keeping parent contention separate from cold restoration cost.
