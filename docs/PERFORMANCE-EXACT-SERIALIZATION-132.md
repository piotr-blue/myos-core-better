# Direct exact serialization — iteration 132

Decision: **KEEP for lower checkpoint export allocation and time**. This is not an end-to-end
latency or throughput improvement claim. The two rejected restoration candidates in iteration 131
remain reverted; this change bypasses neither verification nor input bounds.

## Change and semantic boundary

`LanguageCore.exactText` now delegates to a direct exact writer in `LanguageCodec`. Previously it
copied the entire immutable exact representation into temporary maps and lists, then sorted the
copied keys again while rendering JSON. Exact node fields already have canonical key order. The new
writer visits them directly and uses the existing scalar, numeric and Unicode rendering rules.

Inline values remain inline. References, including references that retain verified cyclic bodies,
remain references. Local cyclic edges retain `this#N`. Bare lists use JSON arrays; typed or otherwise
qualified lists retain their object fields. Integral Double values still have `.0` on the wire.
This changes no content identity, checkpoint format, package, execution profile, proof or gas rule.
No additional cache or retained state is introduced. The structural export API is unchanged.

## Verification and method

All **2,364 library tests and 374 host tests pass**, including strict compilation, formatting and
library Javadoc/JMH compilation. `LanguageEncodingTest` owns 16 cases covering exact scalar wire
categories, empty/typed lists, reference boundaries, canonical Unicode order/escaping, invalid
surrogates, and 320 fixed-seed nested values compared with the existing structural export renderer.
The full published Language corpus and Contracts evidence/restore suites remain part of the build.
No Java test reads the sibling Python repository.

The unchanged `CheckpointRestoreProbe` uses the same 96 checkpoints captured under the deployed
baseline after 32 payments. Each fresh JVM restores a fresh session for each input, runs the next
workflow transition, and exports its checkpoint. One full pass warms the JVM, then three passes are
measured: 192 samples per role and variant across baseline/candidate/candidate/baseline forks.
Every one of the 1,536 transitions, including warmups, matches exact checkpoint bytes, identity,
gas, event hash and full projection hash across variants. Per-thread allocation is measured directly.
Input loading, projection verification and final JSON serialization are outside these timers.
These local memory-store probes exclude storage, HTTP, browser travel and background propagation.

## Local results

| Role | Checkpoint export median | Time change | Export allocated bytes | Allocation change |
| --- | ---: | ---: | ---: | ---: |
| Payment | 0.247 → 0.222 ms | -10.3% | 474.9 → 330.6 kB | -30.4% |
| Order | 0.455 → 0.366 ms | -19.5% | 844.3 → 497.2 kB | -41.1% |
| Agreement | 0.394 → 0.349 ms | -11.5% | 786.7 → 545.7 kB | -30.6% |

The export savings appeared in both candidate forks. They do not imply the complete transition is
10–20% faster. Combined restore+execution+export sample medians were 1.949 → 1.988 ms for payment,
2.118 → 2.070 ms for order and 2.192 → 2.229 ms for agreement. Restoration and execution timings were
slightly higher in this fixed-checkpoint run, offsetting part or all of the export time gain.
The combined numbers are medians of per-sample sums, not sums of separate medians.

Separate reversed-order fresh-JVM runs used 64 warmups/128 measured repeatable payment executions and
5 warmups/10 measured package-backed Hello host initializations per fork. Exact gas, final identity
and state checks passed. Payment execution median was 1.038 → 1.031 ms; Hello creation was
104.09 → 102.72 ms. These small differences do not establish a broader speedup. Corresponding median
allocations were 878,596 → 875,748 bytes and 155,626,292 → 154,158,424 bytes.

This implementation is retained for a direct, deterministic reduction in serialization work, with
no new trust assumptions. It does not solve cold evidence verification, shared-parent transaction
contention, full-graph reads or cross-region browser latency. AWS rollout and functional verification
are recorded separately below; local measurements must not be presented as AWS timings.

Baseline library: `7bcc67ca08ded9fca0f92c9ac13a6026731b035f7284b69be5425da159d54df5`.

Candidate library: `1f096aa4fae9a0a0dc1799d6f1c1dbe8af9999143003ae034cf0d6e3da9ccf71`.

Evidence is pinned in `reports/overnight-2026-11-05/manifest-132.json` after promotion/qualification.


## AWS rollout and graph qualification

Release `318e73ce64c02ac68d0bff535d80ac825a2f2d5c8cd6674a5bb453df15053904` deployed successfully with the same provider identity,
execution profile, eight workers and cache bounds. The existing host binary is unchanged. Four fresh
JVMs exercised the existing synthetic graphs in 128/20,128/20,128/128-order order, each with the same
16 active orders, 16 warmups, 64 sequential and 64 concurrent payments. Real workers and DynamoDB
performed propagation; no manual drain ran during measurement. All 512 measured payments, 64 warmups
and 12 receipt retries passed their touched-value, root-total and durable-acknowledgement checks.

| Fork | Stored orders | Sequential payment-to-root median | 64-payment concurrent completion | Concurrent retryable commit failures |
| --- | ---: | ---: | ---: | ---: |
| 1 | 128 | 124.14 ms | 2.871 s | 32 |
| 2 | 20,128 | 115.64 ms | 2.868 s | 32 |
| 3 | 20,128 | 106.54 ms | 2.773 s | 29 |
| 4 | 128 | 111.72 ms | 2.772 s | 27 |

The larger stored population did not slow this fixed active set. Both populations had 31 measured
sequential session loads and zero capacity evictions; concurrent loads were 21–25. Shared-root
contention remains: concurrent calls retried 27–32 transactions, versus zero sequentially. These are
bounded direct-Core/worker results, excluding public HTTP and browser travel. They do not establish
sustained throughput or a paired before/after AWS improvement for this serializer.

Full order inventory/target lookup/baseline reads and final checks are outside the operation timers.
Inventory alone took 174–181 ms for 128 orders and 11.04–12.47 seconds for 20,128. A complete listing is
size-dependent; selected operation propagation is the measured path. The previous historical tree
inventory established three payments per order (80,513 total documents in the larger graph); this
run validates every touched chain, not a new complete payment inventory.

The service was restored and exact health/profile checks passed. A read-only inventory confirmed
the original user's 512-document graph remains at version 247, paidTotal 4956. Browser qualification
on separate QA documents passed bootstrap, operation dialogs, payment propagation, immutable History,
Events, Timeline creation/append/content, Sharing grant/revoke/regrant, native signed reader access
and cross-account refusal. The payment screenshot records **6.63 ms runtime, 248 ms browser response,
527 ms until all updates were observed**. This is one small QA graph sample, not the 128-order timing.
The ordinary Timeline screen still does not invoke business document operations automatically.

Evidence: `reports/overnight-2026-11-05/manifest-132.json` pins source ledgers, the exact runtime/tool
artifacts, test counts, full local/AWS samples, deployment and qualification commands, browser results
and screenshots. The original Java/Python/packages/MyOS source projects remain untouched.
