# Account-owned Timeline publication

Status: release120 is deployed with private account-owned creation, append, browsing and Sharing controls, plus native
signed readers, executable Timeline Access and the reserved control source described below. Local
suites, isolated DynamoDB qualification and live CloudFront/browser grant/read/revoke checks pass.
Main continues to pin the provider key and endpoint. Console Timeline appends do not yet drive
business-document execution automatically; native receiver adoption/delivery remains separate work.

The selected Coordination identity is `GMzA1zqLaxpDhxefhcS3gWtPFQobAUB5rgWSj3jtvaJa`, the same publication
used by the Better verifier and current shared `blue-packages/blue-coordination` archive (checked in
iteration 112). The original MyOS has additional human/access authority integration; this component
does not claim that broader parity.

## Responsibility and API

`HostedTimelines` stores owner history in the existing conditional-store abstraction. There is no
SQLite database or global publication journal. Different timelines have independent conditional
heads; a transaction publishes one head, one occurrence, its lookup index and its command receipt.

```java
// Deployment supplies its pinned provider document and protected Ed25519 private key.
ProviderSigning signing = new ProviderSigning(coordination, provider, privateKey);
HostedTimelines timelines = new HostedTimelines(store, namespace, signing, unixMicros);

// The owner HTTP adapter takes this account from verified login, never request JSON.
HostedTimelines.Created created = timelines.create(account, "create-1", "Payments");
HostedTimelines.Published accepted = timelines.append(
    account, created.id(), "append-1", language.readYaml("amount: 42"));
HostedTimelines.Checkpoint checkpoint = timelines.seal(account, created.id(), null);
```

These are host-internal classes, not new public Blue library types. The library continues to own exact
Blue content, schema/ancestry, signature verification and replay. JCA owns Ed25519 signing. The signing
adapter checks that its private key matches the pinned provider document before durable publication.
The runtime never generates a replacement key. An explicit provisioning tool generates one protected
key file, uploads it to the retained secret, and removes the temporary directory. Startup loads the
pinned version once using the instance role; per-request publication uses the in-memory signer.

Creation is private. The display name is owner metadata and does not become Blue's `name` metadata or
a type alias. Distinct owner/creation-command scopes receive distinct canonical resource URLs; retry
returns the original exact timeline. The provider and Coordination package are pinned durably per
host namespace. Reusing that namespace with a different key/endpoint/package is refused.

Every owner lookup is account-scoped, including list, append, entry and checkpoint. A known foreign
BlueId does not grant access. The current actor profile is a Principal Actor with the trusted account
identifier; delegated actors, organizations and full MyOS human authority are separate remaining work.
These owner methods do not authorize signed reader requests or native business execution. The separate
access service derives reader permissions from published, actually executed Timeline Access documents.

## Publication and clocks

The timestamp is the maximum of provider time, the exclusive completeness fence and the previous
entry timestamp plus one. Every append transaction fences the head from which it calculated that
choice. Concurrent JVMs cannot fork the chain or insert an entry below already published completeness.
The last safe timestamp is 9007199254740990; the exclusive final bound may be 9007199254740991.

An occurrence retains its exact message and actor, a reference to the immediate predecessor, and a
provider-object attestation. Recognized Operation Request descendants retain the same exact content
in both `message` and `operation`. Valid definition messages and referenced custom types are retained.
Message wire is limited to 256 KiB and one stored content envelope to 2 MiB. These are explicit host limits,
not new Blue semantics. Incomplete/malformed content cannot advance the history or consume a command.

Checkpoints sign the published completeness-guarantee domain. The proof, count and new clock fence
commit atomically. A requested future bound is refused instead of fabricated. A retained checkpoint
can satisfy a minimum bound only if it still covers every accepted occurrence. A new append forces
fresh coverage. Clock rollback cannot invalidate an earlier promise.

A lost transaction response resolves the durable receipt or exact checkpoint. If a peer won the same
command with a different timestamp, recovery returns the peer's occurrence, not the losing attempt's
prepared result. A successful local append can return its already owned immutable result without
reloading its just-written content. Conditional conflicts select fresh state and retry within bounds.

