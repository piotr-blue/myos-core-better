# MyOS Core dashboard parity

## Current status — iterations 86–87 deployed and accepted; parity incomplete

The original post-login UI is reused at https://d2ya9daeiur7nz.cloudfront.net, with Better login/signup.
Full API parity is **not achieved**: 93 operations are partial, 23 pending and none claimed equivalent.
Main gaps include identity/email/ownership directory, invitation delivery configuration, account
lookup, selected-read access, Agent OAuth/execution, mandate/access automation, import recovery,
advanced access, timers, administration, billing, historical runtimes and distributed restrictions.

Iterations 86–87 are deployed and healthy. The original pending request completed in iteration 83 and
must not be replayed. The accepted iteration87 warm AWS increments took
0.836–0.997s inside the engine and 2.607–4.082s until the root was observed. Cold startup still took
35.699s, including 28.873s of session loading/restoration and 492ms initial catalogue migration.
The operation-list path took 706/648ms end-to-end with no session activity, restore or storage writes.
These are bounded samples, not proof of flat scaling or completion of the millisecond target.

Iteration 86 is source-promoted and fully qualified locally: operation descriptions now publish
atomically with the canonical result, allowing selected reads without execution restoration. Older
publications use the previous path until their next successful update. This is deployed and its bounded AWS acceptance passed.
At 1,025 simple local members, catalogue reads took 0.107–0.223ms versus 189–214ms previously;
this is local MemoryStore evidence, not HTTP or AWS latency. Iteration 87 separates single-member
updates from broadcasts and removes unused serialization during snapshot copies. Detailed methodology,
limitations and kept/reverted decisions follow below.

## Goal and source

Reuse the complete signed-in MyOS Core dashboard and its HTTP contract over the Better engine. Preserve Better login/signup, existing accounts, documents, native timeline inputs, canonical transactions and AWS deployment. Source: local `/Users/piotr/data/myos-aws-test`, commit `8b71a0e7de48aaeb79bddddb25ae04e0fc0f2d85`. No GitHub access, source-repository changes, or Python changes.

## Decisions

- Reuse the original React 19 dashboard, components, CSS, assets, operation forms, resource readers, account context and generated TypeScript SDK. Use its existing esbuild production build and Vite development server, not a second UI design.
- Retain Better's Cognito forms behind the original `PortalSignIn` boundary. The public marketing portal and Business companion are separate applications and are not part of this post-login port.
- Keep Java 25, the existing HTTP service and DynamoDB atomic store. Implement the Core `/api/blue/v1` boundary as focused adapters/use cases; retain the existing `/api` routes for deployed clients and performance reproduction.
- Preserve native execution identities. An original runtime identity must not be advertised merely to bypass the UI's execution-selection checks. Package and runtime capability compatibility needs explicit verification.
- Account selection must be authorized server-side. A selected account header never grants membership. Existing personal account IDs must continue to resolve the existing data.
- Reuse the original OpenAPI contract as the parity target. Track each method separately in `dashboard-api-parity.json`; do not represent copied documentation as implemented API behavior.
- Keep reads paginated and document metadata separate from immutable content. No full graph expansion on document-list or operation-submission paths.
- Test layers: unchanged upstream UI behavior tests; SDK/HTTP compatibility tests against Better; account isolation/idempotency/storage tests; browser comparisons; bounded AWS operation measurements against release 231.

## Iterations

### 1 — Reuse the dashboard — KEEP

Copied the original dashboard and generated SDK, pinned dependencies and account UI archive. Kept existing Better auth forms. The original public-portal root-mount test is retained under `docs/upstream-reference` because that application is not being ported; all dashboard tests remain executable. The public example-value fixture is copied locally rather than reading another checkout. Other catalogue and OpenAPI fixtures retain their original paths and bytes.

TypeScript check: passed. UI tests: 363 passed, zero failures/skips, 30.7 seconds. Production build: passed. Four tests were adapted only for the explicitly retained Better authentication forms and route preservation. The signed-in dashboard/components/CSS are unchanged. This does not yet claim backend compatibility or AWS deployment.

### 2 — Account discovery and credential compatibility — KEEP

Added focused Core account/HTTP adapters, durable revision-checked user/account profiles, idempotent changes, membership checks for the existing personal account, session-context guards and the original API-key representation. Existing account IDs and credential digests are preserved. PATCH and PUT now receive the same origin checks as POST/DELETE. Real Cognito token expiry is retained for the original SDK's session contract.

The personal-account compatibility layer currently exposes the existing sole owner. Invitations, additional memberships, account provisioning, role changes and impersonation are still pending; their endpoints must not claim success. Runtime selection, document/history mapping and live notifications are separate subsequent iterations. All unimplemented Core routes currently fail explicitly with `CORE_PARITY_PENDING`.

The console asset manifest now includes the reused build configuration, SDK sources, account UI archive and package-selection catalogues, so stale transitive inputs cannot pass deployment validation.

## Acceptance

- Same signed-in navigation, layouts, forms, document/timeline/history views and settings as the pinned original.
- Corresponding API requests, response schemas and meaningful behavior work through the generated SDK; authorization and idempotency remain enforced.
- Original relevant UI/API tests run locally without access to the source checkout.
- Existing Better login/signup and previously created graph documents continue to work.
- Deployed console is verified after real login, with reproducible bootstrap, order and payment steps and bounded timings.

## Baseline performance

Release 231 (`87a7a8fc263eeb67e77daef47c4e610dfe68ce6e1e1e1e37433b691a4a8f3bcd9c`) was the deployed baseline at the start of this port. Native operation processing measured 202 ms for 8 orders and 460 ms for 208 orders; admission measured 317/624 ms, client round-trip 1.09/1.57 s. These are separate measurements, not one interchangeable latency. No new performance result is claimed until measured.

Validation for iteration 2: 513 host tests passed (zero failures/errors/skips), full formatting/compile checks passed, and the unchanged generated SDK passed real HTTP discovery, selected-account reads, profile updates, idempotency/revision conflicts, API-key creation/list/revocation and foreign-account denial. The SDK test exposed a missing bodyless DELETE case; fixed and verified with a 204 response. `scripts/check_core_sdk.py` reproduces this against an isolated loopback fixture, excluded from the deployed distribution. The asset-manifest regression test also passes.

### 3 — Content and owned timelines — KEEP

Port timeline metadata, native entry reads, content lookup and authoring requests. Signed entries must be returned unchanged, with Core metadata outside their native value. Do not scan all documents/timelines on the operation hot path. Compatibility gaps remain listed explicitly; the AWS runtime is still release 231.


Content now has exact YAML/JSON admission, explicit retention, account-isolated lookup, durable success/failure receipts and authenticated pagination. Owned timelines reuse the signed native publisher, preserve original entry values, expose content links and support native access grants/revocation. Conditional predecessor checks share the append's conditional transaction; idempotent replay preserves the original entry even after the head advances. Entry pagination pins an immutable prefix; exclusive timestamp selection uses binary search rather than scanning history. List selectors operate on metadata with bounded work per request.

Validation: 19 timeline/native regression tests passed; the unchanged generated SDK passed actual HTTP timeline creation/replay, listing/detail, native entry/content equality, conditional appends and stale-predecessor rejection. Content/account HTTP tests from the previous focused run also passed. Remote imports, provider guarantee selection, document-targeted submissions and access follower selection remain pending. No deployment yet.

### 4 — Document views and execution — KEEP

Reuse exact stored document versions and existing native input processing. The Better engine's retained atomic publication receipts differ from the original host's proof-heavy Coordination history. Core-shaped history responses must reference actual content-addressed Better host records with an explicit format/edition, never fabricated native proof identities. Full original proof-object parity is not claimed. Keep this distinction in runtime metadata and documentation while retaining the original dashboard layouts.


Validation for iteration 4: document/HTTP compatibility checks passed, including immutable history pagination and independent source/document/position/receipt identities. A real canonical native input test published the original SDK-shaped operation unchanged, processed it through a signed timeline, observed its event and next epoch, and proved idempotent replay. The generated SDK passed document registration, history and content lookup. Embedded member registration and advanced inspection remained pending.

### 5 — Live dashboard updates — KEEP

Successful atomic commits now notify account-scoped SSE subscribers. The adapter reuses original Core reset/coalescing/reconnect behavior; durable records remain authoritative. Subscribers are bounded per account and globally, authentication is rechecked, streams expire, and failed commits emit no change. One upstream UI behavior adapter invalidates a currently viewed child when its owning graph publishes; unrelated graphs remain unaffected. No signed-in layout/components were redesigned.

Validation: full host `format check installDist qualificationJar` passed. Focused actual HTTP SSE/account isolation/overflow/native execution tests passed. All 364 dashboard tests passed, zero skipped, in 30.7 seconds; typecheck and production build passed. Still local staging; AWS release 231 is unchanged.

### 6 — Exact runtime catalogue and bootstrap — KEEP

Expose actual deployed package and protocol identities, including the distinct Better host execution/history edition. Validate explicit runtime and package selections before bootstrap side effects. Adapt the original bootstrap envelope to the tested durable binding workflow. Two roles owned by the current account can be bound and initialized; foreign/email/represented participants are rejected explicitly before any timeline is created. Discovery now preserves actual native receiver generations and composite operation channel selectors. Fixed bootstrap source identity so operation targeting uses the bound initial document, with original authored source retained separately.

Validation: native two-role bootstrap, execution, replay, actual channel generations and no-side-effect rejection tests passed. Core document, operation and HTTP regressions passed. Package install/removal and broader participant/account management are still pending; pinned deployed packages are presented as managed. No AWS deployment/performance claim yet.

### 7 — Exact member events and input outcomes — KEEP

Input status now uses a durable original-entry index written atomically with the native assessment. Embedded members expose their own bound initial identity and exact emitted values, including repeated occurrences. Added `Contracts.Session.rootMember()` and `Transition.emissions()` so the host can retain native event content without reconstructing it from display projections. Event closures use incremental content storage and survive cold restart. Current member reads reuse the retained session; historical member reads use pinned immutable content indices.

Validation: 12 focused native input/member history regressions passed, including comparison with inline canonical execution. Library API formatting, projection tests and Javadoc passed. The reused dashboard's real loopback browser journey signed in, bootstrapped a two-role counter, invoked an operation, observed processing and opened history. Eight screenshots were inspected; only the expected pre-login 401 occurred. No AWS timing claim yet.

### 8 — Relationships and member history boundaries — KEEP

The unchanged Links tab now receives native source/target identities, paths and generations. Self-cycles appear once in combined traversal. Outgoing pagination detects a changed view; cursors remain account/query/resource bound. No relationship invents an applied proof position: the original Core `UNRESOLVED` representation is preserved where no such position is retained. Legacy local-edition generation tokens remain their original strings; canonical graph generations are native integers. This legacy distinction is not full schema parity.

A later-added member's history begins at its actual admission, with no predecessor before birth. Epochs retain the atomic graph boundary numbering of this host edition. Host publication dates are retained separately from canonical Blue identities. Idle workers no longer render `Waiting []`; checked boundaries come from committed source coverage. Runtime catalogue discovery now traverses the complete deployed import closure.

Validation: nine focused document, bootstrap, member history and relationship tests passed. They cover a dynamically added child, both endpoint directions, self-cycle deduplication, source identities, exclusive cursor scope, actual source coverage and no fabricated pre-birth epoch. No AWS deployment yet.

### 9 — Durable processing controls — KEEP

Pause, Resume and Retry now use conditional control records and idempotent command receipts. Engine publication and input dispositions check the exact control revision. A Pause acknowledged during an in-flight calculation prevents that candidate from committing. Resume atomically reschedules the existing input job; it does not submit another operation. Retrying a paused graph preserves the pause. Control applies to the canonical atomic graph containing the selected member, including cycles; it does not split an atomic Blue invocation. Legacy local propagation retains paused deliveries for subsequent worker passes.

Validation: 50 focused native/local publication, Timeline ordering and control tests passed. A deterministic store interleaving paused both engines immediately before their commit. Both rejected that publication and applied the original command once after Resume. A real signed-input bootstrap test confirmed pause/queue/resume and replay of an old Pause command after Resume. Full host `check installDist qualificationJar` passed in 80 seconds. Gas-billing-specific Retry selections remain pending; this host has no equivalent settled charge ledger.

### 10 — Explicit native runtime selection and committed events — KEEP

The reused package selector recognizes the exact deployed Better execution edition and its full import closure. Existing original Core selections remain distinct; a missing package fails explicitly instead of silently substituting a different publication. Bootstrap now requires a mapping or an authored account identity for every participant role, before binding creates timelines. The account restriction remains explicit.

The native execution audit retains attempted events from rolled-back work. The new public `Transition.emissions()` API and retained dashboard history expose only committed emitted values. This fixes a rollback boundary without modifying native audit semantics or inventing receipt identities.

Validation: all 365 UI tests pass, including exact runtime selection and missing-package rejection. Runtime catalogue and bootstrap checks pass against the real configured publication closure. Full library `format check javadoc` passed (2,484 tests); rollback preserves the document and publishes no events. Full host `format check installDist qualificationJar` passed: 545 tests, zero failures/errors/skips, 88 seconds. The generated SDK passed real HTTP checks; the browser passed a ten-screen journey including Pause/Resume and Links, with no signed-in HTTP/browser errors. The built asset inventory check passed. Remaining API parity is tracked separately; copying the UI does not complete all server capabilities.

### 11 — Existing-account legacy publications — KEEP

The first deployed browser check found an older local-edition document whose history predates retention of its authored source. Requiring that source to build list metadata caused the entire account list to fail. Listing now uses retained root metadata and the native lineage identity when available. If neither source nor lineage was retained, `documentId` is null; the current state is not relabeled as an initial document. Restoring an unsupported historical execution environment is not required to list its retained publications. Historical content still requires its actual runtime; no identity or runtime edition is rewritten.

Four focused document/history/content tests passed, including an early publication without its source or lineage, a cold adapter restart and account isolation. The initial AWS milestone installed artifact `57b0c5fc6eaa23c91d7b42289782431a9e7c242d7b9a7d0157f36ceb2a4f05db` successfully; real login, native counter bootstrap and operation/history worked, while the list failure prompted this iteration. The corrected deployment and latency check follow.

Deployed correction: `9d2f6fa1f209df6f3b59f983ed6cdc79df2f102a90e586ca1f63f103ae9f3a6c`, SSM `c09e41b0-aa6e-4ea3-b79a-4ce159351070` succeeded. Real account login, fresh counter bootstrap, operation, immutable history and processing views passed. Counter: https://d2ya9daeiur7nz.cloudfront.net/#documents/graph-b7f5c54d793bc776e8835ccbade495e3ddaa98179a289f15 . Full resource-list loading receives a separate browser check; the earlier screenshots used a wait that did not recognize the UI's Unicode ellipsis.

Bounded AWS native regression (same instance/runtime, seven operations per agreement):

| Orders before → after | Final acknowledgment engine | Admission through durable commit | Client submission through observation | Final Agreement paid total |
| --- | ---: | ---: | ---: | ---: |
| 8 → 11 | 334.1 ms | 467.7 ms | 1380.8 ms | 600 |
| 208 → 211 | 469.2 ms | 628.9 ms | 1583.6 ms | 2200 |

Each run added three orders, prepared one Payment, accepted its instruction, reported a transfer and acknowledged it. The Payment and Order totals both became 200 and the Agreement increased by 200. The large graph's final acknowledgment remains close to release 231's 460 ms engine / 624 ms durable result. The smaller run was 334/468 ms versus the prior 202/317 ms; this small sample is not evidence of a speed improvement. Browser/network observations, cold restoration and engine calculation are separate measurements. No claim of full API parity follows from these measurements.

### 12 — HTTP discovery and credential pagination — KEEP

The original HEAD discovery endpoints now return the corresponding GET headers and content length without a body; authentication errors also stay bodyless. API-key pages honor the requested limit and use authenticated cursors bound to account, resource and query. The old API retains its existing cursor contract. This closes a query-isolation and limit mismatch without changing key authority or the native processing path.

Nine focused real-HTTP/account-key regression tests passed, including unauthenticated HEAD, header equality, exact page size, foreign-account cursor rejection and changed-query rejection. Production UI provenance rechecked: only `portal-auth.tsx`, `process-packages.ts` and `live.ts` differ from the original source; the added Better authentication and runtime-selection files are documented separately. No signed-in components or styles were replaced.

The final deployed browser pass waited for rendered rows/loading indicators rather than forcing a new request for an already cached catalogue. Documents rendered in 3.72 s including initial login-context discovery; Timelines in 1.01 s; Packages, API Keys and Settings in about 0.21 s each. These are one browser sample with a 200 ms stabilization delay, not server benchmarks. Direct deployed catalogue work measured 2 ms; account work measured 4 ms. There were no browser/HTTP errors in the tested pages. This iteration is staged pending the next deployment.

## Iteration 13 — workspace creation and selected-account isolation (KEEP)

Added the original `POST /accounts` creation shape and workspace discovery through `/me`.
A single conditional DynamoDB transaction publishes the account, Admin membership, user index,
exact runtime selection, disabled built-in agent and replay receipt. Better already provides one
owned personal account, so additional accounts begin with Admin membership, matching Core's
single-owned-account rule. No SQL provisioning worker is necessary for this storage model.

The Core adapter scopes resource operations to the selected workspace after checking membership;
user preferences and identity remain attached to the login. API keys cannot select another account.
SSE subscriptions now use the selected workspace and recheck membership on authorization renewal.
Saving unchanged profiles/preferences preserves public revisions, as in Core.

Validation: 12 focused HTTP/domain tests passed in approximately three seconds. They cover
concurrent identical creation, durable replay/conflict, actual runtime/agent registration, invalid
websites, cross-user denial, stale session guards, preferences, document/content/key isolation and
live updates that exclude the personal workspace. Existing HTTP discovery/key tests also pass.
The tests adapt HostAccounts and HostUserPreferences invariants to Better's atomic store; they do
not claim SQL projection-barrier coverage. Multi-user invitations and role changes remain pending.

## Iteration 14 — bounded epoch reads and processing requirements (KEEP)

Implemented the original epoch-list route with exact retained receipt/event references, a cursor
separate from history cursors, and a boundary pinned on the first page. Epoch detail now includes
the exact historical document. Pages avoid expanding every document. Invalid/overflowing epochs
return a client error. Processing requirements return the same native observation as processing,
including pause/issue fields, following Core's DocumentReadContractTest rather than inventing a
requirement list.

Validation: six focused tests pass, including concurrent publication between pages, account/query/
resource cursor isolation, historical BlueId equality, native requirements shape and epoch bounds.
Better's explicitly identified graph publication epochs/receipts remain its host edition: this does
not claim Core proof-receipt or per-member epoch parity. Processing event history is still pending;
current state will not be presented as a fabricated historical event log.

Iterations 12–14 integration gate: all 553 host tests passed (86.4 s summed test time;
Gradle 1m28s), original generated SDK HTTP contract passed, and the unchanged production
React UI passed an 11-screen browser journey with no signed-in HTTP/browser errors.
The journey includes native bootstrap, operation invocation, pause/resume, history,
workspace creation, isolation and switching back to the existing document. The browser
waits for account selection to finish before reopening the document. The initial anonymous
`/me` 401 is expected and is not a signed-in failure.

## AWS check after iteration 14 — follow-up required

Artifact `8c9d8202998d661dd561e24840319973944fef2f90f4caeddbc13d147e827026` was
installed on the existing test instance; SSM `da860936-5afc-46a1-a02a-9093045ae74b`
passed in 24.108 s with native Timeline/canonical-graph health checks enabled.
The real account can create/select the isolated **Dashboard parity workspace** and bootstrap a
native document. A subsequent operation remains WAITING on the public control Timeline
`R5uoP4SvoRKK1fMa88eU7F9deVond1QQXzdHrkjcGHt`: its fresh workspace archive has no
committed prefix and the durable poll retry reports LanguageException. This is a failed live
operation acceptance check, not a passing performance measurement. Investigation is active.
Temporary diagnostic workspace API keys are revoked immediately after the read-only checks.

## Iteration 15 — durable admission event retention (KEEP; staging only)

Admission acceptance and terminal receipts now atomically retain their event facts. Events are
sequenced into the account log on reads, in bounded batches, so the document execution path
never contends on an account-wide event sequence. Identical replays do not append events.
Implemented `GET /events`, `GET /events/{id}` and admission history with exact retained payloads,
scoped cursors, ascending follow mode, descending pinned mode, filters and account isolation.
These adapt Core's AdaptedEvents model; only newly retained admission-status events exist so far.
Native document/timeline/content event capture and webhook dispatch remain pending. Old
admission transitions are not reconstructed and represented as retained historical events.

Validation: 14 focused tests passed in about three seconds. Coverage includes immutable pending
and terminal payloads, restart/replay, concurrent projectors, bounded backlog continuation,
lost projection commit responses, temporal/filter cursors and actual SDK-compatible HTTP reads.
Projection is currently read-driven. Pinned histories cover the projected retained prefix;
continuous background projection and read-model completeness signaling are still required.

## Iteration 16 — diagnosable Timeline polling and transport parity (KEEP)

Durable polling failures retain the Language error category, without exception messages or
content values. This makes a failed fresh-workspace acquisition diagnosable while keeping
reader credentials and document details out of public processing diagnostics. A new regression
reads 129 access-control grants through both the in-process and serialized transports, crossing
the provider page boundary, and verifies the same entries and final head.

Validation: category-retention/privacy tests pass; full host check passed in 1m27s with the
admission event changes. The additional 129-grant regression passed in six seconds including
Gradle startup. The generated SDK integration check passed. The AWS workspace failure remains
under investigation; this iteration improves evidence and does not claim the failure is fixed.

## Iteration 17 — complete inline evidence at the local transport boundary (KEEP)

The fresh workspace's control Timeline contained grant updates as well as creations. The AWS
read-only diagnostic reproduced the failure with the in-process materialization but succeeded
with the exact same serialized response (195 verified entries). The missing identity occurred
when Timeline operation validation opened an inline grant definition through a separate context.
`Language.prepare(LanguageContent)` retained only the root; exact JSON parsing retained all inline
children. Retrying the provider fetch could not repair that incomplete nested snapshot.

The import now registers every physical inline body, deduplicating shared nodes and preserving
the original immutable values. It neither resolves references nor imports the sender's unrelated
provider evidence. Attached cyclic bodies are stripped at this transport boundary because their
wire representation contains only a reference; explicit cyclic proof records remain required.
The work is bounded by the transported materialization, independent of other account documents.

Both the public Language independent-child-reference test and the signed control-history test
failed before this correction. They now pass. The latter spans 129 grant creations plus a grant
revocation across a page boundary and compares local/wire entries and final head. Existing tests
continue to reject private sender evidence and collapsed hidden content. Cyclic imports require
actual proof records. Full library check/Javadoc passed: 2,486 tests, zero failures/skips.
Full host check passed: 559 tests, zero failures/skips, 87.8 s summed test time. The combined
Gradle verification/build completed in 1m30s. Deployment acceptance follows.

### AWS acceptance after iteration 17

Artifact `faff53ae0e20fdf2fa88ebc551b4716690031bdded4b413c6a63a68dfc7853db`, SSM
`4f2b490c-f861-4a32-bf65-b454e3956534`, installed successfully in 23.032 s. The stalled
workspace operation resumed without resubmission and changed the counter from 0 to -1 exactly
once. A second decrement through the unchanged dashboard produced -2 at epoch 2 in 1.620 s
including client/network/UI polling. The authored first operation is decrement, and history
lists start at epoch 0; the acceptance harness was corrected to reflect both facts.

The bounded native scenario added three Orders to each existing Agreement, then initialized and
completed one Payment. Payment and Order totals became 200; each Agreement increased by 200.

| Orders before → after | Final acknowledgment engine | Admission through durable commit | Client submission through observation | Agreement paid total |
| --- | ---: | ---: | ---: | ---: |
| 11 → 14 | 337.2 ms | 462.2 ms | 1329.9 ms | 800 |
| 211 → 214 | 476.0 ms | 699.7 ms | 1575.1 ms | 2400 |

These are bounded regression samples, not a statistical throughput campaign. The first small-graph
Order after deployment took 1220.4 ms engine time, followed by 609.9 and 528.9 ms. The final
acknowledgments remain near iteration 11 (334/469 ms engine). Small-sample variance and cold
restoration are not presented as a speedup.

## Iteration 18 — native resource miss parity (KEEP)

Cross-workspace native document reads correctly denied access but returned HTTP 400. Missing
canonical graphs and members now use the same typed missing-document exception as ordinary
documents, producing Core's HTTP 404. Processing reads also validate that the requested member
exists instead of reporting the root graph's status for an invented member ID.

Nine focused tests passed in four seconds, including real HTTP owner/foreign/missing-member
checks on document, content, history, operations, processing and epoch reads, plus workspace and
canonical durability tests. This change does not alter document calculation. The parity matrix
now references the actual account/HTTP test class names and records the workspace coverage.

Iteration 18 AWS acceptance: SSM `ade7c307-a6a2-4b2f-ad4d-bb2d6a86b798` succeeded in
23.978 s. The unchanged dashboard displays the recovered workspace counter and epochs
0, 1 and 2; a personal-account read of the workspace document returns 404. No browser or
signed-in HTTP errors occurred in the completed operation/history/isolation journey.

## Iteration 19 — original avatar and business-logo controls (KEEP)

Reused Core's `AvatarUpload`, `AvatarImages`, `HttpDeadline` and `HttpCancellation`, and the
original Commons FileUpload 2.0.0-M5 / Thumbnailator 0.4.21 dependency versions. Package names,
public problem type and trace integration adapt to this host. The signed-in React forms and
shared account UI are unchanged. Authorization and session guards run before reading multipart
bodies; decoding is limited to two concurrent images. Limits remain 5 MiB / 16 million pixels,
15-second multipart reads, JPEG/PNG input, 512-pixel normalized PNG and stripped metadata.

Profile metadata, normalized image record and idempotent receipt publish atomically through
Better's existing store. This avoids adding the original SQL/S3 two-phase image publication
system. Large records use the store's existing immutable-page publication, before conditional
records become visible. Retired images are no longer addressable; orphan immutable-page garbage
collection is an existing host-wide limitation, so physical byte erasure is not claimed.

User avatars and workspace logos have independent revision-checked owners. Name changes retain
image URLs. GET/HEAD authenticate before conditional caching; retired versions return 404 even
with a matching ETag. The original six deadline cancellation tests were ported, including close
barriers, shutdown/saturation and failure propagation. HTTP cleanup failure fails subsequent
uploads explicitly rather than admitting more transfers into a poisoned cancellation executor.

Thirteen focused tests pass, covering the original lifecycle plus profile-edit races, lost commit
acknowledgments, revision checks, unsupported/pixel-bomb input, multipart validation, selected
workspace isolation, authenticated GET/HEAD/304 and deletion. Full host check/build passed: 573 tests, zero failures/errors/skips, 89.8 s summed test time (Gradle 1m33s). The generated SDK passed. The unchanged dashboard passed a 13-screen browser journey including user-avatar and workspace-logo upload, image display and removal; no signed-in HTTP/browser errors occurred.

Iteration 19 AWS acceptance: artifact `57236cd14703d972c9ca8924981167e7b9cefc4d75b362ab9be816db9880be18`,
SSM `bb8cac7e-c506-4b64-9f48-f320ec75dca6`, healthy in 24.061 s. The existing test workspace
uploaded and displayed a logo through the original settings UI, returned authenticated 304,
then removed it; the retired URL returned 404. Workspace revisions advanced 1 → 2 → 3. The
personal profile and avatar were unchanged. No browser/HTTP errors occurred. The temporary
control-Timeline diagnostic JAR was removed from S3 and the instance (cleanup SSM
`fad2f628-2bc2-4c03-a74b-976f9b084106` succeeded).

## Iteration 20 — indexed admission listing (KEEP)

Implemented Core's `GET /admissions` with newest-first order (identity tie-break), kind/status/
session filters, inclusive instant bounds, exact limits and authenticated account/query cursors.
Only metadata is read. One index row publishes atomically with each new receipt; completion
updates that same receipt without changing its position. No shared-account sequence counter is
added to execution. A page examines at most 200 rows in batches of 32 and may return an empty
filtered page with a continuation token. `paginationMode: live` accurately describes concurrent
receipt changes; a full-history count is not computed.

Existing receipts gain an ordered index in 64-record transactions, with a durable restart cursor.
Until that initial backfill completes, listing returns an explicit retryable 503 instead of silently
claiming a partial history is complete. A newly completed receipt and its index cannot be split by
a crash. Newly accepted document operations retain their session selector before the result exists.
Older operation receipts did not retain that fact (including completed receipts whose details only identify the document lineage); their session filter cannot be recovered by guessing. All writers must be
upgraded before the backfill completion marker is trusted in a multi-instance rollout.

