# Durable source adoption

The opt-in `myos.timeline-directory/1` profile stores exact provider/Timeline bindings per account.
Operators can adopt a source after startup. A fresh host recovers its definitions and verified source
history without reading a provider or Timeline deployment file. This is the source-management
foundation for console-native execution; the ordinary hosted console still uses host-links operations.

Source adoption is an operator action. These routes exist only on the loopback operator API, intended
for an authenticated SSM tunnel. They are not exposed by the public console router. An incoming event,
URL, signature or claimed account never implicitly adopts a source. Console owner-authorized adoption remains separate work. Automatic acquisition is opt-in and uses
the same signed verification, durable source archives and receiver scheduling as static sources.

## Select the profile

Keep the verified Coordination publication and its definition mounts from
[the service configuration](TIMELINE-SERVICE.md). Select this `timelines` section:

```json
{
  "coordination": "GMzA1zqLaxpDhxefhcS3gWtPFQobAUB5rgWSj3jtvaJa",
  "namespace": "my-application",
  "directory": "myos.timeline-directory/1",
  "sources": []
}
```

`sources` may contain zero to64 bootstrap declarations in the existing file format. They are adopted
into the same durable directory. Runtime adoption is not limited to64 sources or64 accounts. The
namespace, Coordination identity and optional historical private-access profile remain immutable
within an account's stored configuration. Existing static configurations cannot silently switch to
this profile, or back. Use a new account/storage scope or an explicitly designed migration.

Omitting `automatic` keeps explicit acquisition and stepping. Add this option to enable background
polling of registered sources:

```json
"automatic": {"pollMillis": 1000, "workers": 2}
```

A newly adopted public source is registered for polling in the same transaction as its immutable
source binding. For a public source adopted before automatic polling was enabled, repeat the same
adoption (or call `/timelines/polling` with its account and alias). This repairs the registration
without rebinding source definitions or resetting acquisition progress. A fresh worker discovers the
registry with no startup source list. Removing a bootstrap declaration does not retire a source;
adoption and polling registrations are persistent. Deletion, rebinding, credential rotation and
cross-version migration require an explicit lifecycle design and are not supplied here.

Private sources stay at `READER_REQUIRED` until the operator explicitly registers custody. No private
signing key is accepted in source adoption, a document, or a public console request. Bootstrap
`reader` objects remain limited to the static configuration profile; directory custody uses the
separate operation below.

## Adopt and inspect

```json
POST /timelines/adopt
{
  "account": "example",
  "source": "payments",
  "provider": {"blueId": "<expected provider BlueId>", "json": "<exact provider JSON>"},
  "timeline": {"blueId": "<expected Timeline BlueId>", "json": "<exact Timeline JSON>"},
  "records": {}
}
```

Both definitions must pass canonical admission and match their supplied BlueIds. The optional
`records` object maps content BlueIds to portable `LanguageRecord` JSON, including owning-set proofs
when needed. Supplied records are evidence to validate, not authority. Only the required dependency
closure is retained. Collapsed custom metadata therefore survives restart. No private signing key is
accepted or stored here.

Each definition is bounded to256 KiB. The complete stored envelope is bounded to2 MiB and4096 portable
records; the existing HTTP request bound also applies. Definitions use hashed64 KiB immutable storage
pages. One conditional transaction publishes the account profile, alias, Timeline index and provider/
URL index. Lost responses are resolved against that complete publication. Retries with identical pins
are idempotent. Conflicting aliases, duplicate Timeline bindings or ambiguous provider/URL bindings
fail; they cannot replace an established source.

```json
POST /timelines/sources
{"account": "example", "limit": 50}

POST /timelines/sources
{"account": "example", "limit": 50, "cursor": "<returned nextCursor>"}

POST /timelines/head
{"account": "example", "source": "payments"}
```

Source pages contain the alias and pinned identities/URL. `nextCursor` is null at the end. Ordinary
head, signed-evidence acquisition and native stepping use the same aliases and routes documented in
[Timeline service](TIMELINE-SERVICE.md). They do not accept caller-selected subsets of required sources.

## Private sources and execution

The historical [private-access policy](TIMELINE-PRIVATE-ACCESS.md) remains separately opt-in. Submit the
provider's original signed descriptor and independently adopt its exact public control Timeline:

```json
POST /timelines/discover
{
  "account": "example",
  "source": "payments",
  "descriptor": "<exact signed provider descriptor JSON>"
}
```

Discovery verifies the signature and binds the reserved control identity. It neither advances source
history nor grants access. The descriptor is bounded to256 KiB. A different control identity is refused
even when signed; replacement requires an explicit migration. This route also supports manually
configured static sources.

Before a directory-backed private receiver can be published, its signed discovery and exact public
control adoption must exist. This lets the host subscribe directly to that control source without
enumerating every public Timeline at the provider. The consuming document must still declare its
required reader keys and exact initial grants. Actual control history must close the selected original
input, and canonical access replay must establish permission at that input's full ordering key.
Later revocation does not erase earlier permission; later restoration does not retroactively authorize
an input made during revocation. A current transport grant is insufficient.

Adopted source definitions are not added globally to every account's Language resolver. Authored
documents must supply needed content through their own inline/portable definitions or selected package
context. The qualification example inlines its providers in the receiving declarations. This keeps
source adoption separate from document content and business authority.

## Register private polling custody

The loopback operator API accepts a reference to an exact protected secret version, never key bytes:

```json
POST /timelines/polling
{
  "account": "example",
  "source": "payments",
  "reader": {
    "publicKey": "<64 lowercase hex characters>",
    "secretArn": "arn:aws:secretsmanager:eu-west-1:123456789012:secret:reader-Example",
    "secretVersion": "<exact immutable version ID>"
  }
}
```

