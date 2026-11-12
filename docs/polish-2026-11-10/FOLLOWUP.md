# Follow-up: startup locality and preview acceptance

The user resumed the work after the initial two-hour pass. This follow-up began at 08:49 UTC on
10 November 2026 (15:49 Bangkok). It builds on the earlier qualified source; it is a distinct
measurement series. The original deployment chat released its exclusive AWS window before this work.

## Retained library changes

- **Digest without full snapshot materialization.** A snapshot previously called `wire()` even when
  only its identity was needed. That eagerly copied/froze the complete member and edge record before
  hashing already available immutable fragments. The digest now uses those exact fragments directly.
  Wire and identity caches each validate the complete input version, including mutable runtime
  metadata. Tests compare independently encoded wire hashes after every relevant state change and
  after copy/fork; canonical byte commitments are unchanged.
- **Reachability only when structure changes.** Finishing an ordinary member turn no longer constructs
  a graph helper and computes a full membership difference. The engine checks immutable topology
  identity first. Genuine attachment/membership changes retain the reachability comparison and
  activation updates. Initialization, cyclic topology and transaction tests remain exact.
- **Lazy frozen owner aliases.** The BlueId-to-member lookup used when attaching content now captures
  an immutable map root and reachability set. It materializes only when an attachment actually asks
  for an owner. It sees exactly the historical state captured at refresh, not whatever happens to be
  current later. Tests cover deferred reads, detach/reattach, changed identities and speculative-copy
  isolation. This is invocation-local bookkeeping, not a resident user-document cache.

These changes preserve the shipping gas policy, public document forms, event identities and exact
same-boundary receipts. They add no authored type or alternate execution model.

## Matched startup measurements

Three alternating before/after runs used fresh JVMs, JDK 25, the same machine and fixtures, no profiler
and fresh SQLite stores. Within each run, 10 and then 1,000 Orders were created; each Order has one
Payment and emits an initialization event consumed by the Agreement. Packages are shared within a
run. The initialization timer includes native preparation/execution and durable publication, but
excludes opening the application and constructing the authored source. The native timer is a subset.
Each subsequent Payment opens the application again; the operation timer runs from submission
through the committed Agreement. Whole-native result comparison is outside the measured interval.

| Orders | Measure | Before follow-up | After follow-up |
| ---: | --- | ---: | ---: |
| 10 | Complete initialization median |225.55 ms|227.00 ms|
| 1,000 | Complete initialization median |12,697.99 ms|8,741.51 ms|
| 1,000 | Native initialization median |9,456.93 ms|5,525.04 ms|
| 10 | Payment propagation median, 9 samples |40.37 ms|40.48 ms|
| 1,000 | Payment propagation median, 9 samples |36.14 ms|36.37 ms|

The large initialization improves **31.2% end to end** and **41.6% in its native portion**. Static
Payment differences are noise-level. All 36 Payment inputs passed the uncached whole-native document,
status, gas and ordered-event comparisons. [Raw runs and method](startup-comparison.json).

A new seven-sample interleaved growth run gives 43.17/687.53 ms median for adding an Order at
10/1,000 existing Orders, and 26.64/37.45 ms for Payment propagation. This is a separate run from the
original matched growth comparison, not an additional claimed growth speedup. Every input passes
semantic replay. [Raw growth samples](growth-followup.json), [186-input rooted replay](rooted-followup.json).

## Larger initialization attempts and remaining work

Before this follow-up, a fully expanded 10,000-Order fixture was rejected by the Language input-value
budget before execution. A second fixture admitted/stored each child separately and used BlueId
references in the root, without raising any production limits. It prepared all 10,000 child sources,
then exceeded the six-minute task bound before completing initialization. No successful startup or
operation time is claimed for it. The temporary qualification source was restored. Those attempts
were not repeated after the startup improvements. [Recorded outcomes](large-input-attempts.json).

The current initial-closure path still derives new private member keys from the growing snapshot
when no invocation meter exists. Snapshot identity therefore still consumes the canonical bytes of
preceding members for successive births. Avoiding wire-object allocation reduces the cost, but does
not remove that asymptotic dependency. Ordinary dynamic births within semantic execution already
use creating owner/path/lifetime/value. Any alignment of initial member allocation with that scheme
must explicitly qualify historical member identities, shared references, imported histories, event
occurrence identity and replay; it should not be hidden inside an allocation optimization.

The conservative structural-update fallback still restores all metadata. A native topology delta
remains the main architectural follow-up. Static owner-local Payment results do not establish
constant-time structural addition or large bulk startup.

## Documentation and release checks