## Locality and restoration

Appending reads the selected timeline's metadata, content-index paths and current publication; sealing
reads its selected head. Neither loads or hashes earlier entry bodies. Immutable content is split through the existing bounded blob store;
only records for the new message, actor and attestation are retained in the publication envelope.
Shared package definitions and the already-retained predecessor are not copied again.

Unit coverage makes every earlier entry envelope and indexed entry body unavailable, retains the
lookup-index pages, constructs a fresh publisher and proves that append and checkpoint still succeed. Two metadata pages (50 and 14 entries) also work without those
bodies. Reading one of those old entries correctly fails. This checks
physical dependency locality, rather than inferring it from a small wall-clock sample. Full historical
verification remains proportional to the history requested; it is a separate read/replay operation.

The separate `HostedTimelineProbe` measures eight alternating append/checkpoint samples on histories
starting at 1 and 128 entries, after eight warmups. On DynamoDB it exits the populating JVM and starts a
fresh JVM with the same ephemeral qualification key. It verifies original creation/append/checkpoint
receipts, all 136 retained entries, a new append after the stored completeness fence, and cross-account
refusal. Temporary key material stays in a private directory and is removed afterward. Results are
provider publication measurements, not HTTP, Contracts, parent propagation or an HA benchmark.

## Checkpoint-bound reads (internal component)

Iteration 112 adds `HostedTimelineReads`. The public library's immutable `TimelineRequests` parser
shares the consumer's request rules: exact selected provider/timeline, inherited values, bounded
limits, mutually exclusive selectors, cursor/checkpoint binding and checkpoint-only reads. Parsing
does not authenticate a reader or certify an anchor. The host recognizes checkpoint and entry IDs
inside the selected account/timeline's durable rows. An arbitrary known BlueId cannot select another
source or skip missing history.

```java
HostedTimelineReads reads = new HostedTimelineReads(timelines, signing, 256, 1024 * 1024);
LanguageContent request = language.prepare(Language.Input.yaml("""
    type: Coordination/Read Timeline Request
    timeline: {blueId: '%s'}
    limit: 50
    """.formatted(created.id()));
LanguageContent signedResponse = reads.read(account, created.id(), request);
```

This remains an internal assembly API. Iteration 113 wraps it with `HostedTimelineReader`, which verifies
the optional signature and checks current executable grants on every request, including continuation.
The ordinary owner console keeps its existing metadata and entry routes. Calling the assembler alone
does not grant reader access.

A fresh read atomically seals a checkpoint. A cursor selects the exact retained checkpoint even after
new entries arrive or the publisher restarts. Entry seeking uses an exact membership index; timestamp
seeking binary-searches immutable position metadata. The response loads only the immediate predecessor
and the selected page, bounded by both count and bytes. New append rows include timestamp metadata;
older rows fall back to their original entry bodies when needed for a timestamp seek. Full history
enumeration still costs in proportion to that history. A small recent entry-index read does not.

The response contains the original entries, checkpoint and a provider signature. Entry fetch returns
the original reusable acceptance attestation as well. Checkpoint-only reads make a signed claim;
consumer verification still requires actual chain evidence before publishing completeness. An empty
positive-limit read has an explicit page. An oversized single entry is refused, never represented as
an empty page that claims progress. The host byte bound is 1 KiB–4 MiB and the page bound is 1–256.
For normal fitting pages the response is signed once; a size-limited page uses bounded binary search
to find a fitting prefix. No signature, request, entry identity or package semantics changed.

## Console and deployment

Open **Timelines → Create timeline**, give it a display name, and choose **Append message**.
YAML and JSON are accepted. Select an entry to inspect its original message, actor, timestamp,
predecessor and provider attestation. The definition tab displays the private Signed Timeline.
Listing is bounded to 50 metadata records; selecting one entry loads only that entry's content.
**Open latest entry** avoids paging through a long history. The browser retains unresolved append
commands and drafts across refreshes. Known success permits an intentional new occurrence.

Authenticated routes derive the account from verified Cognito identity:

- `GET/POST /api/timelines`: page owned Timelines or create with `{name}`.
- `GET /api/timelines/{blueId}`: descriptor, count and public provider identity.
- `GET/POST /api/timelines/{blueId}/entries`: page metadata or append `{source}` (YAML/JSON).
- `GET /api/timelines/{blueId}/entries/{position}`: original entry and its attestation.
- `GET /api/provider/timelines/{resource}`: owner-authenticated descriptor at its canonical URL.

POST requires the console Origin, JSON and an `Idempotency-Key`. A foreign resource returns 404 before
message parsing. `account`, actor, timestamp and access flags cannot be overridden in request JSON.
All new resources have `publicRead: false`. Invalid content returns 422; resource exhaustion returns
413; command reuse with changed input returns 409. Original requests remain safely retryable after
uncertain failures. This owner RPC response envelope is not the signed native reader protocol.

`infra/hosted-provider.json` stores only public pins: secret ARN/version, provider BlueId, endpoint and
namespace. The key is absent from source, artifacts, systemd environment and reports. The dedicated
`myos-better-provider-20261106` stack retains the secret and grants the existing Better role only
GetSecretValue on that secret. The original MyOS resources are not used for key custody.
[Secrets Manager Java retrieval](https://docs.aws.amazon.com/secretsmanager/latest/userguide/retrieving-secrets-java-sdk.html)
and [secret CloudFormation resource](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-secretsmanager-secret.html)
describe the underlying AWS interfaces.

For a fresh isolated setup, build the distribution and qualification tool, then explicitly run
`scripts/provision_provider.py`. Its first call creates the empty secret/policy stack; rerun after
CREATE_COMPLETE to provision and pin the key. Once a version exists, the tool refuses replacement.
An interrupted put can recover its prepared public version receipt. Never delete or rotate this
identity as routine maintenance: a changed provider key or endpoint changes its BlueId and requires
an explicit migration. Normal deploys load the saved public pins. Deploying a checkout without those
pins cannot silently disable an already configured provider.

Main requires all three of `MYOS_PROVIDER_SECRET_ARN`, `MYOS_PROVIDER_SECRET_VERSION` and
`MYOS_PROVIDER_ID`, or none. The expected endpoint is `CONSOLE_ORIGIN + /api/provider`. Missing secrets,
wrong versions, mismatched endpoints/keys and durable namespace rebinding all fail closed. There is
no private file fallback or auto-generation. The published Coordination package is bundled with the
service and checked against its exact identity. Source parsing has independent bounded host limits.

## Native provider protocol (release 113)

The provider API base is the pinned `Timeline Provider.endpoint`. In the Better deployment that is
`https://d2ya9daeiur7nz.cloudfront.net/api/provider`. The following routes are deployed and verified through CloudFront in release 113:

| Method and suffix | Authority | Result |
| --- | --- | --- |
| `GET /provider` | Public | Signed provider descriptor with inline provider and public control Timeline |
| `POST /timelines/read` | Public source or signed current reader | Signed checkpoint/page, bounded to 128 entries and 2 MiB |
| `POST /timelines/entry` | Public source or signed current reader | Original occurrence and acceptance |
| `POST /timelines/content` | Public source or signed current reader | Exact content reachable within the selected source |
| `POST /access/create` | Authenticated owner of the listed Timelines | Original publication and actual initialized access document |
| `POST /access/update` | Owner of the access and removed/added Timelines | Actual replacement result and original update occurrence |
| `POST /access/get` | Public; supplied signatures still checked | Initial/current documents and publication/latest-entry attestations |

Every POST uses Blue JSON with exact package type identities. Responses correlate to the exact inner
request, not its signed envelope. Read requests need no Cognito login, Origin header or nonce. Owner
mutations keep the existing verified console login and Origin check, and use the published body's
`idempotencyKey`. Every successful response is provider-attested; errors are typed Provider Failures,
never evidence of an empty history. Optional owner-ingest/stream operation types are not advertised.
Existing `GET /api/provider` is still owner-console metadata; native discovery is
`GET /api/provider/provider`.

```java
// Host internal API: the verified owner is supplied by authentication, not document JSON.
HostedAccess access = timelines.access();
HostedAccess.Access grant = access.create(account, "grant-1",
    new HostedAccess.Permissions(List.of(created.id()), List.of(readerPublicKey)));

// Replacement executes the published BEX/Contracts operation; both lists are supplied.
HostedAccess.Access revoked = access.update(account, "revoke-1", grant.initial().blueId(),
    new HostedAccess.Permissions(List.of(created.id()), List.of()));
```

The provider initializes the original Timeline Access and publishes it as an Agent Actor message.
Updates target that original BlueId through an ordinary `provider/update` Operation Request. The
exact original entry, executed Contracts snapshot, actual after-document, membership index and owner
command receipt commit together. An uncertain reply recovers the original result. A competing update
reselects the winning state. Permissions are the union of current executed access documents; an event
or a separate guessed permission record is never authority. Revocation applies to saved signed
requests and cursors on their next use. The control source remains public.

One provider's control Timeline binds to exactly one namespace in a store. Identical initial documents
have one original publication. The first authenticated owner retains management of that identity,
including an initially empty document; another account cannot claim it. Host bounds are 32 keys and
32 Timelines per access and 64 overlapping access documents per Timeline. These bounds are explicit
provider limits, not new package semantics.

Content indexing publishes a source-specific immutable root with the accepted head. A known global
blob or speculative failed write is not source membership. The public control closure includes access
definitions, arguments and current/initial documents; naming a private Timeline exposes its descriptor,
not its private entry bodies. Protected Contracts snapshot envelopes are not public source values.
New owner appends keep the content index complete. Existing histories from release 111 require an
explicit bounded migration before native content lookup:

```java
// Operator-selected owner/source; one invocation handles at most 16 original entries.
long indexedThrough = timelines.indexContent(account, created.id(), 16);
```

Repeat bounded batches until the returned position reaches the current head. Concurrent appends do
not corrupt the index or rewrite old occurrences. Until complete, native content lookup reports
unavailability rather than a false missing/empty closure. This migration is not part of every read.

Full local qualification passes 283 host tests and 2318 library tests, with strict formatting and
Javadocs. This release executes the same published BEX and preserves exact document identities,
receipts, gas and events against independent consumer replay.

### Protected publisher recovery

The initial candidate recursively reopened old control entries during cold access restoration and
then copied their dependencies while retaining the new state. Incremental snapshot serialization alone
did not solve that work. `TimelinePublisher` now carries a bounded, source-specific validation head.
The host commits its checkpoint and independently stored digest in the same transaction as the original
occurrence, control head, access state, memberships and command receipt. Each access document also keeps
its own last consumed input checkpoint: that can differ from the global control head when documents
interleave. A concurrent publication causes fresh selection, never acceptance of a stale head.

A publisher restores this validation fact only from its protected, committed storage. It is not a
`TimelineVerifier.Prefix`, an acceptance proof, a permission grant or a completeness certificate.
Remote consumers still verify signed chain evidence. A digest supplied by a remote request must never
be used as the trusted checkpoint identity. Current source/order/time/schema checks still execute.
Scoped recovery facts also stop the execution-state retention walk at previously retained entries;
they are revoked when the invocation ends and are absent from ordinary exported Language documents.

Tests make every earlier indexed control entry and acceptance envelope unavailable, then restart hosts
and update two interleaved access documents successfully. Historical entry reads still fail as expected.
Corrupt checkpoint commitments fail without changing permissions or publishing an occurrence. A full
portable export can still require older bodies; incremental export relies on the retained content index.
Older experimental access state without a protected checkpoint requires explicit validated migration;
release 111 never published native access documents, so this is not an upgrade requirement there.

In one bounded local run, `HostedAccessProbe` measured eight alternating in-memory samples after warmups,
starting at 1/32 control entries and constructing fresh host caches before every measured update:

| Operation | Initial candidate, short/long history | Protected recovery, short/long history |
| --- | --- | --- |
| Access update median | 16.172 / 19.006 ms | 18.870 / 18.090 ms |
| Content reads per update | 28–45 / 139–156 | 25–31 / 46–50 |
| Signed one-entry read median | 5.304 / 5.387 ms | 5.541 / 5.471 ms |

Index path lookup still depends on the content index's shape. The physical regression tests establish
that earlier entry bodies are not revisited; these small timing samples do not prove a flat latency
curve or production throughput. Reads have identical storage call counts in both histories. These are
local publisher/access measurements, excluding HTTP, business processing, parent propagation and
consumer history verification. AWS qualification is recorded separately before deployment.

The isolated DynamoDB qualification also passed (SSM `d621ab00-b160-4a5b-846e-c9fbea6caeca`).
On the existing c7i.large host, the short/long control histories measured update medians
181.580/243.343 ms (maxima 211.799/252.409 ms), and signed one-entry read medians 55.390/52.271 ms
(maxima 61.042/62.485 ms). Updates used 24–31/44–55 content reads. Larger content indexes still add
remote point reads; this remains an optimization opportunity, not a constant-latency claim. The
16.8-second bounded workload used a separate temporary table and left the console running. Its
private test provider keys existed only in memory. This evidence is for publisher/access behavior;
it does not replace the separate payment → order → agreement measurements.

## Remaining integration

1. Reduce remaining cold content-index round trips; the current AWS measurements above retain that cost.
2. Migrate pre-113 owner source content indexes explicitly before serving their historical native content.
3. Add richer actor authority and a console sharing workflow for the native access API.
4. Complete durable native source adoption and receiver delivery, preserving all required dependencies.
5. Match original MyOS bootstrapping and operation submission through those native paths.

The deployed console's document History/Events still describe document execution. The payment-flow
example uses host-links processing. Its runtime manifest continues to report native admission and
Timeline processing disabled; provider reads and access updates do not change those semantics.


Live release113 verification also restarts the JVM after a real Cognito grant/read/revoke sequence.
The signed current access response is byte-equivalent after canonical JSON ordering; the user's
512-document business graph retains identical version, totals and membership. Native delivery remains
disabled in the business runtime. Evidence is retained under
`reports/overnight-2026-11-05/native-provider-113/`, with manifest113 at its parent directory.


## Owner sharing controls (release120)

Open an owned Timeline's **Sharing** tab. **Grant read access** accepts an Ed25519 public key
(64 hexadecimal characters); the private key stays with the reader. This creates or reactivates
one exact Timeline Access document for that key and Timeline. The normal signed reader API then
allows current and future entries. It does not grant append rights or business-operation rights.
Grant/public-key metadata belongs to the public provider control history; message bodies remain private.

The page lists all grants currently including this Timeline, including multi-key/multi-Timeline
records created through the native API. Each card exposes the exact access document and all covered
Timelines. **Revoke grant** clears that entire grant, with a confirmation showing its scope. Other
grants may still authorize the same reader. Previously downloaded messages cannot be recalled.

The owner routes are `GET/POST /api/timelines/{id}/access` and
`POST /api/timelines/{id}/access/{grant}/revoke`. The verified login supplies the owner; POST retains
the existing Origin and idempotency-key checks. Revocation verifies membership before mutation.
The original native Create/Update/Get Timeline Access protocol remains unchanged.

A new console grant command can reactivate an existing revoked content-addressed grant. It performs
one actual canonical publication and atomically records the command. Replaying an older grant after
revocation cannot reactivate it; replaying an old revoke after a fresh grant cannot revoke it again.
This also holds after restart and a lost publication response. The membership inventory remains
bounded to64 grants per Timeline and batches its metadata reads. No parallel permission database is
introduced; access is still derived from the executed canonical documents.

If the native API has changed the same exact grant to different active permissions, the simple form
refuses to replace it. Inspect and manage that grant explicitly; granting one reader must not silently
remove other keys or change another Timeline's access. Empty grants can be reactivated normally.

Reader-key grants serve applications using the signed native reader API. Another console account
still has its own owner-scoped Timeline list; this is not a console-account invitation feature.