The host role must have access to that exact secret. The secret uses `myos.reader-identity/1` and
pins the consuming account, Coordination publication, provider, Timeline and public key, alongside
the PKCS8 private key. These fields and key correspondence are validated before registration; they
are checked again when a worker loads custody after restart or cache eviction. Only the public key,
secret ARN and immutable version reference enter the polling record. Repeating identical custody
is idempotent; changing a key or reference cannot silently replace it. Do not broaden the host IAM
policy to arbitrary secrets. This operator route is not exposed by the authenticated console router.

Possession of the key does not grant Timeline access. The provider must independently authorize the
reader, and each signed read checks current transport access. Historical business admission still
requires the consuming document's declared policy, initial grants and signed control history.
Revocation stops private acquisition; the independently registered public control source continues.
A source read under a different account cannot reuse this custody.

`POST /timelines/status` returns current process polling status or `READER_REQUIRED`/`NOT_REGISTERED`.
A failed acquisition records durable backoff (1 second initially, doubling to at most60 seconds);
retry state survives restart and cache eviction. Only an exception class is recorded, not the error
message, request, credential or response body. A current successful retry clears that failure;
a stale success cannot clear a newer failure from another worker. The cleared record keeps its
monotonic revision, preventing an old attempt from matching a later failure after recovery.

Automatic polling retains at most64 queued/running source jobs,64 cached pull sessions and1–8
acquisition threads. Directory archives separately retain at most64 entries. It pages four index
shards scoped to the immutable runtime binding, taking one page per shard per pass. A continuously
growing shard cannot starve the others. Completed shard sweeps wait at least `pollMillis` before
starting again; actual source refresh latency also depends on queue capacity and provider response
time. Incomplete source-history chunks can rejoin the bounded queue immediately. There is no timer
or executor task retained for every adopted source, and steady successful polls write no scheduling
record. Unchanged signed responses create no new acquisition history or business versions.

Multiple hosts may acquire the same source. Durable conditional publication prevents duplicated
business effects, but polling does not yet have distributed leases or provider-wide concurrency
quotas. The cache bounds count entries, not historical bytes. Large-source replay, retirement,
credential rotation and broad throughput qualification remain work; this is not a latency SLA.

## Cost, recovery and tests

Selection occurs under the document's ordinary inspection lock, using its frozen receiving view.
Each receiving declaration looks up its exact Timeline index or provider/URL index. Unrelated account
sources are never enumerated. A warm single-source selection uses three strongly consistent metadata
reads: account configuration, source index and alias. The test retains the same count after adding128
unrelated sources, with no query or content read. Parent documents without native channels do no
directory lookup.

The optional archive cache retains at most64 sources and performs loading outside its metadata lock.
Eviction discards physical reuse only: a fresh archive verifies its retained signed history. Archive
phase counters survive eviction. This entry bound does not bound an individual source's retained
verified history by bytes; cold replay still grows with source history. Definitions and acquisition
history do not yet have garbage collection.

`TimelineDirectoryTest` owns atomic adoption/retry/conflict, tenant isolation, corrupt/missing stored
content, portable dependency closure, cache eviction and source-selection locality.
`TimelineDirectoryHttpTest` owns operator routes, strict configuration, signed discovery, quiet-source
closure and native child/parent execution after restart without source files. The historical private
admission tests run against both static and durable source profiles.

`TimelineServiceProbe ... directory` runs a real Main subprocess through HTTP with zero bootstrap
sources. It adopts exact sources, refuses foreign lookups and rebinding, rejects invalid signatures,
requires strict quiet-source completeness, executes original entries once and observes parent events.
With DynamoDB it starts a second Main process and verifies continued execution from the same stored
directory and history. Run the bounded AWS qualification with:

```bash
python3 scripts/run_native_admission.py --protocol timeline-directory --warmups 2 --samples 4
```

The launcher pins artifacts and takes the deployment lock. It temporarily stops the main service
while the native qualification's workers run, then restores the exact service profile through an EXIT
trap. Poll its returned SSM command to a terminal state. A polling timeout does not mean it stopped.
Synthetic qualification accounts are separate from the user's agreement. Timings separate acquisition,
ingress, runtime execution and propagation to the parent; they are not throughput guarantees.


## Scheduler ownership and upgrades

Source/receiver wakeups use the immutable account binding as their work-index scope. Separate
namespaces, Coordination editions and access profiles cannot consume each other's work. Source
adoption recovers legacy unscoped notifications using the exact source's paged subscriptions. Repeat
the same adoption after upgrading a pre123 directory; its binding and content remain unchanged.
Stop older workers first. Explicit acquisition and stepping remain required for this profile.
See [scheduler recovery](TIMELINE-SERVICE.md#automatic-acquisition-and-execution) for the boundaries.

Automatic directory qualification uses:

```bash
python3 scripts/run_native_admission.py --protocol automatic-directory --warmups 2 --samples 4
```

It starts the actual Main service with zero bootstrap source files, adopts sources through the
operator HTTP API, verifies HTTPS signatures and quiet-source closure, and lets background workers
update the child and parent. DynamoDB mode restarts Main and recovers an offline arrival. The normal
console is restored after the isolated qualification. This does not enable native business execution
in the ordinary console's host-links manifest. Private custody/revocation is covered locally; this
public-source probe does not claim an AWS private-reader-custody qualification.

Iteration 133 adds a separate AWS private-custody probe with a temporary Secrets Manager reader,
actual Main restart and automatic private/control workers. It verifies parent propagation, account
binding, revocation and refusal to execute an occurrence created during revoked access. The normal
console remains unchanged. See [the bounded workflow and evidence](PRIVATE-TIMELINE-CUSTODY-133.md).