The operations guide now points to the current canonical preview and preserves its verified provider,
reader, webhook, worker and runtime settings. It distinguishes source qualification, submitted
deployment and confirmed running release. Private QA credentials, databases, JFR traces, dependencies
and generated build trees remain outside source publication.

The completed host checks and AWS acceptance are recorded below. The original ff18 benchmark
remains separate: at 1,000 children its update median was 2.238 seconds and its last 50 additions had
12.441 seconds median; Payment growth stopped at the configured threshold after 150 creations.
That campaign reported 1,444 distinct APPLIED operations and a cyclic-birth failure. It is evidence
for the previous pinned release, not this candidate. Total service full-GC count was two (1.245 seconds),
not zero; earlier zero values covered individual probe intervals.

## Confirmed candidate deployment

Release `66d9d21516b6c2c1916d48a501a79860d33fb003b1cc0c961c70487f54cf9497` passed deployment and service health
checks on the same canonical preview. The existing table, runtime manifest, provider/reader/webhook
custody and two-worker configuration remain in use. AWS runs Corretto 25.0.5; local qualification
used JDK 25.0.3. [Deployment record](deployment-followup.json).

All final Java gates pass: 2,513 library, 99 local-host and 855 AWS-host tests. The local host also
passes the 186-input rooted replay. The console rebuilt and its source/asset manifest verifies.
All three projects' final JARs/distributions reproduce byte for byte on rebuild.
[Verification and artifact hashes](verification-followup.json),
[archive reproducibility](archive-reproducibility-followup.json).

Repository hygiene also excludes the library's generated `reports/` tree (679 existing generated
files), JVM class files and local profiler dumps from future source publication. Existing evidence
is retained locally. Two unreferenced compiled probe classes in the AWS host root are moved under
ignored `build/cleanup/`; source, fixtures and audit reports are retained.

## Live acceptance results

All tests used fresh QA documents on the confirmed candidate. No old benchmark graph was resumed
or rewritten. The public endpoint remained https://d2buikczdox1rt.cloudfront.net.

| Workload | Correctness | Measured boundary | Measurement |
| --- | --- | --- | ---: |
| Minimal Payment→Order→Agreement, 10 Orders | 3/3 inputs and all totals correct | HTTPS submission through exact root receipt, median |1,540.99 ms|
| Same workload, 100 Orders | 3/3 inputs and all totals correct | Same boundary, median |1,535.06 ms|
| Minimal fixture, 10/100 Orders | Root gas 124 on every call | Root recorded execution, median |27.84 / 25.91 ms|
| Published Agreement/Order/Payment | Two Orders, two Payments, Agreement total 400 | Full ordinary operation journey |PASS|
| Published cyclic Documents clause | Creation and five finite root–child pulse rounds | Exact receipt, back edge and immutable initial history |PASS|
| Published counter growth |10 births, 6 child updates,10 exact final membership paths | Child update through exact parent receipt, median |1,553.67 ms|

The minimal fixture's source initialization takes 1,704.79 ms at 10 Orders and 4,093.69 ms at 100.
These are single initialization samples, not a distribution. Root execution is only one phase;
it must not be presented as full service latency. HTTPS measurements include client round trips,
polling, signed admission, scheduling and durable publication. No new 1,000-child AWS campaign was
run in this bounded acceptance; the prior1,000-child results belong to ff18.

The published-clause test also verifies Cognito login, anonymous rejection, cross-origin write
rejection, cross-account isolation, bootstrap and operation idempotency, two shared participant
timelines, retained events, immutable initial history, and API-key creation/use/revocation.
The formerly failing cyclic birth now completes on AWS with the exact same fixture source hash.
Its later pulse rounds take 1.18–1.63s through the client-observed receipt; server input-to-publication
is 535–800ms. A cyclic unit intentionally executes its participating documents together.

[Minimal propagation samples](aws-owner-followup.json),
[published clauses and API checks](aws-clauses-followup.json),
[cycle receipts and counters](aws-cycle-followup.json),
[counter creation and probe samples](aws-growth-followup.json).

These results qualify the candidate as a working improvement, not as 9+/10 across all dimensions.
The library's public API and test discipline are around 9; qualified semantics are 8.5. Overall I would
currently put the library at 8.5, the local host at 8, and the AWS host at 7.5. The deployed correction
and reproducible evidence improve readiness, but sustained large structural growth, established
SCC migration, full MyOS compatibility and production availability still need qualification.
The next performance work should target native structural deltas and the growing initial-member
commitment boundary, then measure server storage/scheduler phases independently of client polling.
