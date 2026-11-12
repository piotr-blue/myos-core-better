# Checkpoint restoration candidates — iteration 131

Decision: **REVERT both runtime approaches; KEEP the boundary tests and fixed-checkpoint probe**.
The previous iteration 130 established that fixed active sets remove the apparent stored-size penalty.
Its local profile identified checkpoint evidence admission within cold session restoration. This
iteration investigated that specific work without changing AWS or the user agreement.

The retained runtime source and JAR are byte-for-byte the prior implementation. There is no new
runtime optimization, deployment or production speedup claimed. The original release 128 console
remains available. This report keeps failed candidates separate from the code actually retained.

## Candidate 1: reference-only checkpoint records — rejected for compatibility

The first candidate emitted direct child references in each checkpoint record, carrying descendants
separately. BlueIds and snapshot identities stayed unchanged, but existing APIs observe inline versus
reference structure in literal Contracts projections and protected checkpoint fields. Existing
restoration tests failed with `INVALID_INTEGER`, and a new full-projection comparison differed.
Changing those semantics is not an acceptable serialization optimization. The entire candidate was
reverted before any deployment. Its source, failing tests and output are preserved in the archive.

## Candidate 2: reuse independently verified inline bodies — corrected, then rejected on value

The second approach retained the exact wire format. Within one batch, an earlier exact JSON response
can already have normalized a later response's inline body. Reuse required the complete incoming text
to equal that body's canonical encoding, with identical host limits. Installed content, source/YAML
modes and cyclic proofs remained on ordinary verification paths. A 4,096-entry temporary index bounded
extra references; no additional global cache was introduced.

Review and focused tests exposed three candidate-only defects before promotion:

- A canonical encoding can be larger/deeper and contain more values than earlier shorthand. Its own
  input bounds must still be checked. A new regression failed before the correction.
- Intervening responses can supply another inline/reference representation with the same BlueId.
  Reuse must replay normalization's descendant-before-parent publication order, including typed-list
  payloads. A mixed JSON/YAML regression reproduced the lost representation and then passed.
- Canonical Double rendering can change a numeric token's decimal scale. Scalar bounds, including
  string-encoded large Integers, must match ordinary normalization. The decimal-scale regression
  failed before correction; encoded-bound checks were compared against actual parsing/normalization.

These defects were in experimental candidates, not newly discovered defects in the deployed baseline.
The retained ordinary verifier already rejects the invalid cases and preserves the representations.
Intermediate faster measurements are retained for diagnosis and are **not accepted performance results**.

The fully corrected candidate passed all 2,349 library tests and 374 host tests, plus the fixed-state
and broader measurements below. It preserved exact checkpoint bytes, identities, gas, events and
projections. Nevertheless, its benefit was uneven and too small overall for the extra complexity.
It was reverted; the reusable API-boundary regressions remain in `ContractEvidenceTest`. The helper-only
encoded-accounting test was archived with the rejected helper implementation.

## Final candidate measurements

`CheckpointRestoreProbe seed <file>` uses an isolated memory store with one agreement, one order and
three payments. It records payment, order and agreement checkpoints after each of 32 real payments:
96 saved inputs. `run <file>` restores an independent session for every sample, verifies the full
projection, performs the next real workflow transition, checks the value increase and exports state.
The agreement/order receive the corresponding authored event; payment invokes its authored operation.

Four fresh JVMs use baseline/candidate/candidate/baseline order on exactly the same fixture. One pass
of 96 inputs warms the JVM and process caches, followed by three measured passes. Thus each variant
has 192 measured samples per document role. Restore, execution and export are timed separately; input
reading, projection verification, JSON serialization and assertions are outside those timers.
Current-thread allocations are measured directly. There is no storage, HTTP, browser or background
worker timing in this microbenchmark. A fresh session in a warmed JVM is not a cold-process startup.

| Role | Baseline restore median | Correct candidate restore median | Time change | Allocated bytes, baseline → candidate |
| --- | ---: | ---: | ---: | ---: |
| Payment | 0.885 ms | 0.688 ms | -22.2% | 1.990 → 1.746 MB |
| Order | 0.919 ms | 0.982 ms | +6.9% | 2.276 → 2.562 MB |
| Agreement | 0.996 ms | 0.949 ms | -4.7% | 2.374 → 2.429 MB |

Payment restoration improved about 22%, but order restoration slowed about 7% and allocated about 13%
more memory. Agreement restoration improved only about 5%, with slightly more allocation. All 1,536
transitions across the four forks (including warmups) match on exact checkpoint hash/size, snapshot
identity, gas, event hash and full projection hash. There is no wire-format or gas tradeoff hidden in
the measured candidate. The remaining tradeoff is implementation cost and uneven physical performance.

Separate reversed-order fresh JVM comparisons used 64 warmups/128 measured repeatable payments and
5 warmups/10 measured package-backed Hello host initializations per fork. Exact gas/final identity/state
checks passed. Combined median payment execution was 1.053 ms baseline versus 1.057 ms candidate;
Hello host creation was 105.72 versus 105.94 ms. Allocation was also essentially unchanged. These bounded
runs do not show a broader benefit, and their small timing differences do not establish regressions.

No AWS candidate qualification was run after the local rejection. In particular, the earlier 130
128/20,128-order AWS results must not be relabeled as evidence for this rejected candidate.

## Retained verification and next work

The retained library passes 2,348 tests after the runtime revert and removal of the helper-only test.
Host runtime/test sources and both runtime JARs match the previously qualified deployment, retaining
its 374-host-test evidence. The new probe is built against those exact JARs and rechecked after
promotion. All five new public-boundary regressions pass on the retained baseline. No Java test reads
from the sibling Python project, and no original Java/Python/package/MyOS source was changed.

The next investigation should avoid representation changes and validation bypasses. Exact checkpoint
serialization currently constructs temporary map/list trees before writing them. A direct writer
that produces byte-identical output is a concrete, lower-risk candidate; it still needs measured
benefit, strict tests and a keep/revert decision. Shared-parent durable contention remains separate.

Evidence: `reports/overnight-2026-11-05/manifest-131.json`. It pins the retained runtime, rejected
candidate, source ledgers, fixture, test reports, complete samples and controllers. Rejected code and
JARs are stored only under the report archive, never on the active application classpath.

Retained library: `7bcc67ca08ded9fca0f92c9ac13a6026731b035f7284b69be5425da159d54df5`.

Rejected fully corrected candidate: `747890b45181c443b095cb9b79f0e94b6937eed8496c20c2d84d29b1cd097bc5`.
