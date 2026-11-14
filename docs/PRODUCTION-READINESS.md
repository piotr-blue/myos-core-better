# Readiness decisions, 14 November 2026

Source baseline: `c0b686e756bd9cb2b72fe6cb7762b9f727f8be86`. AWS baseline release:
`8181f2a8422cc37b56deb7c33f7d5e78e9f4b8089a57bb3c26d8001f3dd5cfeb`.
The attached review is a useful risk inventory, not a specification or permission to
change a signed document's meaning.

| Recommendation | Decision and scope |
| --- | --- |
| B1: cold-account setup | Implement shared, compact public catalogues before processing, with compatible readers and failure/race/isolation tests. See [design and rollout](PUBLIC-DEFINITIONS.md). |
| B2: dead/revoked providers | Preserve ordering safety. A local timeout must not discard a possible predecessor. Recorded exclusion/rebase needs an editioned protocol design and independent evidence. |
| B3: availability | Keep this isolated test deployment's capacity fixed for measurement. A multi-AZ production topology and failover qualification remain release work. |
| B4: observability | Add generated request IDs, bounded structured HTTP metadata, `/live`, and storage-aware `/ready`; keep existing JFR phase events and storage metrics. Full cross-worker distributed tracing and queue SLOs remain work. |
| B5: gates | Run checks on feature branches; add a Java API declaration guard, stronger compiler checks, adversarial library inputs and the cold-catalogue regressions. Report actual local/AWS results separately from unobserved GitHub checks. |
| B6: licensing/releases | The owner must choose a license. Do not invent a legal grant or call an experimental build a stable release. |
| B7: gas conformance | Preserve the installed semantic-gas implementation and package pins. Editioned specification and independent receipt/gas vectors remain required for a conformance claim. |
| B8: security review | Document trust boundaries and test isolation/failures. An independent assessment remains required. |
| B9: storage lifecycle | Keep existing history and public catalogue roots. Reclamation needs a reachability/retention protocol and tested restore procedure; no heuristic deletion of apparently unused rows. |
| B10: product surface | Preserve existing endpoints and SDK behavior. Keep the existing parity inventory visible; do not narrow the API to improve scores. |

## Deployment inputs are part of the release

The live test service had 41 package entries, while the repository's default console profile
had eight. `console-deployed` now preserves the live manifest and its immutable package
archives. `infra/runtime-catalogue.lock.json` records their SHA-256 values and source release.
The original example profile remains useful for development. A comparison that changes
profiles is not a controlled before/after measurement.

## Interpreting measurements

The account client measures a complete signed operation, including context reads, signing,
submission, network latency and waiting for stable publication. The independent owner probe
separates acquisition and engine/storage work. Compare the same metric, packages, sizes,
sample count, CPU/heap limits and worker configuration. Initialization is reported separately
from warm updates. Receipts, events and final business fields must agree before a latency
sample is accepted.

## Maintenance boundaries

Public class/module moves and a new large-collection language type require compatibility and
edition decisions. File length, map counts and branch percentages are review signals, not
automatic proof of good or bad architecture. Existing immutable storage, ownership and
transaction invariants remain the design anchors. Changes here target a demonstrated cause
and observable release gates; they do not constitute a Caffeine-equivalent maturity claim.

## Cold control-history finding

The first real fresh-account test found a second cause of stalled approval. The provider's
public control history exceeded `TimelinePull`'s 256 content-fetch allowance before its
normal eight-page checkpoint. The generic exception was classified as invalid evidence,
which stopped automatic acquisition after three failures. Existing accounts had already
retained that history and did not reproduce the problem.

Acquisition now closes a verified chunk when its fetch allowance is half consumed. An
oversized page retries from the same durable cut with a smaller page, sharing the original
wire allowance and deadline. No incomplete page becomes a checkpoint. Resource exhaustion
has an explicit availability classification; signature and continuity failures retain their
integrity classification. A single entry that cannot fit still fails within the bound and
requires an operator to resolve the resource constraint. Existing stopped imports require
an explicit retry through the supported timeline API after upgrading.

Timeline archives also reuse the immutable installed package maps rather than copying the
whole catalogue into every source. Source-specific records remain separate. This addresses
allocation observed while the live polling worker rebuilt evicted archive objects; it does
not introduce a new authority cache or change the retained record identities.