Terminal failures now contain all required `AdmissionProblem` fields. The problem status uses
the store's exact integer representation so first response and restart/replay remain equal.
Original `ProjectedAdmissions`/`AdaptedAdmissions` ordering/filter behavior guides the adapter;
its unbounded historical scan is replaced by the ordered index. Further role-specific receipt
redaction awaits the corresponding membership/agent authorization port.

Validation: 14 focused receipt/list/event/HTTP tests passed, including pending-session filtering,
bounded backfill and sparse pages, timestamp ties, lost backfill acknowledgments, restart, scope
isolation and malformed filters. The original generated SDK passed list pagination and failure
schemas. Full host check passed: 580 tests, zero failures/errors/skips; Gradle 1m32s.


Iteration 20 AWS acceptance: artifact `9d60eb5244c2f02683a05656b56b474792df3dcfb655c0e9d48bfa5ddece5d24`,
SSM `047c61d8-15f1-4082-81ee-d91688db6d5b`, healthy in 24.068 s. Read-only listing in the existing
workspace returned retained successful operations, an exact one-item page and a continuation token;
each listed item equals its individual receipt. Historical operations predate the retained session
selector, so their absence from a session-filtered list is documented rather than guessed.

## Iteration 21 — canonical operation preparation (KEEP)

Ported the original operation-preparation boundary and form descriptor behavior. Preparation checks
one reviewed document identity/epoch and its authorized timeline/actor/channel route, validates the
original request with the native Language and BEX matcher, and returns source JSON without rewriting
its request bytes. It creates no admission, timeline entry or document mutation. Presentation has
separate depth/node budgets, pagination and collapsed-field selectors; a partial form never implies
partial input validation. Inherited fields, required presence, fixed values, typed lists, dictionaries,
custom scalars, narrowed enums and cycles retain their canonical semantics. No operation-name or
example-specific branches were added.

The original Core host accepts arbitrary-precision source integers but rejects decimal precision
loss. Better now uses a shared typed source adapter for preparation, content creation and submission.
The public Language `prepare(Node)` / `read(Node, Goal)` APIs avoid a lossy text round trip while
keeping the strict Language wire policy unchanged. `LanguageView.isConcrete()` distinguishes an
unvalued definition from a present request; `LanguageView.content()` selects effective inherited
nodes without reopening unrelated siblings or rewriting the owner. The request is admitted before
presence checks so missing inherited required fields retain their actual diagnostic paths.

An end-to-end SDK test exposed missing current-document evidence during exact-version submission.
The operation path now receives the authorized current immutable document before admitting the
message. This evidence stays request-local; preparing a form does not write it to content storage.
Native route/target validation still runs before signed publication and document execution.

Validation: 2,489 library tests and Javadoc passed. All 594 host tests passed, zero failures/errors/
skips, 89.5 s summed test time (Gradle 1m33s). Thirteen focused preparation tests adapt original
Core cases. The SDK verified no admission/publication during preparation, exact large-integer
execution, replay and stale/foreign route rejection. The original dashboard passed its 13-screen
browser journey; its only HTTP error was the expected pre-login 401. The initial port test wrongly
expected the absence diagnostic for an unfilled scalar; native admission correctly rejects that
scalar first. The corrected test also includes upstream's optional-only nominal-object case and
asserts its distinct `REQUEST_NOT_PRESENT` diagnostic. No validation rule was relaxed.
AWS deployment succeeded (`8e1ae3136f8323eb2ac36f9a997d7f5d40492ac4933a4945924e57b7a8a98c24`),
but live complex-document acceptance exposed a timeline acquisition failure before execution.
The first exact-current-document operation was retained once, while the reader exhausted its
30-second dependency-acquisition deadline. The small counter SDK test did not cover this closure.
This iteration is not evidence of successful complex-document execution; iteration 22 addresses
that failure.


## Iteration 22 — bounded content reads and resumable verification (KEEP)

Exact-current-document operations can refer to a large immutable closure that older operations,
addressed to the initial lineage, never required the reader to acquire. Two host costs compounded:
the content endpoint returned one shallow body per request, and each missing body restarted page
validation. Bounded content materialization now includes at most 128 additional records / 64 KiB,
with independent depth and traversal limits. It preserves type references, exact identities and
source-scoped authorization; it introduces no protocol field or special local trust bypass.
The same materialization crosses both local and wire transports.

The reader now supplies missing records to the ongoing Language validation through its ordinary
provider interface. Completed validation work survives the fetch. All fetches still require the
signed, correlated response, source permissions and existing total bytes/count/deadline limits.
A provider failure preserves its original transport, signature or cancellation category, and no
partial prefix is published. Temporary records remain confined to one acquisition attempt.

A read-only AWS diagnostic against the already retained input reproduced the baseline failure:
57 content requests exhausted 30 seconds. Bounded inlining alone completed once in 23.99 s;
adding validation resumption completed in 9.69 s, with 35 content reads (5.70 s in content transport).
These are cold diagnostic acquisitions with in-memory publication, not document execution timings
or a claim of millisecond end-to-end performance. An additional contract-reference experiment
made 43 requests and took 10.38 s; it was rejected. Contract bodies retain their previous
presentation. The runtime still needs live recovery and steady-operation measurements.

Validation: the full host suite passed after adding resumable acquisition, including original-error
preservation. A 40-child collapsed-content test requires at most four content reads and establishes
the same entries through local and wire transports. Separate tests cover bounded read-ahead,
large bodies, exact identity, inheritance, revoked permissions and private-source isolation.
Library semantics, public API and the reused dashboard UI are unchanged in this iteration.

Iteration 22 AWS acceptance: artifact
`401a755065902781fa406017a79a58a667ba25f2a88a3316df89b7a2dae8660a` was installed
successfully and passed service health checks. The original retained input applied exactly once;
its recovered Order was then used for four operations through Core preparation/submission. Payment
and Order reached 200; Agreement increased from 800 to 1000. Engine times for preparePayment,
acceptPaymentInstruction, reportTransfer and acknowledgeTransfer were respectively 928, 275, 245
and 351 ms. Durable processing was 1128, 412, 359 and 456 ms. These are single samples. The last
acknowledgment still took 7.42 s from signed entry to commit, so these engine timings do not imply
matching end-to-end latency. No further operations were submitted while the earlier input was stuck.

## Iteration 23 — reuse committed local source evidence (KEEP)

A local reader can now resolve exact records already committed by the same account and source,
without requesting and signing each body again. The source index is pinned at the start of one
acquisition attempt. Provider identity, Coordination identity, account, timeline and URL must match;
other accounts and remote providers retain the signed content-read path. Speculative blobs and
unrelated private sources cannot enter this index. There is no global private-content lookup.

This changes content availability only. Language still verifies every supplied record's identity,
normal validation still runs, and the original signed read and current grants are checked on every
acquisition. Revoked readers fail even though the account owns the bodies. The archive still
collects and publishes its complete supporting closure under its existing transaction and resource
limits; no persistent format, proof or document identity changes. Remote and local readers reach
the same verified entries. This is physical reuse of committed evidence, not a new execution mode.

Focused tests cover zero content round trips for a local owner, ordinary signed acquisition by a
foreign authorized receiver, revocation, immutable index pinning, unrelated-source exclusion and
rejected-publication isolation. All 601 host tests and build checks passed (zero failures/errors/skips; Gradle 1m22s). AWS acceptance follows deployment.


Iteration 23 AWS acceptance: artifact
`d339beeab302ac01aa156c19dae780a9597a9f2b8f1209570b99102c142d4df6` passed health
checks. Five exact-current Core operations completed on the 15-order agreement, creating its 16th
Order and increasing Agreement paidTotal from 1000 to 1200. Engine times were 1248, 910, 201, 292
and 350 ms; signed-entry-to-commit times were 4067, 1815, 1078, 1418 and 2019 ms. These are single
samples, not warm-throughput claims. The first submission on the 214-order agreement failed before
creating an entry: its source content index exceeded the 8 MiB new-content allowance. There was no
large-graph mutation or input waiting to execute. Iteration 24 addresses this separate storage cost.

## Iteration 24 — link selected committed document records (KEEP)

Core operation submission now pins the authorized current document and its immutable snapshot
content catalogue together. The ordinary Language closure export still selects every required
record. For a selected record already committed by that account, the timeline index reuses its
existing content blocks; a small raw record receives only an ordinary single-chunk manifest.
Unselected records are not linked. Missing records still use normal encoding and the unchanged
8 MiB new-content limit. There is no blanket grant to a graph, alternate proof, new persistent
format or increase to resource limits. Source publication retains its existing atomic root update.

The native Language reader validates linked exact bodies and cyclic proofs in the usual way.
Tests cover a minimally over-limit 8.8 MB closure, less than 100 KB of new index writes, exact cold
reads, cyclic member reads, pinned historical indexes, unrelated-record exclusion, cross-account
rejection and the unchanged limit for genuinely new records. All 604 host tests passed, zero
failures/errors/skips; full formatting, compile and distribution checks passed in 1m28s. AWS
acceptance follows deployment.
The closure is still enumerated during first submission; this change removes repeated body writes,
not all cold traversal or validation work.


Iteration 24 AWS result: artifact
`f4d374cdf6066b5a926c4c5d8575efb5d7fc430e7de94060b5381c7dbb9172d9` passed deployment
health checks. The source entry was committed, but Core's subsequent content projection attempted
another retention without physical reuse and the admission returned FAILED. Contrary to the initial
interpretation of that error, an original input exists and must not be resubmitted. The graph remains
at 214 Orders / paidTotal 2400 while its source reader retries. Read-only inspection found all 5,211
selected document records already present in the committed snapshot (40,315,171 bytes in their
public record representation). This rules out missing snapshot membership for that document.
The next iteration must fix secondary retention and prevent post-publication projection errors from
becoming terminal input failures. The stored input will be recovered before further operations.

## Iteration 25 — canonical operation context (KEEP)

The original operation-context API is implemented with its exact selectors, reviewed-publication
checks, canonical list indices, lossless integers, metadata navigation, contracts reference default,
optional inline contracts, 256 KiB value bound and paginated child outlines. Oversized scalar payloads
receive the original terminal scalar diagnostic; large navigable envelopes can be explored by child
path. Existing nested references remain references. The read uses the account-authorized pinned
publication; arbitrary content identities and stale reviewed epochs do not select other documents.

The library exposes `Contracts.inspect(content)`, an immutable host inspector. It reuses native
Contracts navigation and exact literal projection without opening an execution session, charging gas
or changing content. Output bounds are checked during construction; each read gets its own Language
work allowance and uses the content's retained provider/evidence. Public API documentation and
Javadoc explain these boundaries. No dashboard component or design was replaced.

All nine original OperationContext test scenarios were adapted to the Better public API. Further
tests verify stale selection, foreign accounts, malformed selectors, bounded reads and cycles.
Full checks passed: 2,491 library tests, 614 host tests, zero failures/errors/skips, Javadoc and
format/build checks, Gradle 1m31s. The unchanged generated SDK passed actual HTTP context/outline
reads and stale-publication rejection, together with its existing operation/execution checks.
An initial SDK attempt used an old installed distribution and correctly returned 501; rebuilding
that distribution before the successful run fixed the test setup, not the acceptance criteria.


## Iteration 26 — reuse through acquisition and Core projection (KEEP)

The signed publisher, local acquired archive and Core content catalogue now share selected immutable
record pages when both account and physical store match. A local reader pins the source index for
one acquisition. The signed read, current permissions, Language identity validation and normal
range verification still run. Known local bodies do not consume the allowance for newly transported
records. Independent stores, foreign owners and remote providers retain the normal transfer limits.
The acquired archive stores its own complete selected index, so a cold restart does not depend on
the publisher's live cache. Reusing content does not establish a prefix, permission or business event.

Both source and archive indexes apply one radix-tree batch per publication. Earlier code rewrote
and staged intermediate paths for each record; a rejected source batch could also expose a partial
root to its caller. The new index root is installed only after the full batch satisfies its bounds.
Physical manifests, exact content, proofs and source/graph publication formats are unchanged.

Core pre-retains the validated reviewed message before first publication and recovers a committed
command before re-evaluating its now-stale current-document precondition. A secondary projection
refusal after durable publication is retryable and leaves admission PENDING. It cannot turn an
already stored entry into a terminal client-input failure. Historical FAILED receipts produced by
the earlier bug are not silently rewritten; their actual stored entry is checked independently.

New boundary tests cover a 200 KB committed body under a 64 KB transfer allowance, rejection in an
independent store with the same account name, cold acquired-content recovery, source/account
isolation, complete large-closure secondary retention, failed-batch root isolation, and an injected
post-publication projection refusal. Retry returns the same original entry exactly once. Replaying
an accepted command does not revalidate its old precondition, while a new stale command is rejected.
The initial large-catalogue test fixture omitted inline child records; it now exports the complete
closure. Replay equality is compared at the JSON boundary because persistence restores numeric
values as arbitrary-precision integers. Neither runtime bounds nor acceptance criteria were relaxed.

Validation: all 617 host tests and format/build checks passed, zero failures/errors/skips, Gradle
1m25s. The original generated SDK passed real HTTP preparation, context, execution and replay checks.
Library code is unchanged since iteration 25. Recovery and fresh AWS measurements follow deployment.


Iteration 26 AWS acceptance: artifact
`ef447fb58dc07fac4965c5f576b3bf9b3b033964bbad1dfb8d72245586523807` passed service
health checks. The input retained by iteration 24 recovered exactly once, creating the expected
Order without resubmission. Its subsequent Payment and Order reached 200 and Agreement paidTotal
increased from 2400 to 2600. Recovery took 2489.6 ms engine / 10543.0 ms durable processing;
its signed-entry age includes the earlier stalled queue and is not an execution benchmark.
The recovery flow's final acknowledgment had 756.8 ms engine but 12854.5 ms signed-entry-to-commit.
That delay remains a real end-to-end limitation, not a millisecond result.

Fresh Core preparation/submission checks then passed all five steps on each graph. These are one
sample per operation on the deployed c7i.large, not throughput estimates:

| Operation | 16-order graph engine ms | 215-order graph engine ms | Small signed-entry → commit ms | Large signed-entry → commit ms |
| --- | ---: | ---: | ---: | ---: |
| placeOrder | 478.8 | 812.4 | 3118.3 | 4238.2 |
| preparePayment | 949.7 | 756.0 | 1745.0 | 1439.2 |
| acceptPaymentInstruction | 121.8 | 276.2 | 799.6 | 930.2 |
| reportTransfer | 243.6 | 364.0 | 1718.4 | 1449.6 |
| acknowledgeTransfer | 246.7 | 576.7 | 2352.0 | 4846.2 |

The graphs ended at 17 / 216 Orders, with paidTotal 1400 / 2800; each Payment propagated 200
through its Order to its Agreement. Preparation server work ranged 280–886 ms. Signed submission
plus client observation ranged 1.63–6.34 s, excluding preparation and preceding lookup calls.
Therefore engine work remains subsecond in these fresh samples, but full API latency is not yet
at the desired level. Iterations 25–26 also passed the original 13-screen local browser journey,
with no signed-in HTTP/browser errors; the only HTTP error was the expected pre-login 401.

## Iteration 27 — truthful source-wait status (KEEP)

The original processing view now distinguishes an unavailable required source from an idle engine.
Durable polling failures are selected through the document's own timeline indexes, read in batches,
and shown as a retrying source wait with a safe category and retry time. It does not enumerate the
account's timelines or embedded documents, expose transport messages/reader custody, or change
native state. A retained execution error takes precedence over a source warning; Pause takes
precedence in the displayed state. Recovery removes the warning from subsequent reads without
requiring a document publication. The original UI renders its existing notice and timeline link.

Focused tests passed for source waits, safe diagnostics, foreign/unrelated source isolation,
batched lookup without directory scans, recovery and paused/execution-error precedence. A first
compile failed because the new fixture omitted its checked-exception declaration; corrected before
execution. Full checks and AWS acceptance are grouped with iteration 28.

## Iteration 28 — unlink root registrations (KEEP)

The original Delete action now appears for registered roots. Its REMOVE admission completes with
REGISTRATION_UNLINKED and processingChanged=false. A conditional visibility tombstone hides the
root from Core listings, reads and new operations. It does not delete native content, history,
subscriptions, execution state or pause controls. Replaying creation does not resurrect the root;
replaying unlink returns its original result. Embedded members without a root registration return
the original root_required conflict and remain usable when their parent registration is hidden.
A concurrent unlink during pagination is skipped without failing the whole list.

This ports the relevant AdaptedManagement and AdaptedAdmissions boundaries. Better still lacks
Core's separate aliases that deduplicate multiple root registrations onto one native instance;
unlink support does not claim that broader registration parity. No original UI component changed.

Full host checks passed after iterations 27–28 (624 tests, no failures/errors/skips, 1m26s).
The new embedded fixture initially used an unavailable Initialization Channel name; it was corrected
to the actual Lifecycle Event Channel / Document Processing Initiated type before the full run.
Reviewing the unchanged Delete dialog exposed its promise that re-adding restores current state;
iteration 29 addresses that dependency before the Delete button is deployed.

## Iteration 29 — registration handles over existing native instances (KEEP)

A Core registration can now reference an existing native instance. Concurrent identical initial
sources use one deterministic native creation command. A durable identity pin, active-registration
pointer and per-command receipt preserve convergence and replay across a lost reply or restart.
Re-registering a known retained publication keeps its current document, history and controls. An
unlinked handle remains hidden; the new registration receives a distinct handle. Embedded members
can still be read independently. Alias resolution uses account-local immutable target records and
never follows chains; existing native IDs retain their direct path.

Core metadata exposes the registration as sessionId and the native instance as instanceId. Receipt
and position identities continue to use the existing native instance; alias creation cannot rewrite
history. Native input wakeups and pause/resume target the instance, while command replay remains
scoped to the registration. Root visibility changes notify the existing account-scoped live stream.
The original dashboard components remain unchanged.

Validation: all 626 host tests passed (zero failures/errors/skips), full format/build checks in
1m27s. Two additional lost-response tests then passed with the registration/live subset: a crash
after the identity pin and a lost alias-publication reply both recover once, preserving the original
handle/history. The original SDK passed unlink receipts, replay, hidden endpoints, exact content
retention, re-registration, operation preparation and actual native execution through the alias.
The original dashboard passed a 15-screen journey, including Delete and Add new → Reference;
its reattached instance and stable publication exactly matched the removed registration.
The browser harness distinguishes the toolbar Resume button from the restored pause notice's
Resume link. It permits only the deliberately removed handle's in-flight 404 during Delete;
other signed-in HTTP/browser errors still fail. No UI component was changed to pass the checks.

Persistence is additive: account-local CORE_REGISTRATIONS rows and registration entries in ROOTS.
Native snapshots, timeline entries and existing IDs are unchanged. Once aliases exist, console
rollback must use an alias-aware host; release 26 cannot interpret the new registration rows.
Do not downgrade to that console without an explicit visibility-metadata migration. Re-registration
is an account-authorized host attachment and does not alter Blue document identities or gas.


Iteration 29 AWS acceptance passed on artifact
`a4a5276da60a9b983c3d38adaedff68097c24915f6910c8aa130c69c8bc51c03`.
The original SDK created a counter, executed it, paused it, unlinked its registration, re-added
its retained publication, verified identical native instance/history/pause state, resumed the
new handle and executed again. Engine times were 46.9 and 14.0 ms; signed entry to commit was
381.3 and 278.1 ms. The old handle remained unavailable. Both five-operation Agreement flows
also passed: 216 → 217 Orders / paidTotal 2800 → 3000, and 17 → 18 Orders / 1400 → 1600.
However the large graph's add took 15969.7 ms signed entry to commit (2026.8 ms engine), and
its final acknowledgment took 10056.2 ms (770.5 ms engine). These are latency regressions,
not acceptable millisecond end-to-end results.

## Iteration 30 — reuse completed timeline schema checks (KEEP; latency target remains open)

A bounded diagnostic run isolated 17734.3 ms in timeline page verification, versus 152.7 ms
in graph admission and 1527.1 ms execution. The new Order committed exactly once at version
279 (218 Orders / paidTotal 3000), after the client's 20-second observation timeout. It was
not resubmitted. The aggregate recording covered three acquisitions and one operation, with
6973 content reads and 3568 batch content reads. Inclusive phase totals overlap; they must
not be added together as one latency.

The verifier now retains bounded, exact recipe/goal/occurrence validation facts and completed
closure identities between successful pages. Changed values and contextual refinements still
validate; ordinary Language snapshots, other verifiers and native execution receive no cache.
The cache contains no document bodies, providers, signatures, reader permissions or ordering
results. A failed page publishes no new facts. Native gas and canonical document identities
are unchanged. Limits per verifier: 4 MiB / 16384 validation facts and 2 MiB / 8192 closure facts;
individual keys are bounded. Eviction returns to ordinary verification. Cold first acquisition
still performs validation; this iteration does not claim to eliminate that cost.

The focused protocol tests passed (47). A new multi-page test confirms that a shared subtree's
unchanged descendants are not reopened through its provider; inspecting a necessary header
can still read a present child. Changed invalid values still fail, public snapshots retain
no verification facts, and an independent verifier still requires its own content. The first
fixture used a Java int instead of the internal BigInteger literal; corrected. The initial
zero-read assertion was replaced by the actual requirement: unchanged descendant traversal
is avoided while necessary header inspection remains permitted.

The raw JVM recording was not transferred: automatic approval review rejected its S3 export
because of possible sensitive runtime data. The safer on-server command returned aggregate
numeric phases only, then deleted both diagnostic files. Its stack-summary tool failed, so
no CPU stack result is claimed. No S3 diagnostic object was created.

Full iteration 30 checks passed: 2493 library tests and 628 host tests, zero failures/errors/skips;
format checks, Javadocs and deployment builds passed in 1m34s. This validates compatibility,
not a deployed speedup. The original SDK and AWS warm/cold measurements follow.

The unchanged Core SDK passed after iteration 30. AWS artifact
`89cc664f3ef382a9a72196aa40e233da23b23534caee86faf7b8a5a12859d476` passed health
checks and two five-operation flows. Cold run: 218 → 219 Orders / paidTotal 3000 → 3200.
Warm run: 219 → 220 Orders / 3200 → 3400. Every Payment updated its Order and Agreement.

| Operation | First run engine ms | First signed → commit ms | Warm engine ms | Warm signed → commit ms |
| --- | ---: | ---: | ---: | ---: |
| placeOrder | 1596.8 | 17339.2 | 934.8 | 4026.4 |
| preparePayment | 1104.9 | 1746.9 | 1121.9 | 1607.8 |
| acceptPaymentInstruction | 309.4 | 1223.1 | 264.4 | 698.5 |
| reportTransfer | 446.8 | 977.9 | 351.2 | 848.1 |
| acknowledgeTransfer | 570.8 | 10140.8 | 592.9 | 3597.1 |

These are one sample each, including cold/warm differences; they do not establish a controlled
baseline speedup. The change is kept for its tested reduction in repeated descendant verification,
not because the end-to-end goal is achieved. Cold acquisition and warmed add/acknowledgment
remain too slow. No operation is retried by resubmitting its business input.

## Iteration 31 — account roster reads (KEEP; deployment pending)

The original Users and Agents API reads now return actual account-local memberships, invitations
and provisioned agents. Users and Invitations require an interactive Owner/Admin; Agents permit
an active member. Current membership is checked for every read. API keys do not acquire account
management authority. Returned fields are explicitly selected so delivery tokens, credential
fields and internal metadata cannot leak through a future row extension. Queries stay inside the
selected account partition and have a 4096-row bound, matching the small account-control domain.

The existing implicit personal Owner is represented without inventing an Agent. A newly created
workspace exposes its already-provisioned, disabled and unqualified built-in Agent. Verified user
email is retained separately on `/me`, without changing profile revisions. Every existing user
owns an implicit personal account, so no roster read advertises an ownership-transfer capability.
Invitation sending/acceptance, membership changes, agent connections and mandate-sharing remain
separate unfinished operations. In particular the full Agents screen still needs mandate-sharing;
read-only roster support does not claim that page is complete.

Focused roster/account HTTP tests passed. The original SDK and Users-screen browser checks follow.
The API matrix now records 67 partially implemented operations and 49 pending operations.

The unchanged generated SDK passed all existing checks plus personal/workspace roster reads.
The original dashboard passed a 16-screen journey with no signed-in HTTP/browser errors; the
Users screen was visually inspected. Its first browser locator omitted the UI's existing
`(you)` suffix; the locator was corrected without changing the component. Invitation controls
remain visible from the original UI, but sending invitations is not implemented by this iteration.

## Iteration 32 — concurrent event projection boundary (KEEP; deployment pending)

The full iteration 31 host suite found an existing intermittent failure in
`CoreEventsTest.projectionIsBoundedAndConcurrentReadersCannotDuplicateFacts`: a reader could
read sequence 24, then another reader project the last two facts, then observe an empty outbox
and incorrectly return 24. That could temporarily hide committed events from a follow cursor.
A deterministic interleaving reproduced the same fault with two facts: expected sequence 2,
received 0. The fix rereads the committed sequence after observing an empty outbox. It adds
one metadata read to that idle projection path and changes no execution or publication work.
The original concurrent-reader assertion was preserved.

The deterministic regression failed before the fix and all five event tests passed afterwards.
Final host validation passed 631 tests, zero failures/errors/skips, in 1m24s; formatting and
distribution/qualification builds passed. Library source is unchanged since the 2493-test
iteration 30 validation. Iterations 31–32 preserve native execution and persisted graph formats.

Iterations 31–32 deployed as `8d6d6d7e8fb18df72c880c511be5f116fe78036e6f0a1332407f3897c1806b63`.
SSM `970398de-3758-4ab9-ae6d-5929eec33e49` completed successfully in 21.853s with
native Timeline and canonical graph health checks passing. The existing authorized AWS session
verified the real personal Owner, empty invitations and no invented personal Agent.

## Iteration 33 — native publication event references (KEEP locally; deployment pending)

The retained event feed previously recorded admissions only. New canonical graph publications now
atomically retain a compact event batch with the native head, immutable history and command receipt.
Root initialization produces Created, Processing History Appended and Epoch Advanced facts; later
applied root publications produce history and epoch facts. A rejected conditional commit exposes no
new event. Replaying a command, restarting, or losing the success reply does not duplicate events.
Projection assigns account sequence numbers separately from execution, with at most 24 facts per
transaction. A partially consumed batch retains its offset atomically with its sequence and IDs.

Retained event rows reference immutable publication history instead of duplicating arbitrarily large
emission payloads. Reads expand only selected events and include actual root emission identities.
The processing-history API and session-filtered event reads understand re-registered document aliases.
The new source cost is one small conditional row per graph publication, independent of sibling count;
there is no account-wide sequence read/CAS, graph scan or callback I/O in execution.

Scope remains partial: this is forward retention for canonical graph roots. Historical releases are
not backfilled; per-member native events, legacy execution events and the remaining source categories
still need adapters. Better history objects explicitly identify their own host format rather than
claiming Core's proof format. Webhooks additionally need a source subscription boundary and dispatch
implementation; their UI/API is not declared complete by adding event facts.

Focused native/event tests passed, including exact emissions, account isolation, alias reads,
restart/replay, rejected publication, lost success response and a 25-fact batch crossing the 24-fact
projection limit. A test declaration initially used `var` for two variables; corrected before running.
Full validation and automatic projection follow before deployment.

## Iteration 34 — automatic retained event projection (KEEP locally; deployment pending)

A single bounded background worker projects event facts outside document execution. Successful
commit hints wake it immediately; an existing DynamoDB work index discovers pending facts after
restart or a lost notification. Each turn handles at most four 24-event account batches, rotates
accounts, and keeps at most 1024 account hints. Work survives queue overflow and process shutdown.
Discovery is bounded to 32 rows per second; storage failures retain work and back off. An Events or
History read also wakes its account, allowing old, unindexed admission outbox rows to recover.

The original Events view now receives its existing live-update messages after projection. A first
pinned history request returns `read_model_not_ready` while source facts remain, rather than claiming
a partially projected prefix is complete. This preserves bounded API work; no source-body scan or
webhook delivery is added to graph publication. Native source gas, document identities and timing
semantics are unchanged.

Focused tests pass: restart without hints, two-account continuation/fairness, prompt wakeup,
worker shutdown, transient storage failure, legacy-row recovery, incomplete-history rejection and
account-isolated live notifications. The original generated SDK passes the new processing-history
and event-detail requests along with its existing account/document/timeline workflows.

Storage is additive, with no DynamoDB schema change. New event batches and immutable publication
references require this reader. Do not roll the console back to iteration 32 without retaining the
new event decoder or explicitly migrating these event records. Native graph snapshots themselves
are unchanged. No original source repository, Python package or GitHub resource is modified.

