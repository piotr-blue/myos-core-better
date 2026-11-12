# Durable timeline acquisition

`TimelineArchive` is the internal host boundary for verified source acquisition. It retains evidence
and content through the existing `AtomicStore`/DynamoDB interface. The internal
[source selector](TIMELINE-PROCESSING.md) connects acquired entries to execution and atomic receiver
progress. Neither component runs an automatic feeder or authorizes private reads.

An archive pins one account, Coordination package, provider and signed timeline. Acquisition takes
exact range/successor evidence plus optional separately supplied `LanguageRecord` values, including
cyclic owning-set proofs. Published records take precedence over newly offered records with the same
identity. Language still verifies resolved content; supplying a record does not authenticate it.

```java
// Internal host code: Repository and TimelineArchive are deliberately package-private.
TimelineArchive archive = new TimelineArchive(repository, account, coordination, provider, timeline);
TimelineArchive.Acquired acquired = archive.acquire(exactEvidenceJson, offeredRecords);
TimelineVerifier.Prefix verified = archive.prefix();
TimelineArchive.Batch retained = archive.history(acquired.position());
Language historicalContent = archive.content(acquired.position());
```

`Acquired` reports the committed acquisition position, evidence identity, exclusive completeness
bound and newly acquired original entry identities. Repeating an acquisition returns its original
receipt and position even when later evidence has advanced the archive. Acquisition position is not
an entry timestamp: one batch may contain several entries or only advance coverage.

## Publication and restart

1. Read the conditional source head and establish its verified prefix.
2. Verify the new evidence against that prefix and the pinned provider/timeline.
3. Export only content closures absent from the published index. Existing records use indexed
   lookup; the archive does not enumerate or copy the old content inventory.
4. Persist immutable content pages, their index and the batch. They are not published source state
   until the following transaction succeeds.
5. Atomically publish the next head, immutable history reference and evidence-idempotency receipt.

A lost commit response is resolved by retrying the same evidence. Simultaneous duplicate imports
return the committed receipt. Different competing proposals cannot both acquire one history
position; the rejected proposal is retained when its separate conditional write succeeds. A failed
retention attempt is reported as unconfirmed. This marker supports inspection; it is not a provider
equivocation verdict or an implemented source-halting policy.

An established importer checks its retained history anchor and verifies only newly committed batches
when another importer advances. An unchanged head reuses its opaque verified prefix. A fresh process
replays every retained batch, verifies signatures/content, and checks stored entry facts, bounds,
heads, successors and the batch chain. Stored numbers or hashes never manufacture a prefix. Missing,
rewritten or inconsistent history fails closed. There is no unchecked checkpoint restore API.

Recovery reads immutable history in windows of at most 32 positions, bounded by the head selected
at the start of the call. It prefetches each batch manifest and its first content chunk. Later chunks
remain demand-loaded and verified. A one-batch catch-up retains the ordinary single-read path.
Prefetching populates the existing bounded byte cache; it does not advance a verified prefix. The
whole pinned head must pass verification before the archive publishes its reusable prefix.

The DynamoDB transport deduplicates requested keys, uses strongly consistent reads and retries
unprocessed keys with bounded backoff. Missing pages, hash mismatches and unexpected keys fail the
operation rather than yielding partial recovery. These are per-key consistent reads, not a new
transactional snapshot: correctness relies on the immutable history and its pinned committed head.
See the [DynamoDB BatchGetItem contract](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchGetItem.html).

Each batch pins its own immutable content-index root. Position one cannot accidentally use content
first acquired at position two. Storage remains tenant-scoped, but that scope does not itself grant
a caller permission to read a private timeline.

## Storage and cost boundaries

Content envelopes use 64 KiB chunks, limited to 16 MiB per envelope. Manifest, chunk and reconstructed
content hashes are checked. These SHA-256 storage identities are not BlueIds. The shared immutable
radix index preserves recorded old embedding-page roots; no embedding-page migration is required.

Warm acquisition costs new content and affected index paths. A fresh restart remains proportional
to the number of acquisitions and can allocate substantially during replay. Content, history and
staged orphan pages have no garbage collector yet. The repository page cache is bounded; durable
history is not. A retained prefix alone does not replace durable evidence.

Iteration92 measurements are in the library iteration log and
`reports/overnight-2026-11-05/archive-locality-summary-92.json`. They use a JSON-boundary in-memory
store, actual signatures and sequential imports. Transport construction, AWS and business execution
are excluded. Cold reconstruction is reported separately.

`TimelineRecoveryProbe` is an explicit qualification tool, excluded from the service JAR. It creates
two signed histories (49 and 128 acquisitions), persists only public source definitions in its
fixture file, then replays those same histories with fresh repositories and archives. Each source
has one unmeasured warm-up followed by 1–5 samples; SDK/JVM warm-up is therefore separate from archive
cache state. Timing excludes fixture admission and creation, and is not a full service cold start.
Every replay checks the restored signed head, completeness bound and absent successor.

```bash
./gradlew qualificationJar installDist
python3 scripts/run_timeline_recovery.py --baseline-host /absolute/path/to/previous-host.jar --samples 3
```

The launcher pins both host binaries, the tool and every shared dependency. It creates one fresh
fixture with the baseline, then runs baseline/candidate/candidate/baseline against that same stored
history on the isolated stack. It takes the deployment lock, stops the main service during the
comparison and restores its exact release/profile on exit. Inspect the returned SSM command until
terminal; an observation timeout never authorizes starting a second campaign. Results retain each
sample, allocation count, storage counters, loaded artifact hashes and the common fixture hash.

## Remaining integration

The native feeder must derive required sources from the exact runtime view, merge original entries
by their full ordering key, and wait until every relevant source has an exclusive bound strictly
beyond the candidate timestamp. Historical permissions, lineage, access-control sources, blocked
dispositions and topology changes participate in that decision. The internal selector publishes
document effects and receiver progress atomically; the published-channel ingress derives required
sources from receiving declarations. Private authority requires the explicit
[historical-access policy](TIMELINE-PRIVATE-ACCESS.md); historical rebase remains unfinished.
An acquired signed entry alone authorizes none of these actions.

The normal service now accepts explicit signed evidence submission and one-step native execution
through [configured Timeline endpoints](TIMELINE-SERVICE.md). Opt-in public HTTPS acquisition and durable
scheduling are now available there, including bounded successor continuation and signed private reads.
The hosted provider exposes current grant enforcement and owner grant/revoke operations. Dynamic
source management, general policy rebase and compact consumer recovery remain unfinished.
An acquired signature alone does
not grant permission or close a partial source prefix.