Full iterations 33–34 validation passed 639 host tests, zero failures/errors/skips, in 1m32s.
Formatting, distribution and qualification builds passed. The unchanged generated SDK passed.
The original dashboard passed an 18-screen browser journey; Events and Event Details were visually
inspected. Only the expected pre-login 401 occurred. The first browser check used an exact label
locator that did not match the original filter markup; its locator was corrected, with no UI edit.
The API matrix now records 68 partial and 48 pending operations, not full parity.


Iterations 33–34 deployed as `53ee15dc3744edced2b8ad81ffbe753e220411633af2908d619cdcce1b272375`.
SSM `75e5b18d-a713-44e2-8ca7-743ff6ab7b54` succeeded in 22.926s with native health checks passing.
The AWS business journey added Order 221 and a Payment, increasing Agreement paid total from
3400 to 3600. All five operations committed correctly. The unchanged generated SDK read five
new epoch events and processing-history entries, checked the latest epoch against the document
identity, and read the same event detail. This proves the tested root event path, not full API parity.

A bounded cold, profiled run measured engine / signed-entry-to-commit times in milliseconds:
placeOrder 3123 / 20472; preparePayment 2582 / 3417; acceptPaymentInstruction 347 / 1299;
reportTransfer 525 / 1075; acknowledgeTransfer 1052 / 12707. These are not acceptable latency
results or a controlled comparison. Five operations plus related reads accumulated 26.1 seconds
in timeline page verification across 20 calls. Immutable record/index decoding appeared in CPU
samples; storage phases overlap and must not be summed into operation latency. Only numeric
profile aggregates were returned; temporary recording and metrics files were removed on server.

## Iteration 35 — bounded immutable index decoding (KEEP)

The profile identified repeated index decoding on retained timeline record lookups. One radix
index now retains up to 256 verified immutable pages within a conservative 512 KiB weight budget.
Branch pages and String-valued leaves can be shared safely; generic mutable leaf values continue
to be decoded on every read. Reuse is scoped to that index and its tenant-bound BlobStore, never
across accounts. Root selection, publication, authentication and semantic checks are unchanged.
Byte-cache presence checks no longer clone a page merely to determine whether it is present.
This changes neither persisted formats nor Blue identities and introduces no dependency.

Focused timeline, private-access and index regressions passed. New tests verify warm reuse,
pinned old/new roots, byte-budget eviction, disabled caching, separate-store corruption rejection,
retry after a corrupt miss, and mutation isolation for generic leaf values. Full host validation
passed 642 tests, zero failures/errors/skips, in 1m27s, including distribution and qualification
builds. Library sources are unchanged. A bounded local in-memory probe used 512 entries, five
warm-up pairs and nine alternating samples of 2048 identical random lookups: median 9.432 ms
without decoded-page retention versus 1.775 ms with retention. Every returned value was checked.
This is a component measurement, not a claimed end-to-end AWS speedup.


Iteration 35 AWS deployment: artifact `4d9be98a872d3389cb12047bb29f3abce32ea62b1d6f0a25a2b4b48f5e041e17`,
SSM `1d720a50-91ac-40c5-9ddf-36b864bbdd19`, Success in 24.082s with native health checks passing.
The first journey attempt hit a non-JSON response during restart on its initial children read;
it submitted no operation. After deployment success, the full five-operation journey passed:
221 to 222 Orders, paid total 3600 to 3800. Engine / signed-entry-to-commit times (ms) were
placeOrder 1660 / 20900; preparePayment 1170 / 1978; acceptPaymentInstruction 356 / 1365;
reportTransfer 525 / 1118; acknowledgeTransfer 1000 / 13134. The microbenchmark benefit does
not establish an end-to-end speedup. Cold verification latency remains unresolved.

## Iteration 36 — original Agent settings boundary (KEEP; deployed)

The original Agents screen can use its existing generated SDK to create external Agent identities,
rename them, change read policy/inclusion, pause/resume them, and list actual approved connections.
Built-in Agents allow only inclusion changes and retain their disabled execution state. Settings
are account-owned metadata; this iteration does not implement OAuth, connection tokens, mandate
sharing or an Agent execution runtime. No new credential or native execution authority is issued
by these endpoints. Existing capability responses still report Agent execution unavailable.

CoreAgents uses the same conditional DynamoDB store as the host. A mutation atomically checks the
caller's current membership and account version, writes settings and retains a command receipt.
An account roster fence serializes creations at the original sixteen-external-Agent limit. Replay
returns the original wire result; conflicting input and stale revisions fail. A no-op retains its
revision. There are no scans of documents or calls into document processing.

Tests port the relevant defaults/replay/settings/permissions from upstream ExternalAgentAuthorityTest,
plus deterministic races for the final roster slot and admin revocation before commit, lost-success
reply recovery, account isolation, mutable settings validation and connection-secret exclusion.
Initial assertions compared Java numeric wrapper types across JSON storage; they now compare the
exact wire values, matching upstream's fingerprint assertions. Focused tests pass. Generated SDK,
original-browser and full validation follow. The matrix is now 71 partial and 45 pending operations.


Iteration 36 validation: 647 host tests passed, zero failures/errors/skips, in 1m25s;
formatting, distribution and qualification builds passed. The unchanged generated Core SDK passed
its complete existing journey plus new Agent calls. The existing 18-screen dashboard journey passed
with only its expected pre-login 401. A separate original-UI Agent journey verified built-in
inclusion, external rename/pause and empty actual connections across two additional screenshots.
Both were visually inspected. That journey explicitly records the existing signed-in 501 from
`account/mandate-sharing`; the whole Agents screen is not declared complete. Its first checkbox
action asserted state before the server update; the browser now clicks and waits for confirmation.
A pre-login refresh 401 is distinguished from signed-in failures. No original UI component or CSS
was edited, and library sources remain unchanged since iteration 30.


Iteration 36 deployed as `82b6096c7a83c4571e96c62e4c4d4b167b1c8f3032eb53bdbe079224230fd38e`.
SSM `6e2c9629-5c22-4af1-b509-f1a4d87252a9` succeeded in 23.018s with native health checks
passing. The unchanged generated SDK passed seven deployed Agent requests: create/replay,
pause/replay, connection list and roster verification (plus account discovery). One paused test
Agent remains in the existing test workspace. No credential was created, and neither OAuth nor
Agent execution was claimed. Existing document code is unchanged by this iteration.

## Iteration 37 — completed presence facts (KEEP; deployment pending)

Timeline profiling also found repeated traversal of abstract definition subtrees to decide whether
an object has a concrete value. The candidate retains exact-identity boolean results alongside the
verifier's existing schema/closure facts, bounded by 8192 entries / 2 MiB. Only a successful
protocol check publishes new facts. Changed identities are checked again; ordinary Language,
logical gas budgets and exported evidence receive no reusable protocol authority. This adds no
package or wire-format change. Focused and full qualification follow before retention/deployment.

Iteration 37 qualification passed: 2494 library tests and 647 host tests, zero failures/errors/skips.
Library check and Javadoc completed in 19s; the full host check, distribution and qualification
builds completed in 1m27s. A diagnostic stack captured during the host run showed its intentional
HTTP deadline test; the suite was not stalled. The cache can add at most 2 MiB per retained verifier
(up to 128 MiB across the host's 64 archive slots). It retains only identity/boolean pairs.
Retained; no AWS latency improvement is claimed before deployed measurement.

## Iteration 38 — shared local publisher/reader semantic work (KEEP; deployment pending)

TimelinePublisher can share its bounded completed semantic facts with a TimelineVerifier pinned to
the exact same Coordination edition, provider and timeline. The host selects this only after an
account-owned source lookup and only when both sides use the same AtomicStore object. Remote and
foreign-account reads retain normal acquisition. Publication does not grant reader permission.
Neither content, headers, signatures, ordering nor execution authority is shared by this change.
Definition and value validation remain distinct goals; changed constraints have distinct keys.

Focused tests pass for exact reuse, definition-versus-value separation, malformed content, failed
checks not entering the cache, source mismatch, forged signatures and mismatched operation markers.
The existing local transport tests still reject access after revocation and preserve exact wire
results. The first malformed fixture was rejected during YAML parsing; it now uses an exact invalid
node to reach the intended semantic-validation boundary. Full checks and deployed timing follow.

Full iteration 38 qualification passed: 2496 library tests and 647 host tests, zero
failures/errors/skips, plus Javadoc, formatting and both build artifacts, in 1m27s.
The unchanged generated SDK journey passed against the built loopback service. Retained.

Iterations 37–38 deployed as `4a0a53f37719888bf31fd3f826d8b0b48afcbf8cd2d524abb98821224e552a8e`.
SSM `e6fda3ff-fa82-4f44-9847-830fdf605b99` succeeded in 22.902s and native health checks passed.

## Iteration 39 — reuse completed local value admission (KEEP; deployment pending)

The operation endpoint already obtains a privately issued LanguageDocument for the reviewed
current state. After the independent native request/target check, its publisher can now retain
that exact value's detached semantic header and share it with the matching local reader. Only
VALUE admissions qualify; arbitrary content, definitions, different semantic identities and
oversized/literal headers cannot seed this reuse. The existing target cache remains capped at
256 entries / 8192 weighted fields. There is no new stored format, package change or credential.

This avoids reopening a previously admitted target closure for protocol validation. Membership,
route authorization, signed source verification, current permissions and native execution still
run. Additional refinements and changed identities cannot borrow another value's admission.
Focused library tests prove missing target bodies can reuse the exact privately admitted value,
while definitions, changed identities, ordinary Language calls and forged signatures cannot.
The first host test filter named a nonexistent CoreDocumentOperationTest; LocalTimelineContentTest
did run, but that focused command did not cover Core operation behavior. The complete host suite
includes the actual CoreOperationsTest, CoreOperationPreparationTest and CoreOperationContextTest.

Iteration 38 AWS workflow passed: Orders 222→223, paid total 3800→4000. Engine /
signed-entry-to-commit milliseconds were placeOrder 2282/17681, preparePayment 1259/1867,
acceptPaymentInstruction 317/1123, reportTransfer 592/4589, acknowledgeTransfer 951/10681.
This single cold run still has unacceptable verification latency; it is not a controlled speedup
claim. Iteration 39 qualification then passed 2498 library tests and 647 host tests, zero
failures/errors/skips, plus Javadoc, formatting and artifact builds in 1m27s. The original
generated SDK journey passed against that build. Candidate retained for deployed measurement.

Iteration 39 deployed as `c7d3f2fbabe63668df9fe66892052f6c710775782af5d029fe86f83d47c82cc8`.
SSM `2f5fe205-2941-4b19-b47f-11624220cc9f` succeeded in 22.978s with native health checks passing.
The five-operation workflow passed: Orders 223→224 and paid total 4000→4200. The first
placeOrder signed-entry-to-commit time was 4011ms (engine 1482ms); acknowledgment was
2220ms (engine 641ms). The preceding cold run measured 17681ms and 10681ms respectively.
These are separate cold runs, not a controlled throughput/p95 comparison. The observed reduction
is promising but remains above the requested latency; request preparation and engine work persist.

## Iteration 40 — original evidence selection API (KEEP; deployment pending)

Adds the original Documents SDK route for a bounded exact-evidence subset selected by epoch,
history position or associated signed entry. Authenticated cursors pin selection and manifest,
and bind account, document and page size. Returned values keep their original BlueIds.
The existing Better history/receipt format remains explicit; no native proof, completeness or
portability claim is invented. Reads do not scan unrelated documents or rebuild all history.
Tests cover exact value identities, equivalent epoch/position selections, pagination across new
publications and restart, selector boundaries, cross-account/document isolation and real signed
entry selection. Full API/UI qualification follows before retention/deployment.

The first evidence compile used expansion on LanguageContent instead of Language and was corrected.
The first tests found two real issues: malformed BlueIds escaped the API problem mapping, and warm
versus restored values used different descendant materializations. Both are fixed. Evidence now
opens only its root and collapses immediate complete children through the existing Language API,
producing one deterministic exact body without traversing a document closure. All original
byte-equality pagination assertions remain. The initial cross-document fixture registered the
same source twice and correctly deduplicated it; the test now uses distinct content and asserts
that the two document handles differ before testing isolation.

The generated SDK journey passed the new epoch/position/entry selection, pagination, unlink and
re-registration cases. The existing original-UI browser journey passed all 18 screens with only
its expected pre-login 401; History was visually inspected. No original UI component or CSS changed.
The API matrix is now 72 partial / 44 pending. Broader native receipt parity remains explicit.

Additional representation tests cover scalar values, exact large integers, bare/typed lists, escaped
field names and two-member cycles. The cyclic test exposed that ordinary cyclic expansion deliberately
serializes as a reference; the evidence adapter now uses the existing selected-fragment API for its
finite own body. It does not return the owning-set proof or claim the body independently proves its
cyclic BlueId. The scalar fixture uses the Language API's required quoted decimal form.

Final qualification passed: 651 host tests, zero failures/errors/skips, formatting and both build
artifacts in 1m23s. The SDK rerun first hit the local sandbox socket-bind restriction; rerunning
with the authorized loopback permission passed against the final artifact. Library code is unchanged
from iteration 39 (2498 tests previously passed). No skipped endpoint is represented as implemented.

Iteration 40 deployed as `f2609eab1fc403f63686d9cc0e90da71434a08eafc91c1c486bc669a7e90fc4f`.
SSM `6cbebea7-f40f-4201-a8a7-af9279f0f649` succeeded in 22.904s; native health passed.
The original SDK selected epoch 308, paged all five retained evidence values, selected the same
position, then selected its signed triggering entry on AWS. All assertions passed; seven evidence
reads took 13.0s from this client in total. This is read-side retrieval, not a document execution
measurement. No account/document mutation was performed.

## Iteration 41 — source commit boundaries for callbacks (KEEP; deployment grouped with webhooks)

Before adding webhook delivery, establish the same committed-source cut required by the original
API. A subscription must not receive old events merely because projection was delayed. Source
transactions attach an account generation under a conditional gate read; creating a subscription
advances 32 gates in the same transaction as its configuration. Source writers only read/check one
gate per touched shard, never increment a shared account counter. Callback work remains separate
from document processing. Old records have generation zero, and internal generations never enter
public event bodies. No webhook endpoint claims completion in this iteration.

Iteration 41 passed the full host check and artifact builds in 1m23s, plus the original SDK
journey. Six ordering tests cover source/subscription races, mixed strong reads, delayed projection,
legacy records and account isolation. The mixed-read test was tightened after the full run and
passed its focused rerun. A source publication adds one strong gate read and one condition per
touched shard, with no gate write; no AWS latency claim is made for this addition yet. Retained
for the webhook implementation. Once subscriptions are created, downgrading to a writer without
these fences would miss new callbacks; rollback must retain the fenced writer or suspend/drain
subscriptions explicitly.

## Iteration 42 — native callback transport and key custody (KEEP; deployment grouped with webhooks)

Uses Apache HttpClient 5.6.5 explicitly (HttpCore 5.4.4) for HTTP framing, TLS hostname verification,
connection management and cancellation. The public-address resolver is installed in the client,
so DNS validation and connection selection cannot resolve a hostname twice. Redirects, cookies,
automatic retries and ambient credentials are disabled. DNS has a 2s bound, connect 3s, socket 5s,
and each attempt a 10s overall deadline; unused response bodies are closed immediately. Separate
bounded callback/DNS resources never run in document workers. The original handwritten HTTP
transport is not copied. The supported extension points were checked against Apache's
[DnsResolver](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/DnsResolver.html)
and [HttpClient](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/classic/HttpClient.html)
documentation and compiled/tested against the pinned release.

Native-v1 timestamp/body HMAC bytes are unchanged. JCA AES-GCM envelopes bind stored signing keys
and replay responses to account/resource contexts. A separate retained Secrets Manager secret is
generated entirely in AWS and selected by exact version; provider signing keys are not reused.
The provisioning tool reads only version metadata and writes only public pins. Rotation requires
explicit re-encryption, not changing an alias. Infrastructure tests check the retained resource,
exact read-only IAM grant and account/region/version validation. No new AWS resource exists yet.

The initial test declaration had an invalid compound `var` declaration and was corrected. All seven
initial transport/crypto tests passed over real loopback TLS. Additional pinned-key coverage and a
stricter literal-private-address assertion are in final qualification. Updating dependency locks
first failed because the existing maintenance task is incompatible with Gradle configuration
caching; it succeeded with `--no-configuration-cache`. The infrastructure test was first launched
from the wrong import directory, then passed from `scripts/`. No endpoint claims webhook delivery
until the configuration/queue adapter is implemented and verified.

Iteration 42 qualification passed: 665 host tests, zero failures/errors/skips, formatting and
artifact builds in 1m32s; the original SDK journey and both infrastructure tests passed. The public
IP-literal assertion verifies DNS-policy rejection specifically, rather than accepting a connection
failure as evidence. Pinned-key tests verify exact-version selection, restart, account/context
isolation, ciphertext tampering and secret-safe failures. Native HMAC is checked against an
independently calculated vector. Retained; configuration/queue integration is next.

## Iteration 43 — original webhook dashboard and durable callbacks (KEEP; AWS qualification pending)

Adds the ten original webhook routes without changing dashboard components or CSS: create/list,
read/update/delete, health, delivery list/detail, attempt pages and manual retry. Create and exact
idempotent replay disclose the signing secret; other representations omit it. Stored secrets and
command replies use account/resource-bound AES-GCM envelopes. The retained key from iteration 42
is now provisioned in the authorized AWS test account and pinned in `infra/webhook-secret.json`;
this contains only public identifiers. Deployment refuses to drop existing callback custody.
The original provider key is unchanged.

Source generation fences exclude pre-subscription events even when projection is delayed.
Staging atomically advances a bounded cursor and publishes retained delivery facts. Exact public
event bytes are stored once and reused for every HMAC-signed attempt. One persistent sender lease
per subscription preserves order and prevents manual retries from overlapping a different active
delivery. Token-fenced completion, expired-lease recovery, bounded transient retries and durable
manual retry receipts survive restart. Deletion disables scheduling while retaining history.
An attempt already in flight can complete after deletion; deleting a subscription is not a network
recall. Four separate callback workers and bounded transport/DNS resources keep receiver I/O out
of document execution workers. Discovery retains excess rows instead of starving the fifth hook.

Current administrator membership or API-key validity is checked again under the configuration
commit. Better API keys remain account-owned, unlike original membership-generation-bound keys;
command receipts also still use account/resource scope rather than the complete original identity.
These are explicit remaining parity gaps. Other bounded choices: 256 nondeleted subscriptions per
account, 4 MiB event payload limit, FIFO per subscription, and a paginated attempts endpoint after
100 attempts. Filtered lists may return an empty page with continuation. Only existing admission
and publication event producers are covered; accepting every selector does not invent missing
source events. Oversize callback payloads currently block that subscription's staging and require
operator handling. The full production load/cost behavior of idle subscription polling is unqualified.

Qualification: 678 host tests passed with zero failures/errors/skips; formatting, installed runtime
and qualification artifacts passed in 1m27s. The original SDK exercises configuration, replay,
health and deletion. Twelve focused lifecycle tests plus live invalidation cover secret disclosure,
source cuts, selectors, pagination, foreign accounts, key revocation including commit races, exact
payload/HMAC, transient and manual retries, eight-attempt exhaustion, stale completion, restart,
pause/deletion, the 16-event staging boundary and five subscriptions across four workers.
The reused UI browser journey passed create/details/pause/resume/filter/delete. Screenshots were
inspected after dismissing the signing secret. The original UI makes a concurrent detail request
when deleting, yielding an expected 404; the final list has no alert and the removed row is absent.
Initial checks exposed an outdated pending-endpoint assertion and test-only status selector; both
were corrected. No required behavior test was weakened or skipped.

A bounded temporary AWS Lambda receiver will deliberately return 503 once per delivery and then
204. It stores only body hashes and delivery/signature metadata in an isolated S3 prefix. Its
unguessable endpoint is private test state, not published configuration. The receiver stack and
its exact evidence prefix will be removed after original-SDK callback/retry qualification. The
retained webhook encryption key is application custody and remains deployed.

Iteration 43 AWS acceptance passed. Artifact
`c70112bdf5e43eead87ba0a9090cdf1830522d8df465b438844be7a454cd3ae4`, SSM
`c377b206-8c9d-4e46-938d-0f995f303643`, succeeded in 23.034s with native health passing.
The unchanged SDK created the temporary subscription and one synthetic content admission; both
admission events were delivered after a deliberate 503, then 204. Manual retry added exactly one
successful attempt and its command replay added none. Paginated attempts, health, pause/resume and
idempotent deletion passed. The five received bodies matched exact Core event bytes and all five
native-v1 HMACs verified. The receiver stack reached DELETE_COMPLETE and its isolated S3 prefix
was verified empty. The application encryption key remains intentionally retained.

The bounded five-operation Agreement/Order/Payment flow passed on AWS, advancing the main graph
from version 309 to 314 and 224 to 225 Orders; payment and order each report 200, and agreement
paidTotal advanced from 4200 to 4400. Engine times: placeOrder 1577ms, preparePayment 1076ms,
acceptPaymentInstruction 377ms, reportTransfer 636ms, acknowledgeTransfer 882ms. Corresponding
signed-entry-to-commit times: 4313, 1634, 1050, 1309, 2645ms. These include different work and cold
state; this single bounded run does not establish a throughput claim or isolate the new source-fence
cost. The low-latency goal remains unmet. Raw reports remain in ignored local qualification state.

## Iteration 44 — account roles and selected-context lifetime (KEEP; deployment grouped with credentials)

The owner-only adapter previously treated every active human member as an account writer.
Before exposing invitations, add one role policy shared by dashboard routes, compatibility routes,
owned native-provider commands and live-stream admission. READ_ALL can read account data but cannot
write; WRITE can submit document/content work without managing administrator settings. Account
capabilities now reflect the actual membership. READ_SELECTED fails closed on private resources
until proven representation coverage is implemented; public runtime/identity metadata remains
available. API-key management is temporarily administrator-only while credentials are still
account-owned; the next credential iteration must bind new keys to their human membership before
allowing other roles to create them.

Selected-session contexts include membership generation after a rejoin; old selected and user-only
contexts cannot silently regain access to a new generation. Initial-generation contexts retain their
existing encoding for deployed clients. Account and membership reads use one bounded batch, and
an immutable membership snapshot is reused throughout request routing. Disabled accounts are denied.
This iteration does not yet expose membership mutation or claim a cross-request revocation barrier;
those must land before invitation/membership commands are enabled.

Iteration 44 passed 682 host tests, zero failures/errors/skips, formatting and both runtime artifacts
in 1m30s. The unchanged SDK and eighteen-screen browser journey passed; its only HTTP error was
expected pre-login 401. The role matrix caught a missing API-key branch during implementation;
that branch and the owned native-provider route now use the shared policy. The final suite passed
after those corrections. Kept as a prerequisite for membership-bound credentials and membership
commands. No new endpoint is counted complete and no new AWS performance claim is made.


## Iteration 45 — membership-owned credentials and exact secret replay (KEEP; deployed)

New keys bind to a human and membership generation, use the current role on every authentication,
and cannot become effective again after removal/rejoin. Their versioned `myos_better_u1_` prefix
is rejected by older runtimes, preventing rollback from interpreting a reader key as a legacy
account-wide credential. Existing account credentials retain their original format and authority;
old creation command retries recover their original metadata rather than minting a second key.
New keys are listed and revoked only by their issuer. Legacy account keys remain visible to admins.

New creation replies are encrypted and recoverable for 24 hours, matching original Core's replay
contract. JCA AES-GCM envelope handling is factored into `SecretEnvelope`; credential and webhook
contexts use distinct domains under the existing version-pinned custody key. A fixture generated by
the actual deployed iteration-43 binary proves existing webhook ciphertext still decrypts unchanged.
The callback HMAC format is unchanged. Missing custody refuses new key issuance. No raw credential
is persisted, logged, embedded in a document or placed in a report.

The unchanged SDK's default key listing is now complete, rather than silently stopping at the first
50 keys. Explicit pagination remains supported and binds user, account, generation, role and limit;
4096 unpaged keys require the pagination extension. Legacy metadata names and DELETE behavior are
preserved. Old legacy pagination cursors must restart after upgrade. Original source membership
control and writer barriers remain future work; current mutations fence issuer membership under the
same atomic commit, but this iteration does not yet expose invitation or membership-change routes.

Iteration 45 passed 693 host tests with zero failures/errors/skips, formatting and both artifacts
in 1m28s. The original SDK passed exact key replay, current owner identity, bearer document reads,
revocation and refusal to replay a revoked key, alongside its full existing document/timeline/
webhook journey. The reused API Keys UI passed create, bearer read, secret dismissal, reload,
revocation and subsequent bearer rejection; both screenshots were taken after secret dismissal,
and the revoked-key screenshot was visually inspected. Only expected pre-login 401s occurred.

Ten new credential tests cover recovery, concurrency, expiry, complete listing, user/generation
isolation, malformed ownership failing closed, role changes, removal/rejoin and legacy compatibility.
The added HTTP test proves the same reader key cannot write through Core, legacy or native owner
endpoints. An initial legacy DELETE regression required an idempotency header where the old route
never did; it was corrected without weakening the existing test. Two test declaration syntax errors
were fixed before final qualification. No library or document-processing code changed, so no new
execution speed claim is made. Kept; deploy together with iteration 44.

Iteration 45 deployed artifact `9b3cde8a1fdb9aeb7a359e5040dddf5432ac40288e628bcb41a4f9e5817fb207`
with SSM command `d84b15bb-54eb-42b0-b570-b246be6b634e`, successful in 24.191s. Runtime health
passed. The original SDK on AWS passed exact secret replay, current membership identity, bearer
document access, content admission, denial of machine credential management, revocation and
401 on subsequent bearer use. Secret replay after revocation returned 409 as required. The
temporary test key is revoked. No business document or library code changed in this qualification.

## Iteration 46 — request revocation barrier (KEEP; deployed)

Use per-active-account JDK fair read/write locks at the public HTTP boundary. Ordinary requests
share a read lease; credential revocation and future membership changes take the exclusive lease.
Reauthenticate after acquisition, so a queued request cannot reuse a credential checked before
revocation. Read bounded request bodies before entering the lease and release it before sending
a response; stalled clients must not hold account authority. This is a single-owner host barrier,
not a distributed lock or a cancellation of native entries already admitted for processing.

Iteration 46 passed 699 host tests, zero failures/errors/skips, formatting and both runtime
artifacts in 1m29s. The original SDK journey and reused API Keys browser journey passed; the
revoked-key screenshot was visually inspected. Six new tests cover shared readers, exclusive
ordering, unrelated accounts, interruption/idle cleanup, HTTP admission/revocation ordering and
a partially uploaded request. The first upload-test publisher sometimes withheld request headers;
replaced it with JDK SubmissionPublisher and reran the full suite successfully. Product behavior
and assertions were not weakened. The original UI and library are unchanged.

The barrier covers Core, legacy and native owner routes; live streams recheck authorization at each
frame selection. Gates are retained only while active owners/waiters use them, with a 30-second
retryable acquisition limit. JSON/YAML body transfers now have a 15-second budget in addition to
the existing 1 MiB limit. Response transmission occurs after releasing authority, so already-selected
bytes may arrive after revocation; this is not a transport-drain guarantee. Native inputs already
admitted still process under their native authorization. No new document-processing speed claim
is made. No persisted record format changes; downgrading loses the request revocation guarantee.

Iteration 46 deployed artifact `8f6be6a50b3067a962d1d1327ab3bb0e65f90fdae2be2f78feb145e9b996db07`,
SSM `c87a8e36-9bd6-4145-b490-d6a282d2857c`, successful in 23.492s with native health passing.

## Iteration 47 — membership transitions (KEEP; deployed)

Port original membership revision checks, no-op receipts, last-admin protection and historical
self-removal replay through the existing PATCH route. Use the account row as the atomic roster
fence and retain an O(1) active-administrator count after one bounded adoption of old metadata.
New OWNER assignment remains explicitly unsupported until global user/email ownership claims
are migrated; Better currently has implicit personal owners, unlike original Core's ownership
registry. Role changes, Owner release to Admin and removal must not pretend that registry exists.

Iteration 46's deployed key lifecycle passed the original SDK: exact replay, membership identity,
document reads, content admission, machine-management denial, revocation and refusal of revoked
secret replay. The temporary credential is revoked. No business documents were changed.

Iteration 47 passed the full 707-test host suite with zero failures/errors/skips and runtime
artifacts in 1m28s. The original SDK passed Owner release, exact replay, stale revisions and
last-admin protection; the reused Users page passed role save, durable reload and disabled
last-admin removal. The final Admin screenshot was visually inspected. Eight new
tests cover original membership invariants, direct concurrent source commits, HTTP self-removal
recovery and immediate membership-key invalidation. The cross-account receipt assertion was
expanded after the full run and the focused membership suite passed again; product code did not
change afterward. An initial error-code mismatch for READ_SELECTED callers was corrected to
original Core's administrator-required response, without changing authorization.

New metadata is `activeAdminCount`/`membershipRevision` on account records and actor/account-scoped
`MEMBERSHIP_COMMAND#` receipts. Existing roster data is adopted on the first changed membership
under account CAS. Every future invitation/rejoin writer must update that account fence and count
atomically. Removed personal membership no longer reappears through implicit-owner discovery.
No native document format, library or business runtime changed. Ownership promotion, invitation
delivery/acceptance and original distributed restriction projections remain pending; this route
is counted partial, making the matrix 83 partial and 33 pending.

Iteration 47 deployed artifact `5e9096c5bea85b0a518551b25be1f1026592fa27292dbf4f6b93c5a330484d1c`,
SSM `808c9964-3584-40c5-a0c6-6cbbc3995ec1`, successful in 25.337s with native health passing.
The original SDK on AWS passed an exact no-op membership command, replay, changed-command
rejection and invalid-revision rejection. The user's actual role and generation are unchanged.

The five-operation AWS workflow passed, advancing the existing Agreement from 225 to 226 Orders
and paidTotal 4400 to 4600. This run is materially slower than iteration 43: engine times were
2952/2029/4112/585/1369ms for placeOrder/preparePayment/acceptPaymentInstruction/reportTransfer/
acknowledgeTransfer; signed-entry-to-commit times were 11744/3157/9011/1119/5051ms. These are not
acceptable performance results. No document-processing code changed in 44–47. During the run,
the 2 GiB JVM heap was 96% occupied (2016095 KiB used), with 157% process CPU, no cgroup CPU
throttling and no cgroup OOM events. Heap retention/allocation and GC are being investigated;
that diagnostic alone does not establish the cause. No further timings are compared across
forced-GC diagnostics.

## Iteration 48 — historical member identities (KEEP; deployed)

The AWS profile located a concrete dashboard regression: reading an embedded member's detail
constructs its predecessor history position. GraphDocuments.publication previously re-admitted the
entire historical member content and its dependencies merely to read its BlueId. In the bounded
60-second JFR recording, 392 execution samples included this publication path; 383 included the
CoreDocuments.read route. This is HTTP dashboard work, not evidence that the event-projection
worker is the main culprit. The recording had 14 full collections with 22.986 seconds of pauses,
plus 86 young collections (1.465 seconds). Allocation sample weights are estimates and are not
reported as exact allocated-byte totals. A subsequent read of the existing embedded Order's
metadata timed out at the client's 60-second read limit before producing a result.

Use the already committed, content-addressed member metadata to select a historical BlueId.
The lookup traverses the document radix index and reads one metadata record; it does not traverse
Language dependencies. Actual content reads still perform Language admission. The account and
immutable publication remain the selection boundary, including when the graph advances during a
read. No cache, new library, native semantic rule, gas policy or persisted format was introduced.
No original UI component changed. The existing public API response is preserved.

Three new cases verify old/new member identities against canonical inline execution and bound cold
storage reads for 1 and 32 members (the latter crosses the radix leaf boundary), including missing
members, unavailable content and cross-account isolation. Initial fixtures mistakenly used scalar
embedded documents and expected the wrong exception family; corrected the fixtures and assertions
without relaxing product validation. The full 710-test host suite passed with zero failures, errors
or skips in 1m25s. Original SDK and the reused 18-screen dashboard journey passed; the final
reattached history screen was visually inspected. All owned raw JFR/configuration/helper files were
removed from the instance and absence verified. Library sources remain unchanged. AWS latency
qualification is next; no speedup is claimed from local verification.

Iteration 48 deployed artifact `2b0127f19c370d4a95e5b75763ee216c1ec65e07019de568134bc07720f61b13`,
SSM `09143c71-e946-4a5b-a636-cdf1661a2f65`, successful in 53.477s with native health passing.
AWS Order metadata read: 10207.681ms first after restart, then 81.862ms server time. Payment:
315.725ms then 80.695ms. These are bounded individual observations, not latency percentiles; the
baseline Order request timed out, so no numerical speedup ratio is inferred. Cold execution-session
loading remains unnecessary on metadata paths and is the next target.

The real five-operation flow passed: 227 Orders, latest Payment/Order value 200, Agreement paidTotal
4800. Engine times in operation order were 1631.715/1571.640/371.600/475.314/570.073ms;
signed-entry-to-commit was 4747.137/2204.045/1309.925/1208.165/2830.021ms. Results are correct
and better than the GC-heavy iteration 47 run, but still above the requested low latency.

The post-workflow iteration 48 JVM counter read (no forced collection) showed 0 full GCs after
4m53s uptime, 73 young GCs totaling 3.180s, and 1228288 KiB used of a 2097152 KiB maximum heap.
This is a different process lifetime from the earlier recording, not a controlled GC speed ratio.

## Iteration 49 — metadata without execution-session loading (KEEP; deployed)

Head, birth/history and publication identity reads now use only committed metadata. Add an optional
`members` radix index to `myos.canonical-pages/1`, mapping the existing stable host address suffix
to its native member key. Only added/removed keys change the index; an ordinary member state change
reuses its root. This index is host metadata and never enters a Blue document, canonical snapshot
identity, proof, gas value or operation result. Root documents keep their existing unsuffixed address.

Old manifests remain readable through their compact document metadata index; the next publication
adds the new index. Prior readers ignore the optional field and still reconstruct the identical
canonical snapshot. No external or in-place migration is needed. Actual content inspection and
execution retain their existing Language validation and session behavior. Metadata does not acquire
the graph execution lock, so an old immutable publication remains readable while new work proceeds.

Regression tests prohibit reads of the execution content catalogue while reading a cold member's
head, birth, history and identities. They also check old/new manifest reconstruction, adoption,
unchanged index-root reuse, rejection of a fabricated root alias, cross-account reads, missing
members and bounded index lookups across a radix branch. Initial test fixtures attempted to patch
inside an embedded managed boundary and compared Java numeric classes across a JSON boundary;
corrected them to a child-owned workflow and exact serialized snapshot comparison.

Full host verification passed: 712 tests, zero failures/errors/skips. The unchanged generated SDK
and full dashboard browser journey passed; the History screenshot was visually inspected. The
library was not modified. The API matrix remains 83 partial / 33 pending; performance changes do
not count as completion of pending features. AWS cold metadata and indexed publication checks follow.

AWS acceptance passed for artifact `fdc37a61f5b93770f6848f7d0ace4236126f371bf463a695151e7164aa0294df`,
SSM `f25e293f-1f76-4aa5-8343-68d5c06a4862` (22.949s, native health passing). First Order metadata
read after restart took 595.757ms server time, then 94.263ms; Payment took 245.949/78.014ms.
After new indexed publications, Order took 274.657/65.500ms and Payment 205.485/71.725ms.
These are individual observations, not percentiles or a controlled throughput comparison.
The original dashboard's Order and Payment History screens rendered in 3640/1306ms with no
browser/API errors; the Order screenshot was visually inspected.

The five-operation workflow added Order 228 and increased the Agreement paid total from 4800 to
5000. Payment and Order both reached 200. Engine times for placeOrder, preparePayment,
acceptPaymentInstruction, reportTransfer and acknowledgeTransfer were respectively
1752.307/1745.908/437.589/499.911/665.492ms. Signed-entry-to-commit times were
4548.745/2430.498/1146.053/1082.401/2328.775ms. Metadata latency improved; this does not show a
business execution speedup. The requested low latency and complete API parity remain unmet.

## Iteration 50 — account-managed reader custody (KEEP; internal prerequisite)

The original import form accepts an existing managed reader key; its removed key-management
routes intentionally return 404. Keep that boundary rather than inventing another UI/API.
Port the internal account-owned key selection, exact creation replay and deletion semantics as
the prerequisite for remote imports. Use JCA Ed25519 and the existing authenticated encryption
envelope, with a separate domain and account/key associated data. Public indexes contain no
private material. No existing source-bound AWS reader custody is migrated or replaced.

Seven focused tests passed in a two-second Gradle run. They port the original selector cases and
verify exact restart replay, tenant isolation, native signature verification, deleted-key rejection,
tombstones, missing custody, malformed input, resource/domain-bound ciphertext rejection, concurrent
creation and recovery after a lost commit reply. The public-key index makes selection constant in
the account's number of keys. Secrets stay out of public projections and parser diagnostics.

This is internal infrastructure, not a completed import feature: provisioning and source polling
integration follow. No HTTP route was added and no AWS reader key was created. These focused checks
do not replace the full host/SDK/browser checks required when the importer is connected. The API
matrix remains 83 partial / 33 pending. Retain the prerequisite; do not deploy an otherwise unused
class separately.

## Iteration 51 — verified import discovery and managed polling (KEEP; locally qualified)

Add read-only provider discovery using the existing native `TimelineReader`, exact selected
Timeline identity and account-owned signing custody. The endpoint's signed descriptor must bind its
provider, public control Timeline and URL. The signed content response must correlate to the exact
request and Timeline. No source is retained during discovery. UTF-8, response size and an overall
15-second network deadline are bounded. The eventual public HTTP adapter still needs a transport
that rejects private destinations; this discovery class alone is not exposed as an endpoint.

The first tests exposed two issues. Materializing an inherited view changes a provider's exact
identity, so discovery now selects its native exact identity and admitted evidence instead. Also,
the existing provider did not serve a newly created empty Timeline's own definition because its
entry-content index was empty. The provider now serves that retained definition after ordinary
reader authorization; unrelated content still requires source membership.

Managed polling registrations retain only key ID/public key. They reuse the existing directory,
native verifier, acquisition worker and retry records. Every acquisition checks live managed custody
before using a cached signer or an idle shortcut. Existing AWS source-bound registrations are
unchanged. Older readers reject the new reader selector rather than treating it as public access;
rolling back after adopting managed sources requires a reader-aware binary. No native Blue data,
identity or proof format changes. A request already in flight at deletion is not recalled.

Seven new tests cover empty-source discovery, signature/identity/endpoint substitution, access
denial, account/deleted-key isolation, malformed/oversized bodies, source adoption and restart,
and cached-worker deletion with no subsequent provider call. Focused native publisher, private
acquisition and directory regressions pass. The dashboard import endpoint and provisioning remain
unconnected; no API parity count is changed. Full shared-runtime verification follows.

Full host verification passed: 726 tests, zero failures/errors/skips, in 1m28s. The unchanged
generated SDK and reused 18-screen dashboard journey passed, with only the expected pre-login 401.
The rendered History screen was visually inspected. No library/UI change or AWS deployment in
this iteration. Keep the native fix and acquisition support, and connect the import adapter next.

## Iteration 52 — original Timeline import workflow (KEEP; local qualification)

Connect the existing import and retry endpoints to managed reader custody and the native source
directory. The unchanged Core form imports an exact signed Timeline, lists its public provider
access history, displays retained entries and retries acquisition. Another account's Timeline remains
read-only. Repeated imports preserve the selected endpoint/key and metadata; source registration
can resume after metadata publication without changing provider pins. No additional UI was designed.

Apache HttpClient 5 owns HTTP/TLS. The importer and webhooks share the existing public-address
validation through its connection DNS resolver, including mixed/public-private rejection. Requests
have bounded bodies, connection capacity, DNS waits and whole-body deadlines; redirects, automatic
retries, cookies and ambient authentication are disabled. The operator-only `reader-key` command
provisions encrypted managed custody and returns public metadata, never private key material.

Entry detail uses a binary search over verified, monotone acquisition completeness followed by
matching the original occurrence in that batch. Presence in retained content alone is insufficient.
There is no full-history scan, new native identity or archive format. A 33-cut boundary test uses
at most seven history reads for first/middle/last entries, including restart and pinned old views.
Unclosed successors and entries embedded merely as message content cannot appear as published
occurrences. Existing archive rows are read directly without migration.

The SDK exposed an integration conflict when imports preceded owned-document bootstrap: both
paths tried to assign different aliases to the same public control Timeline. Owned bootstrap now
reuses its existing verified registration, with both a native execution regression and an SDK
import-then-bootstrap sequence. The provider/Timeline directory pins remain enforced.

Persistence impact: new `CORE_IMPORTED_TIMELINES#account` metadata and the managed reader selector
from iteration 51. The same pinned application custody key is used with the distinct reader-key
domain. Existing owned/AWS reader registrations are unchanged. Do not roll back to a binary without
managed polling support after importing. Import status exposes schema-complete sanitized issues;
the original host's latched integrity-error/recovery lifecycle and broader access/delegate imports
remain explicit parity gaps. Current automatic acquisition does not latch a business ERROR merely
because a provider response failed verification; the last verified prefix remains authoritative.

Full host result, browser/SDK checks and AWS acceptance are recorded below when complete.

Validation: 736 host tests passed, zero failures/errors/skips. The first final run flagged only
Google Java Format's wrapping of a conditional diagnostic; shortening those messages resolves
the formatting issue without changing behavior. The generated SDK passed the complete journey
including import-then-owned-bootstrap, exact entries, retry, read-only denial and all previous
account/document/history/webhook scenarios. The unchanged 18-screen dashboard journey and new
two-screen original import-form journey passed with only expected pre-login 401 responses. The
imported Timeline screenshot was inspected. The library and dashboard sources are unchanged.

The API matrix now has 85 partial / 31 pending operations; none is claimed fully equivalent to
all original Core authorization and lifecycle semantics. AWS acceptance follows deployment.

### AWS acceptance — iteration 52

Artifact `9d4d14f8682113972df725bdc1c8d2061c1ac8215cbe6578ac3b96f026125670`
was deployed to the existing authorized test stack; SSM command
`a6099335-ec6f-4d1d-ba33-5d93e7202071` succeeded in 23.098s and native health passed.
The reader provisioning command returned public metadata only. Its encrypted custody row, one
workspace-owned test Timeline and reader grant are retained deliberately for reproduction.
No external email, account transfer or global-admin authority was created.

The original browser form imported the workspace Timeline into the personal account, displayed
read-only status and the original entry, and retried acquisition. The account-selection-inclusive
journey failed its no-errors assertion on the known `/admin/session` 501 stub. This failure remains
open; it was not reclassified as success. A separate direct import-form journey passed with zero
API/browser errors and a 705ms replay-to-details observation. The rendered entry was inspected.
API verification proved native entry identity, exact detail/list equality, replay and append denial.
Server times: replay 82.040ms, entry page 21.295ms, direct entry 23.237ms. Client times were
827–891ms using separate HTTPS connections. These are individual observations, not percentiles.
This is a same-provider cross-account AWS check; remote TLS/network failure behavior is covered
by the local real-TLS transport tests, not an independent deployed provider.

The original five-step business workflow added Order 229 and increased paid total 5000 → 5200;
Payment and Order both reached 200. Engine times were 1884.444/1169.160/346.425/780.170/590.951ms;
signed-entry-to-commit times were 4920.593/1749.790/1066.573/1424.328/2309.733ms.
The import changes preserved business results; this does not establish an execution speedup.
See `TIMELINE-IMPORTS.md` for the retained example and operator provisioning contract.

## Iteration 53 — native provider guarantee API (KEEP; locally qualified)

Implement `POST /timelines/{timelineId}/guarantees` with the original range-proof response schema.
The owned provider signs a native Completeness Guarantee/Timeline Attestation at the selected
already-established fence. Future/unestablished bounds fail before any receipt is retained. This
endpoint does not seal the clock forward or fabricate received business completion. Imported read
access returns the original `provider_proof_unavailable` error; its remote provider owns proofs.

The durable command selection is written before materializing a page. Retry after an append,
restart or lost response preserves the original fence and entries. Fingerprints bind Timeline and
normalized selectors; cursors bind account/resource/full selection. Timestamp lookup is binary,
and pages touch only the requested occurrences plus the selected terminal entry. Native signatures
and content identities are checked in tests, including a complete range admitted by TimelineVerifier.
No library, UI, native storage/proof identity format or processing-hot-path change. New internal
`CORE_PROVIDER_RECEIPTS#account` rows are additive; older binaries do not serve the new endpoint.

Compatibility decision: the pinned original implementation rejects all guarantee query parameters,
but its generated SDK/OpenAPI explicitly expose `limit` and `pageToken` in the query as well as the
body. Accept both published forms; identical duplicates agree and conflicting duplicates fail.
The returned proof covers the exact requested established boundary, whereas the original hosted
provider may return a proof for a later established boundary. Both are native valid completeness
proofs for the requested page; Better keeps the proof identity stable across its pinned pages.

Focused host tests passed (including prior Timeline/import regressions), format/build checks pass,
and the complete generated SDK journey passed with actual signed guarantees after a native
operation. It checks exact proof content retrieval, replay, conflicts and imported-proof denial.
The API matrix is 86 partial / 30 pending. AWS remains iteration 52; deployment is grouped with
the next bounded API iteration. Full shared verification will precede that release.

## Iteration 54 — administrator discovery boundary (qualification)

The original account chooser probes `admin/session`; Better previously returned a 501 stub.
Port the original separate administrator allowlist, so ordinary account Owners and Admins get
`403 ADMIN_REQUIRED` and the original UI hides its Admin Panel destination. A verified allowlisted
human receives the original userId/email/sessionContext response. The check uses the base user
session and ignores workspace selection. API keys do not acquire administrator authority.
Other admin endpoints authorize first, then remain explicitly unavailable to authorized operators.

`CORE_ADMIN_ALLOWLIST_SECRET_ARN` optionally selects an AWS Secrets Manager JSON object containing
only `emails`, in the service AWS region. The official AWS SDK owns retrieval and retry; initial
load and ten-minute refresh run on one background worker. Initial failure denies everyone, later
failure retains the last accepted list, matching original Core. The list is bounded to 100 emails
and 64 KiB; request authorization reads an immutable in-memory set. No secret retrieval, directory
scan or document activation occurs in the check. Shutdown closes the client exactly once.

No allowlist secret, role grant or administrator was created in AWS. The current deployment remains
empty/deny-all. Deployment plumbing for granting access, original global credential revocation,
directory/notification administration and impersonation remain separate parity gaps. Original
AdminApiExamplesTest assertions are adapted into focused tests and generated-SDK calls; the whole
original PostgreSQL-backed harness is not claimed to run unchanged. No native format or processing
change. The matrix has 87 partial / 29 pending operations, with no claim of full semantic parity.

The first full run exposed a test-helper mistake: its convenience parser required HTTP 200 while
the tested admin denial correctly returned 403. The assertion now parses the error response
directly. The full rerun and dashboard/SDK evidence will be recorded after completion.

Validation: 744 host tests passed with zero failures/errors/skips in 1m27s. A null-token
failure in the loopback-only login fixture was caught by the browser and corrected; the fixture
was rebuilt, then the full generated SDK and unchanged 18-screen dashboard journey passed.
The import journey now traverses the account chooser and asserts the exact expected 403/code,
hidden Admin Panel, verified entry contents inside the entry card, read-only behavior and retry.
Visual inspection caught an overly broad text assertion matching the Timeline name; it now
requires message content and amount inside the entry card. The rendered original viewer was
inspected. Expected pre-login 401s, ordinary-user admin denial, and the existing post-unlink 404
are recorded, not masked as successful responses. No other browser errors occurred. KEEP.

### AWS acceptance — iterations 53–54

Artifact `eae3f6c402ae725b59f0058f7f293419d8bbc15227d39cc284b1fb82f85cbb93`
was deployed to the same authorized test stack. SSM
`e31832ea-77a3-4fb1-a65d-532a24e73f95` succeeded in 22.981s and native health passed.
The existing separately owned Timeline returned its original entry, exact retained guarantee and
attestation, and a byte-equivalent replay response. Server observations: initial proof 178.967ms,
replay 13.856ms, proof content reads 7.677/7.991ms, expected administrator denial 4.667ms.
Client requests used separate HTTPS connections and took 787–966ms; these are not percentiles.

The unchanged account chooser, import form, read-only history and retry passed in AWS. The first
browser attempt navigated away before the administrator probe completed; the acceptance harness
now waits for and verifies the expected response before choosing the personal account. The final
journey records only `403 ADMIN_REQUIRED`, with no unexpected API/browser errors. Import replay to
details was 669ms in this sample. One session renewal overlapped the planned service restart and
received a non-JSON edge response; renewal succeeded after deployment health passed.

No document business operation was added in this acceptance, and no processing speedup is claimed.
The existing 229-Order Agreement remains at version 334, paid total 5200. These API changes preserve
native execution. Full UI/API parity and the performance target remain unfinished.

## Iteration 55 — original Timeline operation form (qualification)

The original Timeline Details form accepts a document's pre-initialization BlueId. Better previously
returned 501 for that input even though the document Operations panel worked. A focused entry
adapter now resolves that identity through the existing account-local registration index, follows
its active re-registration handle, checks its initial identity and publishes through the same native
operation path as the Documents endpoint. Session IDs, current-state IDs, foreign/removed roots and
unrelated writer Timelines fail before an entry is appended.

Authored source can omit the duplicate native `document` field; the adapter adds it to a copy after
recognition by the target runtime's own operation grammar. Exact or referenced requests must already
carry their target. A different historical target requires exact-version semantics and membership
in the same document lineage. Ordinary messages retain their original content. Entry intent IDs
include the Timeline endpoint, so equal command strings on different API routes do not accidentally
reuse a different native operation. No new execution engine, special business logic or Blue wire
format is introduced. The native publication receipt, predecessor check and input worker are reused.

The index already records definitions at document creation and discovery. Existing bootstrap and
embedded-document identities become addressable after discovery, which supplies the original
`documentId`; no cross-account or full-document scan is used to guess an unknown target. Broader
registration discovery/migration remains separate. Imported/delegated writer authority is still
unported. This closes part of an existing partial operation; the matrix stays 87 partial / 29 pending.

Focused checks cover actual increment execution, replay before/after execution, caller-source
immutability, wrong identifiers/writers, exact target requirements, ordinary messages, removal,
existing registration, bootstrap and Timeline regressions. Initial test builds corrected the exact
identity accessor and fixture constructor declaration. Testing then exposed BlueId's domain exception
being missed by the API error mapping; malformed target IDs now return the intended 422. The generated
SDK passes a real Timeline-form call through a reattached registration, exact receipt replay and
current-version-ID rejection. Full host and original browser checks follow before keeping/deploying.

Validation: all 747 host tests passed with zero failures/errors/skips in 1m30s. Review caught an
important source distinction: an authored operation name can itself be a typed Text node. Native
recognition identifies the root Operation Request, so insertion always changes its root target;
the operation field is not interpreted as an envelope. The regression uses that typed form, and
full host verification plus the generated SDK pass on the final implementation. The unchanged
Bootstrap/Timeline/History browser flow passes and its actual count/epoch and triggering entry
were visually inspected. No UI source changes were made.

The walkthrough now names the current Core controls, includes a complete Core bootstrap envelope,
and distinguishes the historical runtime-231 report from the current deployment. The existing full
canonical clause tutorial test now reads that envelope and asserts its document is byte-identical
to the original source, then verifies all prior Payment/Order/Agreement and shared-Timeline checks.
That updated acceptance test passed in 11.163s (14s Gradle elapsed). This exceeds the 10s review
threshold because it runs real signed multi-document clause execution and growth; no repetitions
or assertions were added to the business sequence. The unchanged legacy bootstrap path retains its
separate integration coverage. A generated-SDK reproduction script adds exactly one Order/Payment
and records original entry IDs and distinct timing stages. KEEP; AWS acceptance follows.

### AWS acceptance — iteration 55

Artifact `de060d0dc204bb62a5d57e36f615af660e74f5d7b958277f1f23e3c95da40e1c`
was deployed successfully; SSM `8658b6bb-a84d-434f-ad90-45342f88fde3` completed in
24.054s with native health passing. The generated SDK was installed/built from its unchanged
locked source. `scripts/reproduce_core_timeline.mjs` completed all five real clause operations
through the new Timeline endpoint on the existing large Agreement. Orders rose 229 → 230,
paid total 5200 → 5400, and Payment/Order both reached 200. Final root version is 339.

| Operation | Engine ms | Signed entry through commit ms |
| --- | ---: | ---: |
| placeOrder | 1680.815 | 5063.207 |
| preparePayment | 1679.387 | 2380.830 |
| acceptPaymentInstruction | 376.488 | 1210.605 |
| reportTransfer | 492.057 | 1240.221 |
| acknowledgeTransfer | 644.882 | 2750.129 |

The first root submission used 4559.161ms server time before response; preparation used
1380.988ms. These are functional acceptance measurements, not an acceptable performance result
or a controlled speed comparison. No profiler or other business workload ran concurrently.
The next iteration investigates the remaining CPU/allocation costs rather than claiming parity
work made execution faster.

A separate AWS browser journey reused the original Bootstrap, Timeline append and History UI.
It created a small Shared counter, submitted an authored native operation without its duplicate
target, observed count 1 / epoch 1, and retained the exact triggering entry. No browser or API
errors occurred. Counter `graph-5d677aba724876c0eed2556a7fb707ee50e7196a33293aac` and its two
Timelines are intentionally retained for reproduction. No email, new API key or financial transfer
was sent. Full parity remains 87 partial / 29 pending operations.

## Iteration 56 — bounded profiling and digest syntax costs (qualification)

The bounded warm SDK workflow completed all five operations, reaching 231 Orders and paid total
5600. The subsequent diagnostic export was unsafe in practice: expanding a 3.9 MiB recording to
JSON exhausted host memory, and the instance killed/restarted the service twice. This was a
profiling failure, not a failed business commit. Native health recovered, the diagnostic parser
exited, and all temporary recording/metric files were verified removed. No document was retried.
Do not use the failed export as timing evidence or repeat that export strategy. The JDK's built-in
`jfr view`, with a 128 MiB heap and bounded output, recovered a method/allocation/GC summary.
Future profiling must bound the analyzer's memory and process lifetime independently of the service.

The summary included 43 identity-syntax samples (4.13% of method samples), map copying,
serialization and allocation pressure; twelve young collections paused for 24–70ms in the captured
interval. This is sampling evidence, not proof that one function explains the complete delay.
One focused optimization preserves BlueId's exact rules: new base58 strings are checked against
precomputed byte-width bounds, after alphabet and leading-zero checks, instead of multiplying
all eight integer limbs for every character. Valid digits sort in numeric order, so equal-length
encoded magnitude comparisons are exact. BigInteger is used only once to construct the 64 bounds.
The same bounded per-thread cache remains; no content trust, identity, gas or wire format changes.

A JMH benchmark separates a 64-identity repeated working set from 16,384 identities, which exceeds
the existing 8,192-entry cache. Two forks, two 300ms warmups and three 500ms measurements per fork:

| Distinct identities | Before ns/op | Candidate ns/op | Before/candidate bytes/op |
| ---: | ---: | ---: | ---: |
| 64 | 2.542 ± 0.166 | 2.589 ± 0.013 | effectively zero / zero |
| 16,384 | 461.184 ± 15.219 | 35.962 ± 2.125 | 80.006 / 32.000 |

These are local microbenchmark confidence intervals, not AWS operation latency claims. The larger
working set improves 12.8×; the cached case is unchanged within baseline uncertainty. Tests cover
every leading-zero count, independent integer decoding of 6,000 mutations, exact width bounds and
neighbours, Unicode/invalid alphabets, and member suffixes. Full library checks and Javadoc passed
in 22s. The initial benchmark failed compilation because its setup called a private encoder; the
final setup owns a separate integer encoder and runs outside measurements. Full host checks follow.

Full validation: 2,499 library tests and 747 host tests passed with zero failures/errors/skips;
Javadoc, formatting, distribution and qualification builds passed. The full host run took 1m30s.
No UI source or native document/persistence format changed. The generated Core SDK also passed its full compatibility sequence. KEEP the syntax optimization;
AWS remains the accepted iteration-55 artifact until the next qualified deployment.

## Iteration 57 — invitation and rejoin lifecycle (qualification)

The original invitation forms now use real issue, revoke and accept commands. Invitation identity,
recipient binding, encrypted delivery intent and command receipt are published together. Acceptance
checks the token, verified email, optional explicit recipient, expiry, invitation revision and expected
membership generation/revision, then atomically advances the account administrator count, membership
fence, roster, user account index and acceptance receipt. A lost response is recoverable without a
second membership. An accepted old link can return its original receipt after removal but cannot
restore permissions. A new invitation advances the generation, invalidating earlier keys and guards.

Email-only rejoining resolves former members from the bounded account roster and rejects ambiguity.
Per-email revision records fence verified email changes along with account membership changes; this
avoids a global identity lock and keeps account maintenance out of document execution. Existing
identity rows remain readable; all future identity writers must update the old/new email fences.
This is additive persistence, but once invitation commands are enabled, rolling back to a writer
without those fences would invalidate the concurrency guarantee. The existing roster bound is 4096.

A leased outbox uses the official AWS SES v2 SDK and existing JCA authenticated custody with a new
`myos.better.invitation/1` domain. Email attempts have 5s/15s SDK timeouts, one-minute leases and bounded
retry delay; retry sends the same link. Stale acknowledgements cannot finish another worker's lease.
Revoked/expired invitations leave the work index; cancellation does not begin another send from an
already selected batch. Delivery is at least once, including a crash after send and before receipt.
Membership authority remains entirely in the invitation acceptance transaction.

No UI component, native execution rule, document format or library code changed in this iteration.
The SDK's acceptance operation intentionally has no Idempotency-Key: invitation/user/token/revision
already identify the durable command. The HTTP adapter follows that contract. Creation replay returns
the current public invitation metadata, as Core does; internal receipts remain immutable. Tokens and
custody records are absent from the API. Qualification-only fake delivery uses a private temporary
mailbox and never sends real email.

Focused domain and delivery tests passed. The first full host run passed all 757 tests in 1m33s.
Review then added interrupt-aware delivery cancellation and two further regressions for shutdown,
lost acceptance replies and issuer removal. Original generated-SDK invocation exposed the distinction
between natural acceptance idempotency and commands requiring a client key; the adapter was corrected
before acceptance. A test-script variable scope collision and an exact-label browser selector were
also corrected. The browser acceptance UI navigates immediately, so its check now verifies visible
membership behavior; detailed receipt/generation assertions remain in the generated-SDK layer.
Final host/SDK/browser verification follows. API matrix: 90 partial / 26 pending; full parity remains
unfinished. SES delivery is not enabled in AWS and no sender or external email was created.

Final acceptance: all 759 host tests passed with zero failures/errors/skips in 1m28s. The generated
SDK passed the full sequence, including old key/guard rejection after a new membership generation.
The unchanged browser Users/invitation/removal/Invite again flow passed through two actual signed-in
fixture accounts; all four screenshots were inspected. Only expected pre-login 401 responses occurred.
One browser attempt failed while reading an acceptance response body after the original UI navigated;
the corrected browser check validates its status and visible result, while SDK tests verify full bodies.
KEEP. AWS deployment follows with invitation sending disabled; no SMTP/SES sender or IAM grant is added.

### Iteration 57 AWS acceptance

Artifact `51f354e6fd7b75fec1db86eb2421142600d573f607a3f7c81ad8e049e81d83b8`,
SSM `4153e7fe-999b-4694-aa10-4d548b83bbcf`: Success in 23.646s, native health passed.
The generated Core SDK completed the five real signed operations on the retained large Agreement:
231 → 232 Orders, paid total 5600 → 5800, versions 345–349. Payment, Order and Agreement values
were independently checked. No pending submission remains. Invitation sending remains disabled;
no sender, email grant or real email was created. The deployment also includes iteration 56.

| Operation | Engine ms | Signed entry through commit ms |
| --- | ---: | ---: |
| placeOrder | 1738.183 | 5507.663 |
| preparePayment | 1295.317 | 2098.509 |
| acceptPaymentInstruction | 317.516 | 1113.648 |
| reportTransfer | 550.883 | 1134.382 |
| acknowledgeTransfer | 532.619 | 2570.472 |

These are functional acceptance samples after deployment, including first-use costs. They do not
establish an end-to-end speed improvement. No profiler ran during this journey. The current host
still has avoidable traversal, persistence and preparation costs; performance work continues.

## Iteration 58 — retained closure snapshots (qualification)

A transaction copied immutable body/proof maps but dropped the completed body-retention set. Its
final retention pass therefore reopened unchanged descendants on every new transaction. Completed
presence facts now use the existing persistent LanguageSet and are copied in constant time alongside
the exact evidence snapshot. New facts remain private to each branch. This does not grant semantic
admission, skip new provider verification, change gas or alter canonical identities.

The regression investigation also exposed a lifetime issue: ancestors of a protected publisher head
were marked permanently retained even though earlier history had deliberately been omitted. Those
facts now belong only to the active publisher-head scope, expire when that scope closes, and never
propagate to a fork. Fully retained closures continue to be reusable. An incomplete/failed retention
pass publishes no completed presence facts. No wire format or storage migration is introduced.

Tests demonstrate bounded physical work for 64 already-retained children, independent sibling
snapshots, retry after missing content, semantic rejection despite body presence, and publisher-cut
revocation in both the original context and a captured fork. The unchanged implementation failed the
bounded-work and publisher-expiry regressions. The initial missing-provider fixture used a raw scalar
without its exact Text type; that fixture was corrected before evaluating the candidate.

The same JMH method measures fork plus retention of a changed root, excluding admission, execution,
transport and persistence. Two forks, two 300ms warmups, three 500ms samples per fork:

| Unchanged children | Before µs/op | Candidate µs/op | Before/candidate bytes/op |
| ---: | ---: | ---: | ---: |
| 128 | 6.666 ± 0.190 | 0.447 ± 0.037 | 16,856 / 3,288 |
| 4096 | 303.444 ± 11.632 | 0.512 ± 0.003 | 454,151 / 3,952 |

This removes the repeated child walk in this component; it is not a claim of constant total graph
processing or a measured AWS speedup. Full library checks/Javadoc pass: 2,502 tests, zero failures,
errors or skips, 20s. The first host command was cancelled because library completion had not yet
been confirmed; the complete host validation is rerun sequentially. Host and deployment acceptance
follow before the keep decision.

Final local acceptance: all 759 host tests passed with zero failures/errors/skips in 1m32s;
formatting, distribution and qualification builds passed. The full generated Core SDK HTTP journey
also passed. KEEP the retention change. AWS acceptance follows on the same retained graph, with
no profiler and the ordinary signed Timeline workflow.

### Iteration 58 AWS acceptance

Artifact `1dd540590309d0675dfce70f73c6076cf55b665309e3dad91238e1021f19f677`,
SSM `2c0ef9ee-3a38-4811-a6aa-d8210d502545`: Success in 22.921s, native health passed.
The real signed SDK journey reached 233 Orders and paid total 6000, versions 350–354, with
Payment, Order and Agreement independently checked and no pending operation.

| Operation | Engine ms | Signed entry through commit ms |
| --- | ---: | ---: |
| placeOrder | 1697.975 | 4529.153 |
| preparePayment | 990.546 | 1611.809 |
| acceptPaymentInstruction | 429.102 | 1352.473 |
| reportTransfer | 703.259 | 1396.428 |
| acknowledgeTransfer | 500.962 | 2359.207 |

This sample still does not establish acceptable total latency or an end-to-end speedup. No profiler
was active. A separate user-authorized benchmark chat subsequently reported a 500-child graph and
severe heap pressure on an earlier release at 01:03 UTC. Its clients are now stopped; coordinate
further workload windows. Earlier unsynchronized samples must not be treated as controlled comparisons.

## Iteration 59 — persistent archive indices and direct version selection (qualification)

Each transaction previously copied all archived-version entries and every root's selection set.
Looking up one root also constructed the complete bindings map. Archive indices now use the existing
persistent LanguageMap/LanguageTrie implementation: forks share immutable index roots, later writes
remain private, and lookup selects directly from that root's candidates. Exported bindings still use
the original sorted canonical map. Stored snapshots are still privately frozen; callers still receive
independent working copies. Explicit selection, ambiguity and unknown-view failures are unchanged.

This does not evict old snapshots or claim to solve retained-session heap growth. It removes copying
of unchanged index entries and avoids allocating an unrelated binding map on each selected lookup.
The archive tests cover immutable candidate sets, source/fork mutation isolation, explicit choices for
ambiguous roots, unknown selection and merging into an archive that already has unrelated entries.

The benchmark creates real canonical single-document versions during setup. It times only index fork
or selected-version lookup (including the selected document's independent copy), not execution or
snapshot export. Two forks, two 300ms warmups and three 500ms samples per fork:

| Operation | Versions | Before µs/op | Candidate µs/op | Before/candidate bytes/op |
| --- | ---: | ---: | ---: | ---: |
| Fork | 128 | 7.614 ± 0.303 | 0.0153 ± 0.0005 | 27,880 / 168 |
| Fork | 4096 | 492.147 ± 61.278 | 0.0155 ± 0.0008 | 884,991 / 168 |
| Select | 128 | 6.036 ± 3.287 | 0.217 ± 0.009 | 6,644 / 1,640 |
| Select | 4096 | 506.382 ± 40.997 | 0.230 ± 0.010 | 165,391 / 1,640 |

Full library checks/Javadoc pass: 2,504 tests, zero failures/errors/skips, 19s. Host validation follows.
No public API, Blue identity, gas, native wire representation, UI component or storage schema changed.
AWS acceptance traffic remains idle while the separate user-authorized 500→1,000-child benchmark runs.

Final local acceptance: all 759 host tests passed in 1m33s, zero failures/errors/skips; formatting,
distribution and qualification builds and the generated Core SDK journey passed. KEEP. The next
qualified rolling deployment will include this change; no additional AWS benchmark was started.

## Iteration 60 — indexed member lookup in dashboard reads (qualification)

Current child content, initial identity, property reads, operation discovery and targeting previously
built the entire public graph and hashed every member address to locate one child. They now resolve
the child through the existing immutable address index from the exact locked head. Historical reads
resolve against their selected publication. Root lookup stays direct. Views that explicitly list
relationships/children still enumerate that requested topology. Legacy manifests keep their existing
compact metadata fallback; no new persistence format or UI component is introduced.

The new regression covers current and historical content, original initialization identity, operation
discovery, missing child addresses and foreign-account rejection across restart. Existing member-birth
and native Timeline-targeting checks pass. The initial fixture used the abstract Operation role and
was corrected to the concrete Sequential Workflow Operation before measuring the candidate.

A bounded local probe creates actual 17/257-member canonical graphs, excludes cold restoration,
warms 50 reads and records 100 reads of the same child's property. Both variants use iteration 59's
library. This is a short local comparison, not AWS latency or a statistical confidence interval:

| Members | Before median µs | Candidate median µs | Before/candidate mean bytes per read |
| ---: | ---: | ---: | ---: |
| 17 | 33.313 | 72.854 | 19,742 / 24,125 |
| 257 | 43.084 | 22.229 | 79,046 / 30,206 |

The indexed path pays decoding overhead in the small case and removes the graph-size allocation
slope. Do not report a universal speedup from this probe. Full host/SDK acceptance follows.

Final local acceptance: all 760 host tests passed in 1m31s, zero failures/errors/skips; formatting,
distribution and qualification builds passed, as did the generated Core SDK journey. KEEP the indexed
read path. It will be deployed together with the qualified archive optimization and the separately
validated canonical-session renewal fix. AWS remains iteration 58 while the independent benchmark
coordinates recovery from its recorded heap-pressure boundary.


## Iteration 61 — bounded canonical working history (KEEP; qualified)

The legacy document cache honored the configured `MYOS_SESSION_USES`, while the canonical graph
cache kept a frequently used session indefinitely. Each applied transition could therefore retain
another generation of execution history in memory, even though every committed generation was
already durable. Canonical sessions now honor the same 1–1024 use policy (default 32). Initialization
counts once; subsequent applied transitions count only after durable publication succeeds. The
completed lease closes at the bound, and a later request verifies/restores the current durable
snapshot. Reads, no-input checks and exact command replay do not consume the update budget.

This is a working-history lifetime bound, not a hard byte limit: canonical cache capacity remains 32
entries and the legacy estimated-byte policy does not cover canonical graphs. A large current graph
can still be large, and periodic verified restoration adds latency. No history, receipt, input or
canonical identity is deleted; no storage migration or execution-edition change is introduced.
Cache metrics expose maximum uses, entries, loads, hits and history renewals. Configuration must be
installed before a session is cached. Engine sessions borrowed internally cannot outlive their lease;
immutable LanguageDocument values already returned remain valid when the working session closes.

The four focused tests cover configuration wiring/validation, read-only reuse, borrowed immutable
content after renewal, and repeated close/restore with exact receipt, gas, identity and snapshot
equality against inline execution. They also verify durable old history and idempotent replay after
renewal. Full host formatting, checks, distribution and qualification build passed in 1m34s: 764 tests,
zero failures/errors/skips. The generated Core SDK HTTP journey passed. Its first sandboxed launch
could not bind loopback; the same bounded check passed with loopback permission. No test was skipped.

KEEP. Deploy with iterations 59–60 after the independent AWS benchmark's durable 600-child checkpoint.
That benchmark has paused with 601 additions verified and one saved idempotent add-602 intent; it will
replay the identical request after health confirmation. AWS latency/heap effects remain unmeasured
for this candidate. The earlier microbenchmarks measure individual components only.


### Iterations 59–61 deployment

Artifact `11498307f00f644647446428741c7f79ff0bf7ff39a99963c3afb72cd0094201` deployed to
the existing authorized test stack. SSM `2b18f74e-8dec-4f67-b889-9beb50e498d4` completed
Success in 22.961s; native health passed with canonical graphs, native admission and automatic
Timeline processing enabled, eight workers. Storage, execution edition and canonical identities
remain unchanged. The independent benchmark chat was notified to resume its exact saved add-602
request. Its measurements will distinguish this release from earlier samples; no new workload
latency or heap improvement is claimed from health alone.


## Iteration 62 — distinguish working-session restoration (KEEP; qualified)

Per-version local performance telemetry now records `sessionRestoreNanos` and
`sessionHistoryRenewal` (0/1). Restoration includes loading and verifying the current durable
snapshot before the transaction; it remains separate from `engineNanos`. The renewal indicator
identifies a committed operation whose completed lease releases its working history. Warm leases
report zero restore time. A preceding read or exact replay may already have restored a session,
so zero does not imply no neighboring request paid restoration cost. These measurements are recent
process-local diagnostics, not durable receipt fields and not part of Blue identity or gas.

Focused tests cover cold and warm execution, renewal and non-renewal, and preservation of the
original measurement on exact replay. The initial test expected every post-renewal operation to be
cold; it correctly exposed that the preceding replay had already restored the session. Assertions
now check that distinction explicitly. Full host checks passed in 1m27s: 764 tests, zero failures,
errors or skips; distribution/qualification builds and generated Core SDK journey passed. KEEP.
Deploy with the next qualified feature iteration; do not interrupt the ongoing AWS measurement
solely for these diagnostics. No new AWS performance claim.


## Iteration 63 — account-local package management (KEEP; qualified)

The original Packages UI can now remove and reinstall declarative libraries from the exact deployed
catalogue. Native protocol packages and their import closure remain managed. All existing verified
publications start installed, preserving current accounts. The account stores only explicit disabled
identities and durable command receipts. A conditional account package-state update fences dependency
checks: a concurrent reinstall cannot race past removal of its dependency. Missing dependencies,
managed-package removal and unknown packages produce explicit domain rejection. Replaying an old
removal after reinstall returns its original receipt without removing the package again.

Source `type`, `itemType` and `keyType` names use the installed account packages, with explicit
bootstrap selections resolving and installing their full exact import closure. Preparation does not
install packages; bootstrap installs only after participant/shape validation. Ordinary `name` fields
and string data are unchanged. Exact BlueId references and already retained documents remain valid;
removal does not alter canonical content or the processing environment. Public immutable definition
content remains public. Native execution bindings cannot be installed through this API. There is no
publisher or remote unverified package download.

Alias maps are immutable and cached for at most 64 distinct package selections. Exact source
references require no account package-state read. No document or graph scan is added to operation
processing. The original HTTP precondition failures remain HTTP failures; explicitly admitted package
domain decisions become durable failed admissions, while service failures stay retryable. The SDK
regression caught an overbroad draft error conversion, which was narrowed before acceptance.

New additive records: `CORE_ACCOUNT#<account>/PACKAGES` (format 1, disabled identities) and
`PACKAGES_CMD#<digest>` receipts. No document/storage format migration is needed. Rolling back below
this iteration ignores package choices and would re-enable removed authoring names; preserve these
records and account for that behavior before rollback. Existing execution continues independently.

Six package tests plus a focused admission test cover dependency/managed protection, account
isolation, restart, concurrent dependency changes, replay, exact closure selection, aliases, structural
limits and retained exact content. Full host format/check/distribution/qualification passed in 1m35s:
771 tests, zero failures/errors/skips. The complete generated SDK HTTP journey passed, including the
new package lifecycle. The original dashboard browser journey passed removal, reload, reinstallation
and managed protection with three screenshots and zero signed-in HTTP/browser errors. The initial
browser assertion included the expected unauthenticated refresh probe; the corrected boundary allows
that probe only before login. No dashboard component or SDK generation was modified.

KEEP. Local source and browser/API behavior are qualified; AWS deployment follows with iteration 62.
The API matrix is now 92 partial / 24 pending: the original historical package/runtime catalogue is
still not fully ported, so these endpoints are not marked fully equivalent.


## Iteration 64 — selected property reads without execution restoration (KEEP; qualified)

After canonical working-history renewal, reading a small property previously acquired an execution
session and restored the complete graph. Property reads now pin one committed head, resolve the
selected member through its immutable address index, and read only that member's selected content.
`Contracts.inspect(content).at(pointer).value()` exposes the same BEX projection as a session read;
`value(limits)` adds explicit output bounds. Exact provider records remain verified. Each read has its
own Language work allowance, and no workflow runs or gas changes. Returned data is detached and
immutable. Missing/unfilled fields retain the undefined semantics of session reads.

The first full host check found that imposing the inspector's default projection cap changed the
existing API for a large embedded Payment value. The default now matches `Session.read`; callers can
select explicit projection bounds. The unchanged full clause tutorial then passed. No test was
weakened or skipped. Small selected reads do not restore execution topology; requesting a complete
large value still performs the work needed to produce that value.

The regression denies access to the graph's execution-edge pages while allowing committed member
content, then proves stats/member reads succeed with zero execution-session loads. Operation discovery
still fails the denied restore, demonstrating that the test actually distinguishes the paths. Library
checks compare projection with session reads, reject explicit output-limit violations, preserve later
reads and prevent fetching unrelated content. No storage format, canonical identity, gas or execution
semantics changed.

This does **not** remove the actual execution-session renewal cost. A later operation or worker check
may still restore the complete graph. Existing operation discovery and full execution inspection are
separate paths. Keep end-to-end operation latency, restoration and selected read latency distinct.

Final validation: 2,505 library tests and Javadoc passed in 19s; 772 host tests, formatting,
distribution and qualification builds passed in 1m35s; zero failures/errors/skips. The complete
generated Core SDK HTTP journey passed. The original package browser journey remains the qualified
iteration-63 run; this iteration changes no UI component.

A final bounded local MemoryStore probe separates setup, three fresh views and twenty warmed reads.
It does not include AWS, HTTP, production cache pressure or a confidence interval:

| Members | Before cold reads, ms | Candidate cold reads, ms | Before/candidate warm median, ms |
| ---: | --- | --- | --- |
| 17 | 38.890 / 15.230 / 14.309 | 10.745 / 1.358 / 1.053 | 0.0184 / 0.4586 |
| 257 | 60.278 / 38.951 / 37.006 | 1.756 / 0.368 / 0.314 | 0.0107 / 0.2730 |

KEEP: substantially smaller cold selected reads in this probe, with an explicit warm-read regression
from constructing a detached verified view. No universal or AWS speedup is claimed. Deploy iterations
62–64 at the independent benchmark's clean 750-child/135-probe checkpoint (version 886, no pending
intent). Its pre-deployment checkpoint had 6.171s median root-visible latency and 7.798s maximum;
those operation times remain unacceptable and are not explained away by the local read improvement.


### Iterations 62–64 deployment

Artifact `961b3a7cc6b9bfd150d7ef431049978adc89fe32c3f545538d8feb669e590b74` deployed to
the existing authorized test stack. SSM `66553dc1-c13c-4db9-afec-debf929a7830` completed
Success in 23.074s; native health passed with the existing eight workers and 32-use canonical
history bound. No storage, execution edition or infrastructure settings changed. The benchmark chat
was released from its clean 750-child/135-probe checkpoint after health confirmation. Subsequent
measurements belong to this artifact; deployment health alone establishes no workload speedup.


## Iteration 65 — bounded current-checkpoint handoff (KEEP for bounded AWS qualification)

A frequently used canonical graph closes its working session every configured number of applied
transitions. The next acquisition previously reconstructed all current snapshot pages from durable
storage even though this process had just held that same committed state. The candidate preserves
only a detached portable current snapshot in a 32 MiB estimated cache, closes the old session as
before, and verifies/restores the cached snapshot on an exact account/graph/version/identity match.
The value is consumed once; misses, eviction, size refusal and capture failure retain ordinary
storage recovery. A concurrent writer's newer head cannot use the old checkpoint. The cache contains
JSON values, not an engine or an accumulated sequence of old execution states.

Checkpoint preparation runs after successful durable publication, outside the shared session-cache
monitor. An optional capture failure increments a diagnostic and cannot turn an already committed
operation into a failure. Commit metrics stop at the actual durable boundary; export time is exposed
separately and still contributes to whole-call latency. Cold process startup is unchanged. Retained
cache weight is bounded; temporary snapshot construction is not a complete heap bound. No protocol,
gas, canonical identity, persistent format, UI or execution-edition change is introduced.


Final local acceptance: 777 host tests passed with zero failures/errors/skips in 1m36s, including
formatting, distribution and qualification builds; the generated Core SDK journey passed. The existing
published-clause tutorial now forces renewal every two applied updates and checks successful checkpoint
reuse, preserving all Payment → Order → Agreement results. It took 12.212s in the full run. This deliberate
integration-test cost exercises real packages and signed input across multiple maintenance boundaries
without adding a duplicate journey; full host duration was 1m35s in iteration 64 and 1m36s here.
Library source is unchanged from the 2,505-test qualified iteration 64.

The cache stores at most one candidate per graph. A newer candidate replaces an old one; selecting a
changed head discards an obsolete candidate. The exact current snapshot includes any historical
content its current references still require, but not the engine's separate collection of old working
snapshots. Snapshot verification remains mandatory. Tests cover immutability, one-use consumption,
size refusal, failed capture, version/identity mismatch, cross-account isolation, concurrent writers,
signed Timeline renewal and exact durable replay.

Final bounded MemoryStore comparison: twelve operations per size, first four warmup, a deliberately
short four-use policy to observe two measured renewal cycles. All 36 receipt/gas/identity triples match.
This is a diagnostic pair, without AWS/Timeline I/O or statistical confidence bounds:

| Members | Before/candidate median whole call, ms | Before restore samples, ms | Candidate restore samples, ms | Candidate capture samples, ms |
| ---: | ---: | --- | --- | --- |
| 17 | 6.282 / 7.073 | 14.347 / 12.146 | 9.038 / 9.455 | 1.582 / 1.434 |
| 257 | 19.091 / 20.472 | 36.683 / 27.446 | 34.241 / 22.536 | 4.645 / 3.637 |
| 1,025 | 58.304 / 72.060 | 101.997 / 106.839 | 111.632 / 112.243 | 10.375 / 12.353 |

This is **not a local CPU speedup**. KEEP for a bounded AWS qualification because a successful handoff
avoids durable page reconstruction under storage-cache pressure. Its actual AWS value remains to be
measured, including capture cost, cache refusal and heap effects; revert the optimization if it does not
justify its cost. Cold startup is unchanged. Do not present this candidate as satisfying the latency target.

At the independent benchmark's clean 800-child/144-probe boundary (root version 945, no pending intent),
iteration 64 had 8.029s median root-visible latency and 8.567s maximum. One bounded all-object histogram
and one live-only histogram were collected while the client was stopped. The latter explicitly ran one
GC and completed in 2.071s; diagnostic GC must be excluded from workload counts. Live values included
~775MB byte arrays, ~219MB TreeMap entries, ~143MB Strings and 1.73 million ExactNode.Value instances.
This establishes substantial retained exact-content structure, not just transient allocation; a histogram
does not identify retaining paths or prove that all instances are duplicates. No heap dump, document
contents or JFR were collected, and remote temporary histogram files were removed.


### Iteration 65 deployment

Artifact `f9c80033da7912e88a9b155920bf2f9b6e96d6726e5cfe0ccb2e798ac7c66e02` deployed at the
clean 800-child/144-probe boundary. SSM `bf088a71-5e2f-450f-8af7-a7549a09c949` completed Success in
24.067s; native health passed with unchanged instance, heap, worker, cache-use and storage settings.
The benchmark resumed only after confirmation. Checkpoint capture/restore and heap behavior remain
under workload qualification; no end-to-end speedup is established by deployment or local tests.


## Iteration 66 — same-context normalization sharing (KEEP)

The retained-object histogram motivated inspection of exact-node construction. Normalization always
returned newly allocated immutable values, even when its current body catalogue held the same exact
materialization. It now reuses that existing value and avoids rewriting unchanged catalogue entries.
This changes physical retention only: inline/reference presentation, cyclic body hints, logical work,
provider verification and last-representation behavior remain distinct. No global interner, cache,
protocol edition or persisted data migration is added.

The controlled structural probe retained 225,506 exact values before and 2,228 after at 1,024 repeated
inline workflows, with identical identity/work digests. It does not establish the same reduction for the
AWS Agreement graph. Details and the reproducer are in the library's `docs/NORMALIZATION-SHARING.md`.
Final acceptance: library formatting/check/Javadoc passed with 2,509 tests in 25s; host formatting,
check, distribution and qualification builds passed with 777 tests in 1m37s. Both suites had zero
failures/errors/skips. The generated Core SDK HTTP journey passed. KEEP for materially lower retained
structure with preserved behavior; AWS benefit remains unmeasured. Iteration 65 remains deployed
while the independent chat finishes its fixed-population profile; deployment requires its clean window.


### Iteration 66 deployment

Artifact `6f6d2911da481e850497b263321e9cc68f9add76a02b489a1e52fd64764dc2bd` deployed at the
clean 817-child/150-event boundary (144 regular probes plus six marked diagnostic increments).
SSM `2d510deb-addc-469f-b348-db0f6456cf2c` succeeded in 24.099s and native health passed. Configuration
and persistence formats are unchanged. The benchmark resumes only after confirmation; its first
restore and subsequent warm measurements must remain distinct.

## Iteration 67 — invocation-local completed-value admission (KEEP; qualified)

The independent AWS JFR isolated repeated API submission admission: roughly 97.5% of warm root-add
CPU samples and 99.3% of fixed-population child-increment samples were under CoreContents.prepare.
At 817 children, previously untouched middle/newest children submitted in 0.762/0.869s; their next
updated versions took 6.517/6.409s, with no growth or session renewal. These are measured pre-fix
samples, not proof that every cost is caused by this path. Engine work and publication remain separate.

The candidate adds explicit Language.read overloads taking library-issued completed values. Their
exact semantic headers are usable only during that admission call. New source/exact input, contextual
restrictions, identity changes and ordinary resource bounds remain checked. Definitions cannot stand
in for completed values; neither serialized records nor the reusable Language configuration acquire
these invocation-local facts. Core preparation supplies its reviewed current target through this API.
No operation authorization, history validation, account isolation, gas or persistence format changes.


Library formatting/check/Javadoc: 2,514 tests passed in 21s (final defensive input-array snapshot). Full host formatting/check/distribution/
qualification: 778 tests passed in 1m33s. Both suites have zero failures/errors/skips. The generated
Core SDK HTTP journey passed against the final distribution. Five targeted library cases cover invocation bounds,
source/exact/parsed-node equivalence, contextual refinements, changed references, definition refusal,
cyclic portable evidence and a native-produced value retained after session closure.

The controlled local probe uses 2/129/1,025 managed members, initializes and changes both root and
selected child, then compares ordinary and scoped preparation on the identical immutable versions.
It alternates before/after order, discards two warmup pairs and reports three measured pairs. Every
wire representation and BlueId matches. It is a bounded diagnostic, not a statistical campaign or AWS
latency measurement; its simple clauses do not reproduce the full Agreements deployment.

| Members | Target | Before / reused median, ms | Before / reused median allocated bytes |
| ---: | --- | ---: | ---: |
| 2 | root | 1.785 / 0.429 | 1,381,944 / 213,696 |
| 2 | updated child | 1.374 / 0.369 | 963,880 / 213,280 |
| 129 | root | 9.117 / 0.295 | 21,122,480 / 204,352 |
| 129 | updated child | 0.408 / 0.157 | 898,664 / 200,984 |
| 1,025 | root | 42.661 / 0.186 | 154,647,576 / 199,600 |
| 1,025 | updated child | 0.302 / 0.124 | 881,960 / 199,216 |

Setup (outside admission samples) was 94/348/5964ms at the three graph sizes. The improvement applies
to repeated admission of an already completed subtree, not initial graph construction. The separate
qualification executable is excluded from the service distribution and default test execution.

```bash
./gradlew -PblueLibrary=../blue-java-better installDist qualificationJar
java -Xmx768m -cp "build/libs/myos-core-better-0.1.0-experiment-qualification.jar:build/install/myos-core-better/lib/*" myos.better.OperationAdmissionProbe
```

KEEP: reviewed completed-value evidence removes repeated subtree validation without changing
accepted source, exact identities, permissions or native gas. No global cache or storage migration.
AWS end-to-end acceptance follows at the independent benchmark's clean 850-child/159-event boundary.
Its preceding increment median was 6.361s (max 6.592s); this iteration must be measured against it.
Iteration 65's checkpoint was oversized at that boundary, with zero captures/restores and one refusal;
it has not demonstrated value for this large workload and requires a separate keep/revert decision.


### Iteration 67 deployment

Artifact `696145d3691e2773bead72380bb510fa8a2e04621b46505eab1a5f74fd2a7ddf` is deployed.
SSM `df69b66f-48ae-42ca-9942-15043d708227` completed Success in 22.988s, native health passed,
and the service PID is 76353. The benchmark remains on the same instance, 2 GiB heap, eight workers
and 32-use session policy. Its owner will repeat the fixed 850-child comparison (one labeled warmup
and nine diagnostic increments), then finish 1,000 children, a small control, cycles and payments.
This release is held steady during that measurement window. Health is not a latency result.


### Iteration 67 AWS admission comparison

The independent benchmark repeated nine matched child increments at the unchanged 850-child
population on iteration 67, without JFR. One explicit post-restart warmup was excluded. Median
submission fell from 5,097ms to 549ms; median root-visible latency fell from 6,361ms to 1,985ms.
Engine median changed from 606ms to 725ms, so this is an admission improvement, not an engine speedup.
The measured root total is now 169 increments (including labeled diagnostics); growth resumes to
1,000 before a small control, finite cycles and payments on this same artifact.

KEEP confirmed for the measured API bottleneck. The desired flat end-to-end scaling is not yet
established: engine, commit, selected reads and history renewal remain separately measurable costs.
Raw evidence is retained locally under `blue-java/benchmarks/aws-2026-11-07/admission-fix-comparison.json`;
generated captures are not library or service source artifacts.


## Iteration 68 — exact account-ID lookup for original bootstrap examples (KEEP)

The unchanged dashboard example builder resolves participant account IDs through Core's public
lookup API. That read previously returned `CORE_PARITY_PENDING`. It now accepts up to 100 explicit
IDs, normalizes and deduplicates them, and returns active public account profiles in account-ID order.
The response whitelists the original public fields; it never grants membership or exposes private
profile addresses, agent configuration or credentials. Missing/disabled accounts are omitted.

The read-only POST requires authentication, origin/body checks and the base-session guard but no
idempotency key. Page tokens bind normalized selectors, page size, user and session. Original input
validation is ported, including nonempty selectors, exact integer page limits and bounded arrays.
Email and text search, including conjunctions with explicit IDs, still return an explicit unsupported
response: those semantics require a public directory index and cannot silently broaden a filter.
This is a partial operation, not full account-discovery parity. No storage migration or document
processing changes; dashboard components and generated SDK remain unchanged.

Validation: focused account lookup/HTTP/permission tests passed. Full host formatting/check,
distribution and qualification builds passed with 782 tests in 1m34s, zero failures/errors/skips.
The generated Core SDK passed real HTTP lookup without an idempotency key, stable normalized
pagination, private-field exclusion and cross-user cursor rejection, plus its existing native
Timeline/document/package/account journeys. KEEP: restores the exact-ID discovery path used by
bootstrap examples with bounded reads. Matrix: 93 partial / 23 pending, zero claimed fully equivalent.

AWS remains iteration 67 while the independent chat finishes 1,000 counter children, a small control,
finite cycles and payment growth. This feature is not yet deployed; no latency claim is made for it.


## Iteration 69 — direct current-state maintenance (KEEP locally; AWS qualification required)

Iteration 65's serialized checkpoint handoff was refused at every observed large-graph renewal. At
1,000 children the AWS service reported five oversized refusals, zero captures/restores and five
renewals. The periodic next access therefore reconstructed the graph from durable pages. At 950
children a separate operation-context read took 19.258s after renewal; this is not engine latency.

The library now compacts an existing verified graph directly: copy reachable immutable exact bodies
and cyclic proofs into a fresh configured processor, reconstruct only canonical metadata, check its
identity/bindings and retain one current archive snapshot. It does not preserve old resolver-backed
declaration caches or serialize and re-admit every body. Untrusted portable recovery is unchanged.
Trees, cycles, native registrations, borrowed content and stale private transactions retain their
existing public behavior. Required historical references remain reachable; their storage is not erased.

The host performs this maintenance after durable commit at the same configured use limit and keeps
the rebuilt current session. A failure invalidates the optional session and the next access uses
ordinary verified durable recovery; it cannot undo the committed operation. A peer's newer durable
head still invalidates the cache. Cold start and eviction still require recovery. The 32 MiB serialized
checkpoint cache and its three implementation-specific tests are removed; lifetime tests now cover
the direct library boundary and the retained host behavior. No persistence format or gas change.

New telemetry is `graphSessions.compactions`, `graphSessions.compactionFailures` and per-operation
`sessionCompactionNanos`. The old checkpoint-cache counters are removed. Commit timestamps exclude
post-commit maintenance; whole-call measurements include it. Current graph-cache entry and use bounds
remain unchanged. This does not establish a strict heap bound for canonical sessions.

Full library formatting/check/Javadoc passed: 2,517 tests in 21s, zero failures/errors/skips. Full host
formatting/check/distribution/qualification passed: 779 tests in 1m33s, zero failures/errors/skips. The
generated SDK's real HTTP journeys passed. The published-clause test deliberately renews after two
uses and verifies signed Payment → Order → Agreement with the same two Timelines; it took 11.944s.

The library's five-pair comparison (two warmups, alternating order) reduced portable maintenance
from 108.401ms to 49.467ms at 1,025 static members and allocation from 212,939,376 to 105,921,656 bytes.
Every portable snapshot and next receipt/gas/identity matches. See `docs/SESSION-COMPACTION.md`.

Host comparison: identical static-member fixture, MemoryStore, four-use policy, twelve operations
with the first four excluded. All 36 receipt/gas/identity triples match across the binaries. These
small diagnostic samples are not an AWS campaign and have no confidence intervals.

| Members | Before / candidate median whole call, ms | Before / candidate mean, ms | Before / candidate maximum, ms |
| ---: | ---: | ---: | ---: |
| 17 | 6.674 / 7.867 | 8.469 / 7.724 | 15.734 / 9.320 |
| 257 | 20.700 / 24.122 | 30.074 / 24.778 | 66.392 / 36.030 |
| 1,025 | 67.962 / 79.392 | 96.346 / 80.193 | 190.639 / 109.487 |

The median increases because maintenance moves into the preceding call; mean and worst sample
improve. The candidate has zero durable session reloads during the probe versus three before.
At 1,025 members the two measured maintenance boundaries cost 51.875/48.036ms; the preceding
implementation's following restores cost 96.025/102.311ms, in addition to checkpoint capture.
KEEP for lower maintenance allocation/cost and removal of avoidable durable reloads, subject to AWS
qualification. It does not claim constant-time processing or solve the cost of growing required history.

The independent AWS run completed on unchanged iteration 67: 1,000 children, one participant
Timeline and 196 increments (180 normal plus 16 labeled diagnostics). Final nine probes had root-visible
median 1.977s and maximum 2.106s; root metadata/stats reads were 421/323ms. PID76353 remained up.
Heap was 87.1%, with eight full GCs totaling 7.992s. Small control, finite cycles and a separately
restarted payment workload follow on that same artifact. Iterations 68–69 remain local until its
owner releases the deployment window. API matrix remains 93 partial / 23 pending.


## Iteration 70 — pinned operation-context reads without execution recovery (KEEP locally)

The original Core context adapter selected a small canonical path only after `documents.content`
opened the current execution graph. This explains why an AWS `/stats` read could trigger a full
post-renewal reload despite the lower-level selected-property API already avoiding it. The adapter
now obtains a lazy exact reference from the authorized immutable publication's member/content index.
It resolves only what the requested context projection needs, with the existing output/Language bounds.

Current-publication selectors, account isolation, registration handles, exact numeric values and
canonical outline paths remain unchanged. Historical member lookup uses the requested publication,
not the current execution graph. This is inspection, not a claim that a reference alone is an admitted
executable document. Ordinary document execution and untrusted input admission are unaffected.

`GraphMetadataTest` now checks current/historical child content, missing versions/members, foreign
account denial, and scalar/outline reads while topology storage is deliberately unavailable. No
execution session is loaded. A first test expectation incorrectly included a null/unfilled field
that Language had already omitted; it was corrected to the existing undefined-field semantics.
Existing Core context tests and the generated SDK protect publication guards, alias re-registration,
projection limits and navigation. Full host check/distribution/qualification passed with 779 tests
in 1m36s, zero failures/errors/skips. The original generated SDK HTTP journeys passed.

The qualification probe compares the exact same pinned context JSON through the previous session
path and the reference path. Five alternating cold pairs at each size exclude two warmups; counts
are independently checked and reference reads load zero sessions. MemoryStore, no HTTP/AWS I/O:

| Members | Previous / reference median selected context, ms |
| ---: | ---: |
| 17 | 14.193 / 0.645 |
| 257 | 36.896 / 0.467 |
| 1,025 | 152.769 / 0.662 |

```bash
./gradlew -PblueLibrary=../blue-java-better installDist qualificationJar
java -Xmx768m -cp "build/libs/myos-core-better-0.1.0-experiment-qualification.jar:build/install/myos-core-better/lib/*" myos.better.OperationContextReadProbe
```

KEEP: removes execution recovery from selected inspection while preserving its publication and
projection contracts. Persisted exact records may present nested values by reference, as cold
session recovery already does; canonical child navigation continues to resolve them. These
bounded local samples do not establish AWS latency. The initial metadata-discovery path and full
content export are separate operations and can still require more work. No storage migration; API
matrix stays 93 partial / 23 pending. AWS remains iteration 67 during independent payment testing.

### Same-release counter control and finite cycle

The independent chat verified all 33 touched children against the root's 196 increments. A finite
root → child → root Documents cycle processed five pulses, producing child count five and root
capture count five, with both active and one shared Timeline.

Nine matched probes per population on iteration 67, with separate root Timelines and no renewal
flags, measured the following medians (network/polling is included in client observations):

| Measurement | 10 children | 1,000 children |
| --- | ---: | ---: |
| Submission observed | 428.6ms | 431.1ms |
| Engine | 30.85ms | 419.72ms |
| Signed entry to commit | 312.6ms | 1,345.3ms |
| Root update visible | 703.3ms | 1,977.2ms |
| Canonical gas | 324,651 | 1,202,933 |

Admission is approximately flat in this sample; engine time and gas are not. Population, history
length and cache state are not independently controlled. The bounded growth JFR had 1,444 sampled
worker stacks under CanonicalGraphs.process: 1,156 included transaction finish, versus 85 under
GraphTimelineIngress.prepare. Inclusive subpaths (overlapping) include patch 488, runtimeMatches 313
and creationBarrier 181. This identifies runtime execution as the main sampled worker cost for
that growth window; it is not a wall-time percentage or proof that every increment has that profile.

The existing JFR was transferred and cleaned remotely. The benchmark chat owns the agreed restart
of the same iteration-67 binary to establish a clean heap baseline before payment growth. No
iteration-68–70 deployment interrupts that sequence.


## Iteration 71 — compile fixed validation patterns once (KEEP locally)

The AWS growth profile included 92 worker stacks under `String.matches`/`Pattern.matches`.
Canonical pointers, list indices and numeric text repeatedly compiled constant regular expressions.
They now share immutable `Pattern` instances; each invocation still owns its matcher. The exact
pattern text, match mode, call order, error branches, scalar parsing and gas charges are unchanged.
BEX's more permissive decimal-conversion grammar remains distinct from Language's numeric grammar.
No public API, storage format, execution edition or wire identity changes.

The complete library suite passed: 2,517 tests, zero failures/errors/skips, plus formatting,
strict compilation and Javadoc. The full host check/distribution/qualification passed in 1m36s:
779 tests, zero failures/errors/skips. Generated Core SDK HTTP journeys passed. Existing published
Language/BEX/Contracts vectors own semantic correctness; no timing assertion was added to tests.

A standalone JMH run used Java 25, 512 MiB heap, two forks, two one-second warmups and three
one-second measurements per fork. Each operation evaluates sixteen list-path reads; the second
workload also converts decimal text. Inputs and programs are prepared outside measurement. Setup
checks independently authored values, no effects and unchanged gas (35/67). Baseline and candidate
used the same workload/JVM. An earlier candidate run overlapped Gradle tests and was excluded.

| Workload | Baseline / candidate mean, µs | Baseline / candidate allocation, bytes |
| --- | ---: | ---: |
| Sixteen list-path reads | 11.157 / 6.114 | 94,336 / 24,676 |
| Reads plus numeric conversion | 19.541 / 9.532 | 156,272 / 29,104 |

JMH's reported score errors were ±0.515/0.320 µs and ±0.672/0.481 µs respectively.
KEEP: approximately 45%/51% less time and 74%/81% less allocation in these bounded BEX workloads.
This is not a whole-contract or AWS speedup claim. The source includes `BexReadBenchmark` so the
isolated measurement can be repeated; raw local outputs remain outside publication source.

AWS remains iteration 67. The benchmark owner restarted that same artifact at 04:47:49 UTC,
PID79330, verified an empty session cache, and reached 50 payment wrappers with 50 locally triggered
payments. Three simulated receipt roundtrips updated the root. First-50 root-visible creation median
was 1.396s; heap 63.5%, no full GC. This is baseline evidence, not evidence for the local optimization.
The next isolated experiment addresses quadratic list membership in embedding reconciliation.
API matrix remains 93 partial / 23 pending.


## Iteration 72 — ordered-set embedding membership (KEEP locally)

`ContractGraph.reconcile` checked every existing edge against a list of required paths. A root
with N retained child paths therefore performed O(N²) string-membership work on this branch,
even when an unrelated field changed. The required paths now use a `LinkedHashSet`: expected
constant-time membership, with the same previously validated canonical traversal order. Duplicate
and overlapping paths are still rejected before construction. Edge removals/additions, generations,
validation, event order and logical gas remain unchanged. No new cache or storage representation.

Standalone JMH, same Java 25/512 MiB heap and two-fork protocol as iteration 71. Each operation
reconciles one unchanged collection; setup checks that identity and topology remain unchanged.
This component benchmark excludes admission, contract handlers, history and persistence.

| Children | Before / after mean reconciliation, µs | Before / after allocation, bytes |
| ---: | ---: | ---: |
| 16 | 19.553 / 20.065 | 78,012 / 79,848 |
| 256 | 368.688 / 328.819 | 1,184,159 / 1,206,350 |
| 1,024 | 2,264.005 / 1,356.111 | 4,723,999 / 4,773,872 |

The wide case improves about 40%; the small case is slightly slower, and the set adds allocation.
At 1,024 children JMH score errors were ±70.215/90.019 µs. The entire reconciliation still performs
linear selection/validation and other graph work; removing this lookup does not make it constant-time.

Whole-host sanity check: the same existing MemoryStore fixture, four-use compaction policy and
12 operations per size, discarding the first four. All 36 input/receipt/gas/identity tuples match.
Median whole calls were 6.846/6.620ms (17 members), 21.658/23.268ms (257) and 74.673/75.795ms
(1,025). This small run shows no whole-operation improvement at the larger sizes; other work and
maintenance dominate. It is not evidence of an AWS speedup.

Full library formatting/check/Javadoc passed: 2,517 tests, zero failures/errors/skips, 23s.
Full host check/distribution/qualification passed: 779 tests, zero failures/errors/skips, 1m31s.
Generated Core SDK journeys passed. Existing conformance, transaction, topology-change and cyclic
restoration tests protect semantics. `EmbeddingReconciliationBenchmark` reproduces the isolated case.
KEEP for removal of confirmed quadratic lookup with measured wide-case benefit and modest complexity;
continue investigation of transaction finish, creation barriers and matching. API matrix unchanged.
AWS stays iteration 67; independent payment growth reached 100 wrappers/four completed receipt flows
with median 1.388s versus 1.396s for the first 50, without full GC. Later route-discovery changes are
being measured in separate cohorts rather than conflated with population growth.


## Iteration 73 — invocation-local initialization candidates (KEEP locally)

Every patch previously checked initialization markers across the reachable graph, including all
already initialized siblings. Initialization markers are validated at invocation entry and protected
from document patches. Creation barriers now discover the initially uninitialized members once,
then include newly imported members. Each barrier still checks current reachability, markers,
in-progress initialization and suspended frames, and sorts actual work canonically. Candidates are
retained throughout the attempt because speculative local frames can temporarily hide or initialize
them; continuation forks receive independent copies. The cache does not cross an invocation boundary.
The existing physical-reuse-disabled mode retains the original full scan.

`ContractInitializationTest` checks an ordinary write before attachment, a child initializer that
creates a grandchild, readiness before the next step reads it, unchanged template data, exact
full-scan snapshot/receipt/gas/event equality, yielded/discarded work and committed retry. Repeated
initialization leaves business content unchanged and emits nothing; its processing revision may
advance. Initial test assumptions compared Java wrapper identity and processing identity; they were
corrected to compare public transition fields and document BlueId before changing production code.
Existing transaction and fresh-subtree tests cover cycles, nested initialization and rollback.

Full library formatting/check/Javadoc passed in 20s: 2,519 tests, zero failures/errors/skips. Full
host check/distribution/qualification passed in 1m34s: 779 tests, zero failures/errors/skips. Generated
Core SDK HTTP journeys passed. No persisted format, public API, gas or execution-edition change.

Standalone JMH uses the same two-fork protocol as iterations 71–72. Each measured invocation includes
construction, validation, first candidate discovery and eight creation-barrier checks. Initial graph
admission is outside measurement; the candidate set starts fresh for every invocation.

| Initialized children | Before / after mean, µs | Before / after allocation, bytes |
| ---: | ---: | ---: |
| 16 | 39.611 / 36.651 | 122,647 / 122,867 |
| 256 | 596.297 / 543.274 | 1,459,348 / 1,463,557 |
| 1,024 | 2,698.543 / 2,436.225 | 5,619,824 / 5,718,297 |

This workload improves roughly 7–10%, with slightly more allocation. At 1,024 children JMH's
reported score errors were ±159.854/59.869 µs. Invocation construction and other graph work remain
linear; the optimization removes repeated initialization scans, not all graph-size dependence.

The one-write MemoryStore probe remains effectively unchanged: medians 6.620/6.771ms, 23.268/22.125ms
and 75.795/76.471ms for 17/257/1,025 members. A separate eight-write probe uses eight distinct values
precomputed by one BEX step, preserving its actual evaluation semantics; the final count is 96 after
12 inputs. An initial diagnostic incorrectly expected eight identical precomputed changes to increment
eight times; its count assertion caught that and the fixture was corrected before measurement.
The comparison restores exactly the two pre-optimization production classes over the otherwise
identical distribution, with a separately compiled baseline and a fresh JVM per run.

| Members | Eight-write before / after median whole call, ms | Before / after mean, ms |
| ---: | ---: | ---: |
| 17 | 9.768 / 8.730 | 10.177 / 9.136 |
| 257 | 27.690 / 27.059 | 28.956 / 28.016 |
| 1,025 | 88.747 / 87.997 | 90.254 / 91.314 |

All 36 input/receipt/gas/identity tuples match for each probe. These bounded samples show no clear
large-graph whole-operation improvement; no AWS speedup is claimed. KEEP for the narrower measured
reduction and removal of repeated N-member eligibility scans while preserving lifecycle semantics.
`GraphSessionRenewalProbe 8` reproduces the multi-write fixture; its default remains one write.
Next investigation: repeated snapshot traversal/allocation and transaction-finalization work.

AWS remains iteration 67. At 200 payment wrappers the independent run had seven settled/root captures,
root-visible median 1.922s, engine median 548.6ms, 44.6% heap and no full GC. The benchmark owner will
confirm the sustained trend at 250 and verify stored results before releasing the deployment window.
The local qualified batch is 68–73. API matrix remains 93 partial / 23 pending, not complete parity.


### Deployment of iterations 68–73

The independent baseline window was explicitly released after final readback: 1,000 counter children,
196 increments across 33 verified counters, the ten-child control, five finite-cycle roundtrips,
251 payment wrappers and ten distinct receipt flows. No pending intents, profilers or benchmark
clients remained. The in-flight 251st addition was completed idempotently and included in readback.

Final plot review identified payment additions 214/230/246 with root-visible stalls of
14.133/13.837/16.058s. Submission consumed 12.668/12.563/14.695s; engine execution remained
0.808/0.570/0.727s. Their 16-payment spacing corresponds to 32 create/payment operations and is
consistent with the previously observed renewal reload. This is evidence motivating iteration 69,
not proof that the new deployment removes it.

Artifact `c137f097c694252de6b93f2bbcd25be805ca63c1c6d9006da776d43565004389` was deployed after
release of that window. SSM `99057e91-6321-4181-b5cb-731d849bb7ea` succeeded from
05:19:54 to 05:20:17 UTC on 2026-11-07, elapsed 23.148s. Native health confirms durable canonical
graphs, native admission, Timeline processing and eight workers. Existing data, credentials and
runtime editions were preserved; the previous owner stopped before the replacement started.

The benchmark chat owns the next bounded acceptance window on this fixed artifact: selected cold
context reads, matched ten/thousand-child counters, a 32-use maintenance boundary, payment receipts
and a finite-cycle pulse. Cold recovery is measured separately from warmed operations. No deployed
speedup or complete API parity is claimed until those results are verified. Local iteration 74 does
not change this deployed artifact.


## Iteration 74 — allocation-free topology value traversal (KEEP locally)

TopologyMap inherited AbstractMap's key/value traversal, which created mutable entry wrappers
while merely reading each member. Dedicated live key/value views and direct forEach delegation now
traverse the owned ordered map. Mutations through iterators, views and entries still invalidate the
lookup cache and structural generation exactly as before; copied maps retain independent ownership.
Four tests cover ordered live views, removal/clear, unsupported insertion, replacement and copies.
No execution semantics, identity, gas, persistence or API response change.

Standalone two-fork CreationBarrierBenchmark, same protocol as iteration 73:

| Children | Before / after mean, µs | Before / after allocation, bytes |
| ---: | ---: | ---: |
| 16 | 36.651 / 36.193 | 122,867 / 117,920 |
| 256 | 543.274 / 529.433 | 1,463,557 / 1,405,770 |
| 1,024 | 2,436.225 / 2,362.401 | 5,718,297 / 5,488,440 |

Allocation falls about 4%. Timing confidence intervals overlap: this is not a demonstrated overall
speedup. The bounded eight-write host probe preserves all 36 input/receipt/gas/identity tuples;
whole-call medians before/after are 8.730/9.463ms, 27.059/27.366ms and 87.997/89.524ms for
17/257/1,025 members. KEEP for lower traversal allocation with small, conventional collection code.
Full library check/Javadoc: 2,523 tests, zero failures/errors/skips, 23s. Full host check and packaging:
779 tests, zero failures/errors/skips, 1m36s. Generated Core SDK journeys passed.

AWS remains iteration 73 while independent acceptance runs. It found a selected-context regression:
/stats returns nested content references rather than scalar values. The fast immutable read opens no
execution session, but this presentation mismatch must be fixed before acceptance. Its first cold
1,000-child operation also took 36.376s root-visible, including 28.962s submission; cold graph loading
is still expensive and is reported separately from warmed processing. No complete parity claim.


## Iteration 75 — readable scalar context from immutable storage (KEEP locally)

AWS acceptance exposed a gap in iteration 70: a selected object such as `/stats` displayed
`{count: {blueId: ...}}` after its scalar children were stored by identity, where the previous warm
response displayed `{count: 1}`. Both prior probe paths had the same cold reference representation,
so merely comparing them did not detect this. The regression test now asserts an independently
specified whole-object JSON result, not only `/stats/count` or equality between two implementations.
The new assertion failed before the fix.

`ContractInspection.readableLiteral` provides bounded exact presentation with verified primitive
scalar references inlined. Ordinary `literal` retains its existing materialization contract. Explicit
object/list references, types, cyclic edges and excluded direct fields remain references; this is not
recursive document expansion. The dashboard uses the readable form. Output bounds and provider
verification remain in force; malformed or unavailable referenced evidence fails the read. Contracts
excluded by the caller are never opened. Canonical state, processing receipts and gas do not change.

Tests cover lossless large integers, repeated scalar references in lists, text, retained object/type
and cyclic references, scalar metadata, exclusions, independent read budgets and forged evidence.
The fixture uses Language's required explicit quoted representation for integers beyond JSON's safe
range. Existing original operation-context cases pass unchanged, including precision, outlines,
publication guards, object references and response bounds. Graph metadata tests reject execution-
topology access while confirming the exact whole-object result and zero execution-session loads.

Full library formatting/check/Javadoc: 2,525 tests, zero failures/errors/skips, 20s. Full host check
and packaging: 779 tests, zero failures/errors/skips, 1m34s. Generated Core SDK journeys passed.
The corrected local OperationContextReadProbe alternates fresh session/reference reads, drops two
warmup pairs and checks both exact expected JSON and restoration counts:

| Managed members | Restore session median, ms | Immutable selected read median, ms |
| ---: | ---: | ---: |
| 17 | 12.899 | 0.809 |
| 257 | 39.275 | 0.496 |
| 1,025 | 158.015 | 0.801 |

These are bounded MemoryStore measurements, not AWS latency or throughput claims.

### Verified AWS acceptance of iteration 73

The independent chat released its measurement window after 40 large counter operations (196→236),
ten small-counter operations (9→19), three complete manual payment receipt flows (10→13, 251 payment
wrappers unchanged), and one finite-cycle roundtrip (5→6). All touched child and root totals matched.
No restart/OOM occurred; final session loads=4, entries=4, compactions=1, failures=0. Four full GCs
consumed 4.936s. The selected `/stats` read took 485.813ms and loaded no session, but its scalar
presentation failed acceptance; this is the regression fixed locally above.

Nine early matched warm probes had engine/root-visible medians 67.606/1,417.730ms for ten children
and 684.921/2,642.643ms for 1,000. The next 30 large probes had medians 438.471/1,950.897ms. The
first cold thousand-child call took 28.962s submission and 36.376s root-visible; opening a preserved
payment embedding separately took 16.406s. Cold admission remains expensive.

Operation 32 published before its maintenance completed: root visible at 1.810s, engine 462ms,
then compaction took 9.602s. Its immediate history performance was empty and populated later.
The next call took 3.615s submission and 6.121s root-visible. Ordinary renewal no longer restores
the graph, but this evidence disproves any claim that its pause disappeared. Exact immutable content
copying, cold reconstruction and graph-wide validation/finalization remain investigation targets.
Do not equate local component wins with flat AWS processing. API matrix remains 93 partial / 23 pending.


### Deployment and AWS acceptance of iteration 75

Artifact `e59f7bda5004e60cc922077dc49cba310c7da030ad2ea9e4c3c01b466262d6d8` deployed successfully.
SSM `e761c5f5-8b39-4693-911b-1ab65576e662` ran 05:33:19–05:33:42 UTC on 2026-11-07, 23.025s.
Native health passed; the previous owner stopped first. The independent chat then performed seven
read-only acceptance requests on the preserved graphs, with PID83063 unchanged and no mutations.

The first cold thousand-child `/stats` read returned the exact independently saved scalar/string
object in 435.305ms: added=1,000, total=236 and all three last-value strings unchanged. Selected total
was 236 in 320.674ms; the stats outline took 305.861ms; children outline returned the exact first
100 lexical paths and continuation 100 in 358.085ms. Small-counter/payment/cycle stats matched their
preserved totals 19/13/6 in 347.990/392.227/611.004ms. Execution-session loads, hits and entries
remained zero before, after the first read and after all seven. No maintenance ran.

Acceptance passed and the window was explicitly released. This proves the presentation correction
and absence of graph restoration for these reads; it does not retest or resolve cold mutation or
maintenance latency. Evidence is retained outside publication source in the separate AWS benchmark
chat's `postdeploy-e59f7bda` report.


## Iteration 76 — original sources retain their private types (KEEP locally)

The generated SDK can now store a private parent definition, extend it, instantiate a document
using the child type, and retrieve its exact original source after initialization and idempotent
creation replay. Original-source lookup previously re-read legacy source with only the global
language, losing account-stored dependencies; the new regression failed on that missing type.
The fallback reader now uses the same account-aware content provider as document admission.
Foreign-account lookup remains denied, and no source/package identities change.

Canonical root and embedded original-source reads now pin the current immutable publication,
read the selected member's initialization identity, and retrieve that exact content through the
publication's verified content index. They no longer restore a mutable execution session. The
existing cold-read test forbids execution-topology access and checks the independently authored
root source identity. Existing embedded history and registration tests remain unchanged.

A bounded MemoryStore probe reads an embedded member from five fresh readers at each size,
drops the first two warmup samples, and verifies its independently calculated original BlueId:

| Managed members | Before median, ms | After median, ms | Session loads before / after |
| ---: | ---: | ---: | ---: |
| 17 | 12.915 | 1.073 | 1 / 0 |
| 257 | 36.482 | 0.722 | 1 / 0 |
| 1,025 | 143.788 | 0.455 | 1 / 0 |

These local reads are not AWS mutation timings. The baseline overlays the exact prior
GraphDocuments class on the same candidate distribution. Setup is outside the timed interval.
Full host formatting/check/packaging: 780 tests, zero failures/errors/skips, 1m33s. The real
native generated-SDK journey passed, including private type inheritance, source identity,
creation replay and readable scalar context. The unchanged library retains iteration 75's
2,525-test qualification. The standalone probe compiled and passed after that host run.

An unrelated typed-listing prototype was discarded before qualification: root-only filtering
would not satisfy original Core's embedded-document inclusion, alias deduplication, unavailable
counts and observation-bound pagination. The API still explicitly rejects that unsupported
selection. Matrix remains 93 partial / 23 pending. AWS remains accepted iteration 75.


## Iteration 77 — embedding addresses from immutable edge metadata (KEEP locally)

Opening a payment embedding took 16.406s on the previous AWS mutation acceptance because
`GraphDocuments.embedding` restored the complete execution graph before searching its edges.
The read now pins one head and looks up the selected native occurrence in the existing immutable
edge index. Address, path, generation and all three publication versions come from that same
head; no document content, mutable execution state or sibling edge enumeration is needed.
The physical format and edge-key encoding are unchanged, including existing publications.

Tests compare complete responses with native inline topology, exercise escaped field names and
nested members, reject a path that is not a direct managed occurrence, check account and missing-
member boundaries, and forbid access to the Language content index. A self-cycle resolves to
its root address without restoration. Replacement changes the target and native generation;
the old pinned page retains its old occurrence. Storage tests cross the radix leaf boundary
(1/32 children) and bound the selected metadata reads to six physical content calls.

Full host formatting/check/packaging: 783 tests, zero failures/errors/skips, 1m37s. All generated
Core SDK journeys passed. The library is unchanged from its 2,525-test iteration 75 qualification.
The isolated probe verifies the complete expected address/generation/version response and
restoration count, with setup excluded and two warmup samples discarded from five fresh readers:

| Managed members | Before median, ms | After median, ms | Session loads before / after |
| ---: | ---: | ---: | ---: |
| 17 | 11.657 | 0.362 | 1 / 0 |
| 257 | 36.147 | 0.223 | 1 / 0 |
| 1,025 | 157.273 | 0.162 | 1 / 0 |

These are local MemoryStore reads, not AWS mutation measurements. The exact iteration 76
GraphDocuments class is overlaid for the baseline on the same distribution. KEEP: a small
metadata-only change with no execution or canonical semantic change. Cold mutation, maintenance
and graph-wide processing costs remain unresolved. No additional API parity claim is made.


### Deployment of iterations 76–77

Artifact `ccbb240441633123dadd642c8deb71060a969b7b0944ff071b60932566dc64ad` deployed successfully.
SSM `f4a0e1e7-2691-42a1-99ae-1f51f53d6bb6` ran 05:52:00–05:52:23 UTC on 2026-11-07, 23.079s.
The old owner stopped first; native durable/timeline/graph health passed. The benchmark chat owns
the read-only acceptance window. No new mutation performance claim is made.


### AWS read acceptance of iteration 77

Nine read-only checks passed on the preserved graphs; PID84278 and artifact `ccbb2404` stayed
unchanged. The first cold wrapper2→payment address lookup took **316.787ms**, versus the previous
16,405.684ms. All seven response fields matched the saved child IDs/publication 542 and unchanged
first generation; the earlier entire payment response was not retained, so this expectation was
reconstructed and is not claimed as a byte-for-byte saved-response comparison.

Root→wrapper2 took 303.407ms; repeat wrapper2→payment 326.628ms; root→wrapper3 251.318ms;
wrapper3→payment 348.798ms. Cycle root→child took 251.842ms and child→root 250.001ms, the latter
matching its complete saved response at version 8. Thousand-child `/stats` took 425.826ms and
matched added=1,000, total=236 and all preserved strings. The payment root's original identity
matched through Core metadata in 338.679ms. That metadata may be cached, so it does not qualify
direct `initial()` materialization; source-read coverage remains local and SDK based.

Execution-session loads/hits/entries remained zero before, after the first lookup and after all
reads. No mutations or profiler ran. The window was explicitly released. This demonstrates the
cold address-lookup improvement on AWS, not a change to mutation admission, graph processing or
compaction. Evidence is outside published source in `postdeploy-ccbb2404/acceptance.json` and
`results.html` in the independent benchmark repository.


## Iteration 78 — native relationships without execution restoration (KEEP locally)

The original Links tab still restored the execution graph to enumerate relationships. It now reads
one pinned publication's compact edge metadata and exact endpoint identities. A persistent adjacency
index maps a member to its incoming/outgoing native occurrences. Only members touched by an edge
change get rewritten; ordinary value changes reuse the whole adjacency root. Endpoint records are
batched, and only endpoints present in the requested relationship view are read. Work for a child
with one link no longer depends on enumerating all sibling edges. A root with many links still has
work proportional to its returned relationship set; public paging currently orders that whole set.

This is an optional physical manifest field, not a canonical snapshot or execution-format change.
Existing publications scan compact edge metadata, never execution content. Their next publication
adds the index. Older readers ignore the optional field; rollback writers may omit it and fall back
again. All historic pages remain immutable. No canonical BlueId, receipt, generation or gas changes.

Tests cover exact cold snapshot reproduction, index adoption with and without existing edges,
bounded selected reads across the radix leaf boundary, unchanged index roots after value writes,
escaped/nested paths, two occurrences of the same child, one self-cycle occurrence, current endpoint
identities, replacement generations, detachment, old publications and account isolation. Content-
index guards make an accidental execution restore fail. Original Core relationship direction,
occurrence and continuation tests pass unchanged. Full host formatting/check/packaging passed:
784 tests, zero failures/errors/skips, 1m34s. Generated SDK journeys passed. The added standalone
topology probe initially had an undeclared report field; corrected and compiled successfully before
its measurements. Production sources did not change after the full suite.

Cold single-child relationship lookup, five fresh MemoryStore readers, first two samples discarded:

| Managed members | Restore session median, ms | Indexed metadata median, ms |
| ---: | ---: | ---: |
| 17 | 12.966 | 0.763 |
| 257 | 36.835 | 0.502 |
| 1,025 | 149.462 | 0.535 |

Every complete endpoint/path/generation/version response matched native topology; session loads
fell from one to zero. Setup was separate. Baseline overlays the exact prior GraphDocuments and
CanonicalSnapshots classes on the same candidate distribution.

Mutation qualification checks the cost of maintaining this physical index:

| Starting members | Eight-write invocation before / after median, ms | Add one child before / after median, ms |
| ---: | ---: | ---: |
| 17 | 9.513 / 8.810 | 10.651 / 11.106 |
| 257 | 26.680 / 26.508 | 28.043 / 26.631 |
| 1,025 | 84.875 / 86.797 | 86.445 / 87.459 |

The first probe runs 12 invocations per size, four warmups, and includes periodic maintenance.
The second adds six actual native children per size, two warmups, with maintenance outside its
short run. All 36 and 18 input/receipt/gas/snapshot-identity tuples respectively match the baseline;
added children are verified admitted. These bounded runs show no clear write improvement and small
timing differences; they are not confidence intervals or AWS throughput guarantees. Index creation
adds physical pages, and existing publications incur one-time adoption on their next write.

KEEP for the metadata read improvement with unchanged canonical behavior. Library sources remain
at iteration 75 qualification (2,525 tests). AWS is still accepted iteration 77; this change has not
been deployed or measured there. Matrix remains 93 partial / 23 pending. Cold mutation admission,
long compaction and graph-wide processing costs remain unresolved.


## Iteration 79 — exact member content without restoring unrelated execution (KEEP locally)

Reading an exact member still restored the whole graph even when only its immutable content was
needed. The adapter now pins its historical publication and admits that member through the stored
content provider. Language still verifies the selected content and dependencies. A live, idle
session at the exact requested version can supply its existing immutable materialization; a missing,
busy or differently versioned session is never restored or waited on for this read. The short view
capture uses the same cache monitor as acquisition/eviction and never exposes a mutable session.

The first simple replacement exposed an existing presentation distinction: warm state can retain
an inline object where persisted content uses a reference to that same object. The finalized change
preserves the available warm materialization and compares the cold path against the prior cold
presentation separately. It does not claim warm and cold exact JSON were identical. BlueIds and
resolved values remain identical; explicit retained references stay meaningful Blue content.

Tests assert complete warm/cold exact presentations independently, selected and historical member
values, provider access while execution topology is forbidden, cyclic content, private dependencies,
account/version isolation, active-lease bypass and validity of borrowed content after further writes
and close. Full host format/check/packaging: 786 tests, zero failures/errors/skips, 1m39s. Generated
SDK journeys passed. The standalone probe's variable naming cleanup was compiled afterward;
production sources were unchanged. The library remains at iteration 75 qualification.

Five fresh MemoryStore readers per size, first two warmups discarded; setup and output inspection
are outside the timed content call. Current member identity and value are checked independently;
all full expanded-root wire hashes match the exact prior GraphDocuments baseline:

| Managed members | Before median, ms | After median, ms | Session loads before / after |
| ---: | ---: | ---: | ---: |
| 17 | 12.878 | 1.323 | 1 / 0 |
| 257 | 35.529 | 0.572 | 1 / 0 |
| 1,025 | 153.288 | 0.503 | 1 / 0 |

These are local small-member reads, not proof that large or dependency-heavy member content is
constant cost. Full root materialization and semantic admission still cost their selected dependency
closure. Mutation/maintenance are unchanged. KEEP; awaiting deployment with iteration 78. No full
API parity claim: matrix remains 93 partial / 23 pending.


### Deployment of iterations 78–79

Artifact `1c3bc0ade2d2b069f3816fc5470f0bce27b3cb0578e9b32633aec20a0606dd8d` deployed successfully.
SSM `b0f2bc2b-7d5b-4969-8436-730881f25a2c` ran 06:12:41–06:13:04 UTC on 2026-11-07, 23.019s.
The old owner stopped first; native durable/timeline/graph health passed. The independent benchmark
chat owns a bounded window: first exact-content/relationship read acceptance, then, only if it
passes with cold execution caches, one profiled cold thousand-child counter operation. The latter
also exercises first-publication adjacency adoption. No growth or maintenance campaign is requested.
No AWS result for this release is claimed before that evidence arrives.


### AWS acceptance and repeat-read profile for iteration 79

The exact payment content matched published identity
`GPPw78SygbP1NUU9xSbrpZcGp1pF9EgLzbC7teZmUYax`, receipt fields, amount and active state,
but took 45,523.008ms. Relationship directions/endpoints/generations passed after the harness
added four legitimate Timeline-access edges to its expectation; the original assertion mismatch
and corrected cause remain in the evidence. Median relationship latency was approximately 274 ms.
These existing manifests use the compact-metadata fallback; no indexed AWS claim is made.
Root stats remained 236. No mutation or execution session was loaded.

One bounded repeat, with JFR started first, took 38,739.193 ms: 38,385.774 ms before headers and
353.419 ms downloading 130,499 wire bytes. Same body and identity. Of 460 request-thread Java CPU
samples, 340 (73.9%) included Language admission/validation, 303 (65.9%) CanonicalSnapshots.content,
and 110 (23.9%) AWS storage; categories overlap and 274 stacks were truncated. Recorded request-thread
AWS socket waits totaled 6.45456 s. No sampled hotspot was CoreContents.retain or export. This is
directional profiling evidence, not an exclusive wall-time partition. PID 85497 stayed healthy,
FGC 0 and all execution-session counters zero. The verified local profile was removed remotely.
The planned cold-counter mutation was suspended, not run.

## Iteration 80 — verify exact display without repeating semantic admission (KEEP; deployed)

Core exact document reads now use a dedicated presentation path returning LanguageContent. It
opens and verifies the selected exact body and any required cyclic owning-set proof without
claiming semantic admission or walking the full dependency closure for validation. Existing strict
LanguageDocument reads remain for execution, import and operation evidence. Idle warm immutable
materializations are preserved; cold output matches the previously persisted materialization.
Complete closure retention still runs for reference browsing. An attempted physical-record alias
optimization was rejected: the SDK showed that it replaced some warm inline fields by references
on a subsequent content-addressed GET. Equal BlueIds did not satisfy the expected JSON presentation.
The final candidate retains the returned materialization through the existing retention path.
This does not pretend that one stored body proves its whole closure is retained.
No canonical identity, gas, execution semantics or published storage format changes.

A synthetic linked-history probe separates display from retention. Four reads per size; the first
display sample excluded from the following medians; all complete wire hashes match the independently
selected committed exact record:

| Linked private records | Strict display before, ms | Presentation after, ms | Repeat retention before/after, ms |
| ---: | ---: | ---: | ---: |
| 8 | 1.900 | .263 | .188/.123 |
| 64 | 3.017 | .283 | .093/.066 |
| 128 | 4.010 | .121 | .079/.053 |

First retention was 5.145/8.289, 4.747/10.310 and 4.083/8.134 ms respectively. It remains closure-sized,
and validation/export work shifts between phases; no first-retention improvement is claimed. These
are bounded local MemoryStore measurements, not AWS latency or statistical confidence intervals.
The first full host run passed 788 tests, but the generated SDK then caught the physical-alias
presentation change described above. That optimization was removed; all generated SDK journeys
now pass. A native API regression test additionally compares the returned whole exact document
with later content-addressed reads after restart. Its initial fixture used an unsupported bare large
integer; the explicit lossless Integer form corrected that setup error. Final full host format/check/
packaging passed: 789 tests, zero failures/errors/skips, 1m35s. The final production code passed the
generated SDK; library sources remain at the prior 2,525-test qualification. KEEP; no AWS claim yet.
Matrix remains 93 partial / 23 pending. Cold execution and maintenance remain unresolved.


### Deployment of iteration 80

Artifact `6984b9cede7fcef7f7d29b0f034c1fc0f10c0d97fe0012a497922e4f672d60fa` deployed successfully.
SSM `fc118482-fc2a-4f65-bedc-1a69892f98ce` ran 06:31:01–06:31:23 UTC on 2026-11-07, 22.935s.
Account/region were verified and the old owner stopped first. Native graph/durable/Timeline health
passed. The benchmark chat owns a bounded read-only acceptance window comparing the original
slow payment's complete content and identity, another payment's first read, content-addressed
round-trip, cycle content and unchanged root stats. No mutation or AWS performance claim yet.


### AWS acceptance for iteration 80 — PASS

On the unchanged deployed artifact and fresh execution caches, the identical pinned payment2
exact GET took 718.304ms, versus 45,523.008ms first-read and 38,739.193ms profiled repeat on 79.
Complete saved JSON and independently recomputed BlueId matched. Different payment3 exact content
took 1,106.824ms with matching published identity, amount, acceptance/completion, target and receipt.
Payment2's content-addressed GET took 421.049ms with the same full presentation. The cycle exact route
took 667.294ms but returned a reference-only `{blueId: ...#1}` representation. Its ID and unchanged
root stats passed; that response is not independent full cyclic proof/body verification. Full cyclic
presentation/provider validation is covered locally, not newly proved by this AWS response. Four root stats took 396–472ms and
remained 236/19/13/6. Thirteen HTTP reads total, one attempt each, no retries, mutation or profiler.
All execution-session loads/hits/entries remained 0; PID 87437 stayed healthy.

The accepted evidence is retained by the benchmark chat under
`blue-java/benchmarks/aws-2026-11-07/postdeploy-6984b9ce/`; this project does not copy raw responses
or profile archives into published source. The bounded single cold-counter profile is now requested
under an exclusive benchmark window. It will separate preparation/submission/execution/commit
and root visibility; no new mutation or maintenance latency is claimed yet.


## Iteration 81 — recognize operations without restoring execution (KEEP; local only)

`Contracts.operationRequest(message)` uses the execution grammar without opening a session. It
still certifies the actual document and request values. The host pins account-scoped graph metadata,
resolves the selected member and checks committed target lineage directly. Authored-root fallback,
exact/historical target identity, missing-member rejection and the ordinary publication/authorization
checks remain. Merely recognizing an operation never authorizes it.

Current completed-value evidence is now optional. If the exact version already has an idle retained
session, admission can borrow it; a cache miss instead supplies the pinned immutable record provider
and validates the actual input dependencies. It no longer opens the current whole graph just to
obtain optional evidence. Legacy local-edition evidence retains its previous behavior. The shared
entry-admission paths use the same account/store-scoped provider.

**Qualification:** full library formatting/check/Javadoc passed, 2,532 tests with no failures/errors/
skips (20s). Full host formatting/check/build passed, 791 tests with no failures/errors/skips
(1m34s). The generated Core SDK journeys passed. Request tests cover private types and targets,
malformed grammar, missing/forged evidence, wrong member/account, authored roots and zero execution
loads. The probe initially used an ordinary External Channel, which is not a native timeline
receiver and therefore has no published timeline address; the final probe uses the actual pinned
Coordination Public Timeline Channel. No failing fixture was counted as a successful measurement.

`GraphOperationPreflightProbe` compares iteration 80 GraphDocuments/CanonicalGraphs with the candidate
using identical immutable targets and messages, local MemoryStore, two warmup samples and three
reported samples per size. These medians include recognition and committed-lineage lookup only:

| Managed members | Previous ms | Candidate ms | Execution sessions loaded |
| --- | ---: | ---: | --- |
| 17 | 11.960 | 3.002 | 1 → 0 |
| 257 | 36.846 | 1.447 | 1 → 0 |
| 1,025 | 139.803 | 0.964 | 1 → 0 |

The ordering/JIT effects make the candidate's decreasing times unsuitable as a scaling curve.
This is evidence that the unrelated graph restore is removed, not a claim of sub-millisecond AWS
submission, processing or historical dependency validation. Raw results remain outside the source
repository in `/private/tmp/myos-dashboard-parity/iteration81-{before,after}.json`.

### Existing AWS cold-operation failure remains unresolved

On deployed iteration 80, one exact-current-child operation timed out after 60.216s, before append
publication or graph execution. The target was the current child version, not an old small target.
The profile recorded semantic validation/dependency reads on the admission request. The graph heads,
root count 236 and child count 80 stayed unchanged. The original admission remains PENDING; its
idempotency key and original input are preserved and have not been replayed. No engine/gas/commit
latency can be inferred from this failed submission. Iteration 81 is not deployed as a claimed fix
for this case: its target closure still requires certification on a cold request.

Next investigate protected reuse of completed semantic validation across host restarts. New content,
new constraints, account scope, exact identities and cyclic owning-set proofs must continue to be
checked; an arbitrary request BlueId must never assert previous admission. No spec/package changes,
new trust shortcut, or persistent validation checkpoint is included in iteration 81.


## Iteration 82 — recover protected prior admission (KEEP; deployed, AWS acceptance pending)

The new public `LanguageCheckpoint` is explicit local recovery metadata issued only for a completed
`LanguageDocument`. The protected commitment binds the exact value, Language edition and format.
Recovery still verifies the selected body and cyclic owning-set proof, then reuses the host's prior
semantic result. Untrusted reads/imports remain strict. The host must retain the entire immutable
closure; this is not a portable proof or permission from an arbitrary supplied digest. New request
content and contextual constraints remain checked. Full details and the trust boundary are in the
library's `docs/ADMISSION-CHECKPOINTS.md`.

Canonical physical manifests now optionally contain per-member admission checkpoints, published
with the existing atomic head/content boundary. Unchanged members reuse their old checkpoints;
changed members use the completed candidate exposed by `Contracts.Transaction.member`. These extra
physical records do not enter canonical snapshots, receipts or deterministic gas. Cold account-scoped
member evidence can recover its prior completion without loading an execution session. Stateless
operation recognition accepts that same invocation-scoped evidence, and revokes its facts/provider
scope afterward. Current account, member lineage, native permission and source publication checks
remain independent.

Old manifests are unchanged. They have no implicit certificate. An explicit legacy backfill first
performs full selected-member validation, then stores an optional account/page/member/edition-bound
cache record. It never changes the original graph head, version, manifest, receipt, timeline or pending
operation. Repeating it reuses the same record; failed validation writes none. A normal future graph
publication can also capture the already validated members' checkpoints. The qualification-only
`PublishedAdmissionRecovery` CLI takes an explicit account, document, current version and exact BlueId
plus `--retain-admission`; it verifies the pinned selection and unchanged graph head. It is not an
HTTP route and has not yet been run on AWS.

**Checks:** full library formatting/check/Javadoc passed, **2,538 tests**, no failures/errors/skips
(22s). Full host formatting/check/build passed, **796 tests**, no failures/errors/skips (1m35s).
All generated Core SDK journeys passed. The separate recovery CLI compiled after these checks.
Focused tests initially exposed fixture mistakes (record object equality instead of serialized
content, an absent Integer value that is not a present invalid request, and authored versus initialized
identity); corrected assertions compare serialized records, a real minimum-constraint violation and
the independently committed member identity. No failed result was used as passing evidence.

The bounded `PublishedAdmissionProbe` prepares and recognizes identical requests against a small
current member with a private linked history. Each mode uses a fresh host read context, local
MemoryStore, alternating order, two warmup pairs and three measured pairs. Storage calls count
immutable content reads; network and execution are absent.

| Historical values | Ordinary median ms | Checkpoint median ms | Ordinary content reads | Checkpoint content reads |
| --- | ---: | ---: | ---: | ---: |
| 8 | 3.172 | 1.453 | 28 | 8 |
| 64 | 4.959 | 0.845 | 147 | 8 |
| 128 | 6.081 | 0.557 | 359 | 8 |

Request and target identities and parsed operation fields matched for every pair. Decreasing candidate
times reflect ordering/JIT effects and are not an AWS scaling curve. The meaningful result is the
constant number of selected-record reads. Raw evidence remains outside the source tree at
`/private/tmp/myos-dashboard-parity/iteration82-admission.json`.

**Remaining limits:** no AWS submission, engine, commit or maintenance speedup is claimed yet.
Pre-checkpoint AWS history may still need substantial one-time validation. A failed or timed-out
backfill cannot assert completion. The earlier original operation remains pending and untouched;
its terminal server-side cause remains unknown (HTTP 504 alone does not establish a Language limit
or cancellation). Cold execution still reconstructs/validates the graph independently. Full dashboard
API parity remains 93 partial / 23 pending / 0 equivalent; this iteration changes no parity count.


### Deployment of iteration 82

Promoted qualified source hashes match the staging source trees. Built the target distribution and
qualification JAR, then deployed archive `1d179ca05f87a33891780b1763a409503c87f062877be69a060f0d9d282e1ba6`
to the existing isolated AWS stack. Deployment command
`a831770e-6f40-43a8-8c21-ebcd64b2dc8d` succeeded 2026-11-07 07:10:12–07:10:35 UTC, 23.161 seconds.
The previous owner was stopped before the new process started. Native canonical graph/Timeline health
passed; Corretto 25.0.5, eight workers and existing cache/heap settings are unchanged.

Qualification JAR SHA-256:
`2b3f774fba29356362865d426bb6dcd2eeb81da854b9b6f5ca320d1acebeae5a`.
Deployment and qualification pins are retained outside source under
`/private/tmp/myos-dashboard-parity/iteration82-*`. The benchmark chat owns the next bounded legacy
admission recovery window. No operation was submitted or replayed during deployment. AWS submission
and execution performance remain unqualified for this release; local admission results are not AWS
latencies. Account/partition identifiers stay within the benchmark chat after automatic review denied
their transfer; qualification can use that chat's already saved values without transmitting them here.


### AWS legacy admission recovery — INCOMPLETE

The single bounded standalone check used the pinned current child and exact release/tool artifacts.
It failed with `OperationLimitExceeded`, reason `steps`, after **142,209.130ms** of validation
(143,903.256ms standalone elapsed). It did not reach the 180-second external timeout. Storage-interface
calls were **50,608 content**, **569 contentAll**, **2 get**, **0 commit** and **0 publishContent**,
with no reported store failures; these are interface calls, not an asserted count of physical AWS
requests. No admission cache was written. Final admitted identity equivalence was not established.

All guarded durable rows remained unchanged: graph head, pinned history, timeline state and pending
admission. The append receipt remained absent. Service restoration/health passed, execution session
loads/hits/entries remained zero, and remote evidence was transferred, hash-verified and removed.
No retry, raised limit, operation submission or profiler was run. The earlier HTTP 504's server-side
terminal category remains unknown; this later recovery attempt alone has an observed steps-limit
failure.

This result is not acceptable operation latency and is not reported as a successful migration.
Iteration 82's checkpoint path remains qualified for newly published completed values. The old
publication needs a different explicit recovery path. The next iteration will inspect the prior
successful Contracts publication itself as protected completion evidence: verify its independently
trusted snapshot commitment, exact release and member metadata, plus selected body/owning proof,
without treating it as an untrusted portable import. Ordinary untrusted verification remains strict.
No such additional recovery path is implemented or deployed in iteration 82.


## Iteration 83 — explicit recovery from a protected Contracts publication

**Decision: KEEP; bounded AWS recovery passed.** The previous legacy full-validation attempt reached its normal
steps limit after 142 seconds without writing a checkpoint. Raising the limit would not address the
history traversal. The new path reuses the completion already established by this host's successful
Contracts publication, under an explicit protected-storage boundary.

`Contracts.recoverMember` authenticates immutable metadata against the separately protected snapshot
identity, checks the supported Contracts release, unique membership and root, then verifies the
selected member's exact body and cyclic ownership. Only configured retained providers supply bodies;
embedded envelope content is not installed. It returns a completed Language value, without creating
an execution session. Strict `Language.read` and portable `Contracts.restore` remain unchanged.

`CanonicalSnapshots.retainPublishedAdmission` reconstructs canonical document/edge metadata without
reading the historical content catalogue. Its expected identity comes from the protected HEAD row.
Only after successful recovery does it write the optional account/page/member/edition-bound admission
cache. Metadata hashing is a one-time O(members + edges) migration cost. Subsequent cache recovery
reads selected content. Complete immutable closure retention remains required, and this boundary is
inappropriate for untrusted imports or arbitrary supplied BlueIds.

The qualification-only `PublishedAdmissionRecovery` CLI now requires `--recover-publication` instead
of `--retain-admission`, preventing an old invocation from silently changing trust boundaries. It
pins the explicit current version and BlueId, takes the expected commitment from HEAD, verifies cold
cache recovery and checks HEAD again. Its output omits document/account identities. It submits no
operation and changes no original snapshot, history, receipt, Timeline state or pending request.

Full library formatting/check/Javadoc passed: **2,543 tests**, no failures/errors/skips, 21 seconds.
Full host formatting/check/build passed: **798 tests**, no failures/errors/skips, 1m36s. All generated
Core SDK journeys passed. The qualification tool was then recompiled after adding sanitized
`BlueException` diagnostics; raw identifiers/messages are not emitted. A separate read-only review
found no blocking issue in the recovery boundary; its diagnostic comment was addressed.

The separate `PublishedRecoveryProbe` uses local MemoryStore, a fixed 128-value private history,
one warmup and three measured repetitions, and a fresh read context each time. It compares explicit
metadata recovery/cache retention with subsequent selected checkpoint recovery; no execution,
Timeline append, HTTP or AWS is included.

| Graph members | Metadata recovery median ms | Selected checkpoint median ms |
| --- | ---: | ---: |
| 2 | 0.922 | 0.507 |
| 129 | 2.845 | 0.241 |
| 1,025 | 13.168 | 0.174 |

Metadata reconstruction/hash still scales with graph size once; checkpoint reads use a radix lookup
and the selected body. Decreasing checkpoint times reflect JIT/order effects, not a scaling claim.
The first sample creates the optional cache; subsequent metadata samples repeat authentication and
idempotent retention. Every identity matched and original portable snapshot bytes remained equal.
Raw evidence: `/private/tmp/myos-dashboard-parity/iteration83-recovery.json` (outside source).
No iteration-83 AWS recovery or operation replay has occurred.
Full UI/API parity remains incomplete; no matrix entry changes in this iteration.


### Iteration 83 deployment

Promoted source hashes matched the qualified staging trees. Archive
`0d8d03d1a9cac459903b11807edbc7fe5d498017f9cb1aea98af4e5c38ee056d` deployed successfully through
SSM `20d9571f-e868-4b47-bb31-0243ba1ad88d`, 2026-11-07 07:27:58–07:28:20 UTC (22.738s).
The old owner stopped before startup. Native canonical/Timeline health passed with eight workers and
unchanged heap/cache/runtime settings. Qualification JAR:
`7ed1dd0ee516f3a63479c8ad59e543221ce52821c3e8e0d632b6a521cf43de7f`.
The benchmark chat owns the next single bounded recovery check using its existing local selectors.
No operation was replayed during deployment, and no AWS performance improvement is claimed yet.


### AWS protected-publication recovery — PASSED

One bounded standalone run authenticated the selected previous publication and retained its admission
cache in **1,865.964ms**; fresh Language/CanonicalSnapshots readback took **38.580ms** in the same
process/store caches. Total standalone elapsed was **3,533.913ms**, without timeout. The readback
number is not a fully cold-host or end-to-end operation measurement.

Selected identity/version matched. Graph head, pinned history, Timeline state and pending admission
remained unchanged; the original append receipt remained absent. Exactly one optional cache commit
occurred, with zero content publications. Storage-interface calls: content39, contentAll115, get4;
no failures. These are interface calls, not physical AWS request counts. All57 runtime JAR hashes and
the tool hash matched. Service restoration and health passed; execution loads/hits/entries remained
zero. Evidence was transferred/hash-verified and remote artifacts removed by the benchmark chat.
No operation or profiler ran in this window.

This removes the specific legacy admission migration blocker. It does not establish operation latency,
cold execution performance or 100,000-member scaling. The next coordinated window is exactly one retry
of the preserved PENDING increment, using its original payload and idempotency key, after checking
unchanged baseline/receipt guards. No new mutation/key or repeated retry is authorized by that check.


### Iteration 83 AWS operation acceptance — correct, still slow

Exactly the preserved request and original idempotency key were submitted once through the generated
Core SDK. HTTP202 arrived in **1,736.999ms** and the original admission became SUCCEEDED. The child
changed once (80→81), observed at **38,776.364ms**; its parent changed once (236→237), observed at
**39,020.712ms**. Both version deltas were1. The sequential child/parent reads account for part of the
244.348ms observation gap; that gap is not an isolated propagation measurement.

Server timings: **29,963.988ms cold session restoration**, **2,893.833ms engine execution**,
**36,286.466ms processing-through-commit**, **37,127.619ms signed-entry-through-commit**. Remaining
processing/commit work was **3,428.645ms**. Gas was1,204,129; no compaction/renewal occurred. Original
request fingerprint, original-key append entry, original admission entry and committed-history entry
matched, and old history remained unchanged. Session loads/entries advanced0→1, hits increased1.
The artifact and process were unchanged and service health passed. No new key, second retry, extra
increment or profiler was used. The benchmark chat released the window and the now-warm host is
preserved.

The admission regression is addressed, but this remains unacceptable end-to-end latency. Cold
execution still reconstructs the retained closure and strictly restores the entire graph. No claim
of millisecond operation performance follows from the successful admission migration.

## Iteration 84 — separate cold loading from Contracts restoration

**Decision: KEEP locally; full checks passed; not deployed.** The existing combined cold-restore
timing explained29.96s but did not separate retained content loading from Contracts verification.
Added `GRAPH_LOAD` and `GRAPH_RESTORE` to existing low-cardinality process metrics. Each phase now
also reports active scopes, so in-flight work remains visible before completion/failure. Scopes
release their active count on failure and tolerate repeated close without double-counting. No
account/document identifiers are captured, and no Blue wire format, deterministic gas, validation,
publication or cache policy changes.

Focused `CanonicalSessionCacheTest` and `TimelineMetricsTest` passed. They distinguish one cold
load/restore from warm reuse and prove active/failure/nested-scope accounting. Full host formatting/check/build passed: **800 tests**, no failures/errors/skips, 1m34s.
No deployment has been requested for these metrics alone.

The next execution design must preserve the installed release/native bindings and exact selected
body/owning-proof checks while explicitly reusing prior host completion. Ordinary portable imports
must remain independently strict. This execution recovery is not implemented or qualified by83/84.


### Iteration 83 warm operation qualification

Two further ordinary increments used fresh targets and unique keys, one SDK attempt each. Both were
warm: no session load, renewal, compaction or failure; same process/artifact. Each child/parent count
and version advanced exactly once; target fingerprint, admission, append and committed-history
linkage matched. The original terminal admission remained byte-for-byte unchanged. Final counters
were child83 and parent239. No profiler or restart occurred.

| Metric | Warm1 ms | Warm2 ms |
| --- | ---: | ---: |
| HTTP submission | 1,185.407 | 884.644 |
| Child observed | 5,175.626 | 3,729.132 |
| Parent observed | 5,432.974 | 3,969.987 |
| Engine | 1,443.780 | 890.540 |
| Processing through commit | 3,303.112 | 2,527.864 |
| Signed entry through commit | 3,750.739 | 2,901.396 |
| Other processing/commit | 1,859.332 | 1,637.324 |

Gas was1,202,935 per call. Sequential HTTP observations include client polling/read overhead. These
results isolate substantial warm execution/publication overhead, independently of cold restoration;
they are not acceptable millisecond end-to-end performance.

### Read-only restoration attribution tool

`PublishedGraphRestoreProbe` independently times `loadForExecution` and ordinary `Contracts.restore`
for a version/BlueId-pinned current publication. It checks HEAD before/after, verifies selected
restored identity, uses the same installed native Timeline bindings, and throws if any input verifier
is invoked or any storage write occurs. It prints sanitized phase timing/storage deltas. It changes
no recovery semantics and runs no operation. `--local` passed using the exact iteration83 runtime
JARs, independently of the new instrumentation: selected identity/HEAD unchanged and zero writes.
This is a qualification tool, not a service endpoint. AWS attribution is not yet run.


### AWS read-only restoration attribution — PASSED

The single pinned standalone check completed in **35,270.313ms**, with2GiB maximum heap and
1,549,556KiB peak child RSS, no timeout. `loadForExecution` took **14,991.072ms**; its storage delta
was2,043 contentAll calls /11,789.083ms and zero other interface calls. Ordinary strict Contracts
restoration took **18,499.337ms**, with every storage-interface delta zero. It reused the data/caches
populated by loading in this fresh JVM. The restoration time is not additional measured storage work;
it is not labeled pure CPU without a profile.

Selected identity matched and all12 guarded durable rows remained unchanged: graph HEAD, four history
rows, Timeline state, all three accepted admissions and their append receipts. No storage write,
input verifier call, operation, cache write, retry, binding workaround or raised limit occurred.
All57 runtime JARs and the qualified tool matched their pins. Service restoration/health passed and
remote artifacts/evidence were cleaned after verified transfer. The restored service has a fresh
execution cache; the earlier warm process measurements were captured before this stop.

Pre-stop cumulative GRAPH totals across the three accepted updates were: snapshot3calls/5,855.398ms,
admission6/845.455ms, addresses3/54.720ms, subscriptions3/74.079ms, commit3/130.413ms.
Snapshot averaged1,951.799ms. These are inclusive cumulative process counters, not individual warm
sample deltas. Other Timeline/store totals include background and observation work. The warm
“other processing/commit” numbers above are arithmetic remainders, not attributed phase measurements.

## Iteration 85 — selective retained-catalogue access

**Decision: KEEP locally; AWS acceptance pending.** A cache miss for the whole content catalogue caused retained checks
and delta updates to enumerate its entire history. A catalogue larger than the16MiB whole-map cache
could repeat this work on every update. The revised path reuses that cache when present and otherwise
queries only needed radix paths. Current member identities are batched as read-ahead hints, with
actual catalogue membership still authoritative. Delta updates read only the keys being updated;
no full historical map is copied when unavailable. Full exports, deletions in non-delta snapshots,
canonical identities, closure obligations and cache bounds remain unchanged.

`RadixIndex.getAll` groups selected keys by immutable hash path and batches each depth's pages.
Focused tests cover point/bulk equivalence, absent/duplicate/Unicode keys, disabled caches, previous
roots and foreign stores. Snapshot tests disable the whole-map cache and bound fetched pages for a
membership check and small delta against1,024 retained historical values, then verify independent
restoration, old publication bytes and retained history. All focused tests passed. Full host checks
and the bounded `PublishedCatalogueProbe` comparison are in progress. No iteration85 deployment or
AWS speedup is claimed.


### Iteration 85 local qualification

Full host formatting/check/build passed: **802 tests**, no failures/errors/skips,1m34s. All generated
Core SDK journeys passed. The new cache-disabled snapshot regression ran in77ms; its complete class
ran in236ms. No library source or semantic edition changed.

The identical `PublishedCatalogueProbe` ran sequentially against the pinned83 runtime JARs and the
candidate distribution, with1GiB heap, local MemoryStore, two warmups and three measured samples.
Every sample starts a fresh catalogue read context; setup, execution and independent verification
are excluded. The fixture adds independent historical values whose complete records may include
additional helper records. The raw field `historicalRecords` in this first probe revision counts
those values, not necessarily the distinct record-map entries; the tool's output label is corrected
for future use. Raw evidence remains outside source as `iteration85-catalogue-before.json` and
`iteration85-catalogue-after.json`.

| Historical values | Previous export median ms | Selective export median ms | Previous save median ms | Selective save median ms | Previous export page requests | Selective export page requests |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 128 | 1.626 | 0.705 | 1.396 | 1.529 | 140 | 22 |
| 2,048 | 4.707 | 0.379 | 0.935 | 0.521 | 1,531 | 39 |
| 32,768 | 365.306 | 0.258 | 354.417 | 0.419 | 24,743 | 56 |

Page requests count calls to the test store's content interface, including repeated requested pages;
they are not AWS RPC counts. Save requested one further page in each measured case. Old publication
bytes, deterministic physical page identity across repeats and independently restored canonical
snapshots all matched. Small-case save timing was slightly higher; decreasing candidate times reflect
JIT/order effects. This demonstrates removal of historical full-catalogue work in the isolated path,
not yet an AWS end-to-end speedup or a solution for cold strict execution restoration.


### Iteration 85 deployment

Promoted source hashes matched the qualified staging trees. Archive
`a6e2d258514fc4442db634919690fe8d93f80bc1e9ee5667ce0ab13ed08153c1` deployed successfully through
SSM `34a4515d-c528-4ba9-b120-e6130870d7ac`,2026-11-07 07:54:46–07:55:08UTC (22.668s).
The previous owner stopped before startup; native canonical/Timeline health passed with unchanged
workers, heap, cache and runtime settings. Runtime/tool pins are outside source in
`/private/tmp/myos-dashboard-parity/iteration85-qualified-artifacts.json`.

The next benchmark window is one explicitly timed cold operation-list GET, followed only on success
by two warm increments with complete per-sample phase counters. No operation occurred during
deployment. Cold restoration remains unchanged and must not be hidden by this preload. AWS snapshot
and end-to-end improvements remain unqualified until that check finishes.


### Iteration 85 AWS acceptance — snapshot improvement, end-to-end target unmet

All57 deployed runtime hashes and native health matched. One read-only generated-SDK operation-list
GET took **31,066.445ms**: GRAPH_LOAD15,099.109ms and GRAPH_RESTORE15,162.947ms. All12 prior durable
guards were unchanged; storage commits and content publications were zero. This was explicitly cold
setup, not excluded from the report or presented as fast execution.

Exactly two new normal increments followed, with refreshed targets and unique keys, one attempt
each. No profiler, restart, retry or recovery-cache write occurred. Both child/parent counters and
versions advanced exactly once; request/admission/append/history linkage and all earlier receipts
and history remained correct. No session load, compaction, renewal, full GC or storage failure occurred.

| Metric | Sample1 ms | Sample2 ms |
| --- | ---: | ---: |
| HTTP submission | 1,790.259 | 927.418 |
| Child observed | 6,214.684 | 3,765.320 |
| Parent observed | 6,475.893 | 4,008.531 |
| Engine execution | 2,394.560 | 1,174.282 |
| Processing through commit | 3,335.709 | 2,096.123 |
| Signed entry through commit | 4,221.208 | 2,465.937 |
| GRAPH_SNAPSHOT | 437.333 | 640.612 |
| GRAPH_ADMISSION (two scopes) | 374.814 | 224.679 |
| GRAPH_ADDRESSES | 37.459 | 20.110 |
| GRAPH_SUBSCRIPTIONS | 37.524 | 20.926 |
| GRAPH_COMMIT | 51.526 | 41.703 |

Gas remained1,202,935 each. Snapshot time was lower than the previous three-call cumulative mean of
1,951.799ms. This is a small, unmatched warm-up comparison: the earlier run warmed with an actual
cold increment, while this run warmed with a read. End-to-end improvement is **not established**.
Process-interval storage counters include background/observation reads and inclusive nested scopes;
they must not be summed into exclusive operation time. Child/parent observations were sequential.
The benchmark chat released the AWS window and preserved the warm process. No100,000-member claim.

## Iteration 86 — publication-bound operation descriptions

**Decision: KEEP locally; full qualification passed below.** Operation discovery previously restored the full
execution graph and then filtered its reception to one member. The candidate publishes an immutable
member catalogue alongside HEAD and history, using the already completed canonical transaction.
Each entry retains exact operation names, channels, request declarations, descriptions, native header
identities, selectors, observed flag, generation and target identity. The console response format is
unchanged. The catalogue is a host read model, not Blue content, permission, admission evidence or a
portable execution proof; input preparation and execution still perform their existing checks.

Only changed member identities or receiver descriptions regenerate entries. Other immutable entries
and radix paths are reused. Full reception determines visibility, so detachment, terminated ancestors
and cycles do not become a selected-body shortcut. HEAD and history publish the catalogue root in the
same conditional commit as the canonical result. Failed candidates can leave unreachable immutable
pages but cannot expose their catalogue. Reads pin HEAD once, check member metadata and target, then
read the selected content blocks. They neither acquire an execution lease nor migrate old data.

Older publications lack this optional field and retain strict inspection until the next successful
publication. No prior snapshot/history/receipt is rewritten. Descriptions larger than the existing
16MiB block bound defer to ordinary inspection instead of rejecting an otherwise valid document.
Stored descriptions describe their historical publication; they do not assert that another host's
current runtime can execute it. Cold execution restoration remains unchanged.

The library adds candidate `Transaction.graph()` and `Transaction.operations(member)`, mirroring the
existing session inspection API and preserving commit/discard isolation. `GRAPH_OPERATIONS` reports
host projection preparation separately. Focused tests cover exact native responses without content
or topology reads, child-owned amendments, detachment/termination, legacy fallback and next-publication
migration, failed commit isolation, foreign-account denial, immutable history, channel generations,
large chunked descriptions and selected regeneration among128 members. Full checks and bounded local
measurements remain pending; no iteration86 deployment or AWS improvement is claimed.


### Iteration 86 local qualification

**Decision: KEEP locally.** Full library formatting/check/Javadoc passed:2,543 tests, no failures,
errors or skips,22s. Full host formatting/check/build passed:806 tests, no failures/errors/skips,
1m34s. Generated Core SDK journeys passed. All57 baseline runtime JAR hashes matched the deployed85
pins. The same qualification JAR then ran strictly sequentially against baseline/candidate runtime
JARs in separate1GiB JVMs, local MemoryStore. Initial accidentally overlapping attempts were discarded
and retained with `.contended.json` names; they do not contribute to the table below.

One read warmup/three measured fresh readers and two update warmups/three measured updates were used.
All response hashes and each canonical update identity/receipt matched between versions. These are
simple authored operations with no accumulated Timeline history, no HTTP and no AWS.

| Members | Previous read median ms | Catalogue read median ms | Catalogue preparation median ms | Previous update median ms | Candidate update median ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 17 | 13.809 | 0.816 | 0.968 | 19.404 | 22.134 |
| 129 | 25.710 | 0.168 | 1.958 | 116.320 | 126.643 |
| 1,025 | 189.057 | 0.107 | 9.681 | 4,582.755 | 4,575.173 |

Decreasing candidate read times reflect JIT/order effects; the index follows logarithmic paths, not
a mathematical constant-time promise. Publication still scans compact member/reception metadata,
while description regeneration and immutable writes are selective. Small updates became somewhat
more expensive; no update speedup is claimed. Engine time at1,025 members was4,507.397ms before and
4,491.475ms after. Subsequent investigation identified this fixture as a broadcast: unfiltered
external channels advance every member checkpoint, even with a root-addressed operation. It is not
evidence that changing only one member takes 4.5s. See the separated measurements below. Evidence/pins remain
outside source in `iteration86-discovery-{before,after,summary}.json` and
`iteration86-local-runtime-pins.json`. No iteration86 AWS deployment has occurred.


## Iteration 87 — distinguish broadcast work and defer unused snapshot serialization

**Decision: KEEP locally; qualification passed below.** The operation-discovery probe now labels delivery
as broadcast or filtered and counts changed member identities outside the measured update. Filtered
children receive only the `act` operation, so a root `increment` changes exactly one member. Broadcast
children deliberately accept every external event and all advance their checkpoints. Both cases are
useful regressions; neither is a substitute for a native Timeline deployment with accumulated history.

Sequential separate-JVM comparison of iteration 85 and 86, same probe JAR/1GiB/local MemoryStore,
confirmed identical description hashes, canonical identities, receipts and gas. One read warmup/three
measured reads and two update warmups/three measured updates per size; all filtered updates changed
exactly one member. No profiler ran during these measurements.

| Members | Previous read ms | Catalogue read ms | Previous engine ms | Catalogue-version engine ms | Catalogue preparation ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 17 | 13.910 | 0.881 | 5.847 | 6.085 | 0.174 |
| 129 | 28.209 | 0.235 | 11.254 | 14.974 | 0.235 |
| 1,025 | 214.123 | 0.223 | 69.813 | 69.680 | 0.755 |

At 1,025 members total local update was 76.931/77.274ms. This read optimization does not establish an
execution speedup. Routing and metadata traversal still grow with membership; gas also grows under
the current semantics. Evidence: ignored/local `iteration87-filtered-{before,after}.json`.

A separate local broadcast JFR profile sampled 1,818 stacks under `CanonicalGraphs.process`;
992 included logical-prefix commitment and 340 included snapshot copying (inclusive, overlapping).
Snapshot copying eagerly rebuilt full wire metadata even where no caller used it. The candidate
copies immutable cache references with their original validity markers and lets ordinary wire/identity
access validate them. It does not treat a stale cache as valid, change gas, bypass closure or omit any
required canonical byte. Tests cover cold/warm copies after header, member, channel and edge changes,
then independent mutation and exact commitment equality. Comparative qualification is pending.

### Separate AWS diagnostic on iteration 85

One bounded JFR session surrounded one fresh generated-SDK increment, with no restart, retry or
extra mutation. All 57 runtime hashes matched, no previous record changed, child/root counts and
versions each advanced once, and request/admission/append/history linkage remained exact. The
recording stopped promptly; remote diagnostic artifacts were removed and the warm host retained.
Raw recordings and tenant selectors remain local to the benchmark chat.

Profiled times were submission 991.509ms, engine 2,170.421ms, processing through commit 2,818.306ms,
signed-entry through commit 3,293.505ms, root observed 4,199.292ms and child observed 5,203.413ms.
Observation polling explains the reversed observation order; it is not reversed causality. These
profiled numbers are not a speed comparison. Gas remained 1,202,935. GRAPH_SNAPSHOT was355.894ms,
ADMISSION219.414ms across two scopes, ADDRESSES23.028ms, SUBSCRIPTIONS33.406ms, COMMIT41.895ms;
LOAD/RESTORE were zero. No session load, compaction, renewal, failure or full GC occurred.

Only 64 execution-thread CPU samples were captured; no leaf dominated. Inclusive categories included
BEX execution23, work accounting20, channel selection7 and matching8. Of56 engine-boundary stacks,
21 were in begin-work and35 in finish. This differs from the local broadcast profile and does not
justify claiming that the AWS selected update is dominated by the same snapshot-copy cost.


### Iteration 87 local qualification — KEEP

Full library formatting/check/Javadoc passed: **2,573 tests**, no failures/errors/skips,22s. Full
host formatting/check/build passed: **806 tests**, no failures/errors/skips,1m34s. Thirty added
parameterized cases exercise pending cache invalidation before a copy, including previously encoded
and never-encoded snapshots. Changes are confined to physical reuse, not a new semantic edition.

The same compiled probe ran sequentially against pinned iteration86 and87 JARs, separate1GiB JVMs,
17/129/1,025 members, two warmups and three measured updates per case. All30 canonical snapshot,
receipt, gas and changed-member tuples matched, as did all operation descriptions. No profiler ran.

| Delivery | Members | Previous engine median ms | Candidate engine median ms | Previous total update ms | Candidate total update ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Filtered | 17 | 5.599 | 5.704 | 7.491 | 8.272 |
| Filtered | 129 | 14.182 | 13.206 | 16.940 | 16.129 |
| Filtered | 1,025 | 68.435 | 70.804 | 75.340 | 78.259 |
| Broadcast | 17 | 16.982 | 16.497 | 21.143 | 21.429 |
| Broadcast | 129 | 110.770 | 92.451 | 121.813 | 103.058 |
| Broadcast | 1,025 | 4,484.818 | 3,567.626 | 4,558.590 | 3,652.791 |

The largest broadcast's engine time decreased20.5%; filtered updates show no clear improvement.
This is a small bounded component comparison, not an AWS/production throughput claim. The remaining
broadcast cost includes canonical commitments over growing logical state, and single-member routing
still examines graph metadata. The optimization is retained because it removes proven unused work,
preserves exact outputs and improves the workload where that work repeats. Evidence and runtime/tool
hashes remain outside source in `iteration87-copy-*.json`. AWS deployment/acceptance follows separately.


### Iterations 86–87 deployment

All generated Core SDK journeys passed after the full suites. Promoted source hashes matched staging;
all57 target runtime JAR hashes and the qualification tool matched the tested artifacts exactly.
Archive `5387092ab6eb63d5b3112e2de42ed02a754f2fe723d0b2cd28f8e1e1fbb436a2` deployed successfully
through SSM `33f16a46-8252-4405-879b-dfeaba230ee0`,2026-11-07 08:29:22–08:29:45UTC (23.284s).
The previous owner stopped before startup. Native runtime/console health passed, with workers, heap,
cache, data and capability settings preserved. No GitHub or original-repository changes were made.

The benchmark chat now owns a bounded acceptance window: at most one fresh cold increment to create
the optional catalogue on this old publication, selected operation reads, then two fresh warm increments.
Cold restoration and initial projection migration must be reported separately, with no replay of prior
requests or concurrent root AWS activity. AWS results remain pending; local gains are not substituted.


## Iteration 88 — preserve integers during numeric work

**Decision: REVERT production change; retain tests and diagnostic benchmark.** Profiling found integer ordering
passing through decimal conversion. The candidate made `BexValues.integer` directly retain `BigInteger` and convert
integral Java wrappers with `BigInteger.valueOf`. Decimal/floating conversion, exactness checks and
canonical text grammar stay unchanged. Contract integer comparisons charge the same scalar/limb work
before comparing the exact integers directly. Byte/Short retain their prior comparison tariff.

The full library suite/Javadoc passed:2,589 tests, zero failures/errors/skips,20s. New focused tests
compare arbitrary precision, signed boundaries and mixed representations to the independent decimal
route, including identical charge traces and first rejection for shared/local limits with tracing
on/off. The bounded measurements and decision follow below. The deployed runtime remains87.


### Iterations 86–87 AWS acceptance — catalogue passed, execution target still unmet

The benchmark verified native health and all57 deployed runtime hashes, then ran exactly three fresh
SDK increments and two operation-list reads, each once. No old request replay, profiler, restart,
additional host, setting change or extra mutation occurred. All previous history and terminal receipts
remained unchanged; the final guarded set is30 records. Each increment advanced child/root counts and
graph version once, with exact request/admission/append/history and stable-input linkage. All storage
failure/retryable-failure counters and full-GC deltas were zero. The same process remains healthy/warm,
with one loaded graph session and about2.42GB process memory. AWS ownership was explicitly released.

| Metric | Cold/migration ms | Warm1 ms | Warm2 ms |
| --- | ---: | ---: | ---: |
| Submission | 1,515.691 | 1,086.726 | 903.748 |
| Child observed | 35,447.050 | 3,798.375 | 3,583.027 |
| Root observed | 35,699.356 | 4,081.726 | 2,606.794 |
| Engine execution | 2,495.926 | 997.332 | 836.178 |
| Admission through commit | 32,834.739 | 1,818.286 | 1,341.533 |
| Signed entry through commit | 33,503.138 | 2,146.145 | 1,674.404 |
| GRAPH_LOAD | 13,403.218 | 0 | 0 |
| GRAPH_RESTORE | 15,467.706 | 0 | 0 |
| GRAPH_ADMISSION (two calls) | 417.697 | 202.899 | 182.580 |
| GRAPH_SNAPSHOT | 431.450 | 558.227 | 222.586 |
| GRAPH_OPERATIONS | 492.190 | 31.769 | 26.209 |
| GRAPH_ADDRESSES | 24.571 | 22.377 | 24.137 |
| GRAPH_SUBSCRIPTIONS | 29.442 | 22.585 | 42.878 |
| GRAPH_COMMIT | 53.954 | 46.544 | 39.271 |

Gas remained1,202,935 for all three. Every GRAPH phase other than ADMISSION had one call where nonzero.
Warm session loads/entries, compactions, failures and history renewals were unchanged, with three cache
hits each. Sequential polling explains Warm2's root observation before child; it does not describe
propagation order. Inclusive phase/store times must not be summed as exclusive work. Two warm samples
with different warm-up history do not establish a controlled end-to-end improvement or flat scaling.

The catalogue root was absent before the cold increment and present in committed HEAD afterward.
Two generated-SDK operation-list GETs returned HTTP200 in **705.715ms** and **647.565ms**, with exactly
the previously verified operation/channel/route shape. All durable guards and the selected exact head
were unchanged. Both reads had zero LOAD/RESTORE, zero graph-session activity including hits, and zero
storage commits/content publications. The public SDK list response does not echo target identity:
target binding is supported by the unchanged exact head plus the implementation's exact member check,
not an independently echoed target field. These reads followed initialization; no cold-process HTTP
measurement was performed. The local cold-reader tests separately deny execution content/topology
reads and verify the selected catalogue response. The unchanged cold execution restore remains a
major unresolved cost. No iteration88 runtime has been deployed.


### Iteration 88 result — revert the runtime experiment

The candidate passed all806 host tests (1m33s), in addition to the2,589 library tests above. The same
compiled eight-write/session-renewal probe then ran sequentially against pinned87/candidate JARs,
separate1GiB JVMs,17/257/1,025 members,12 operations per size with the first four excluded. All36
input/receipt/gas/snapshot tuples matched. Total-update medians were9.193→9.049ms,29.410→27.679ms
and81.858→80.610ms. These modest differences do not establish a material improvement in a bounded run.

JMH1.37/JDK25 used two forks, two500ms warmups and three500ms measurements, one thread, no other local
CPU qualification. The fork used normal JVM ergonomics rather than an explicitly fixed heap. The
reference decimal round trip already allocated **0B/op for BigInteger**, as did the candidate; integral
wrappers and decimal input allocated64B/op in both. Timings in nanoseconds/op were:

| Representation | Decimal route | Candidate integer route | Bytes/op, either route |
| --- | ---: | ---: | ---: |
| Integer | 2.817 | 2.839 | 64 |
| Long | 2.655 | 2.894 | 64 |
| BigInteger | 0.689 | 0.495 | 0 |
| Decimal | 3.438 | 3.306 | 64 |

JIT escape analysis already removes the suspected intermediate allocation in this isolated case.
These nanosecond measurements cannot establish a faster BEX program or AWS operation. The runtime
changes to `BexValues` and `ContractWork` were therefore restored byte-for-byte from87. The organized
numeric behavior tests and explicit benchmark are retained for future investigations. No AWS restart
or deployment is required;87 stays accepted and warm. Evidence is outside source in
`iteration88-numeric-jmh.json`, `iteration88-renewal-{before,after,summary,pins}.json`. Post-revert focused
verification and final artifact comparison follow; no unqualified runtime optimization is retained.


Post-revert verification passed all16 retained numeric cases, zero failures/errors/skips, plus
formatting/benchmark compilation. The final staging distribution's57 runtime JARs and qualification
JAR exactly match the already accepted/deployed iteration87 hashes. The full baseline suites remain
applicable to that unchanged runtime; no further CPU or AWS rerun is needed for test/documentation
additions. Final source promotion contains only the numeric tests/benchmark and documentation.
