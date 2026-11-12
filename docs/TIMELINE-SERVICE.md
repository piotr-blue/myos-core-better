# Configure and process signed Timelines

The normal `Main` service can acquire signed source evidence and execute one ready original entry
through Contracts/BEX. Configuration selects trusted providers and timelines; HTTP callers select
an account-local alias. An optional automatic runner fetches public or explicitly authorized private provider pages over HTTPS and
schedules registered receivers from durable work records. This is an operator API bound to loopback,
intended for an authenticated SSM tunnel. Account
keys isolate stored data; they are not application authentication or authorization.

The opt-in [durable source directory](TIMELINE-DIRECTORY.md) adds runtime operator adoption,
indexed account-scoped selection and restart without source definition files. It supports explicit
acquisition/stepping or opt-in automatic polling, including protected reader custody registered by
the operator. The static source profile below remains supported.

## Configuration

Set `MYOS_RUNTIME` to a manifest alongside the verified Coordination archive and exact JSON provider/
timeline definitions. This template uses the supported published Coordination identity; replace the
source pins with the actual BlueIds of your adopted definitions. Do not put signing keys in these files.

```json
{
  "format": "myos.runtime/1",
  "creationEdition": "blue.document-local/3-preview",
  "editions": ["blue.document-local/3-preview"],
  "packages": [{
    "archive": "coordination.bluepkg",
    "blueId": "GMzA1zqLaxpDhxefhcS3gWtPFQobAUB5rgWSj3jtvaJa",
    "purpose": "definitions",
    "mounts": {"Coordination": [], "Contracts": ["Contracts"]}
  }],
  "timelines": {
    "coordination": "GMzA1zqLaxpDhxefhcS3gWtPFQobAUB5rgWSj3jtvaJa",
    "namespace": "example-timeline-host",
    "automatic": {"pollMillis": 1000, "workers": 2},
    "sources": [{
      "account": "example",
      "name": "orders",
      "provider": {"file": "provider.json", "blueId": "<provider BlueId>"},
      "timeline": {"file": "orders.json", "blueId": "<timeline BlueId>"}
    }]
  }
}
```

`purpose: definitions` is deliberate. Coordination declares the full native protocol, which this host
does not yet implement. The `timelines` section installs the exact supported channel profile and
Dispatch through the trusted verifier. Leaving purpose at its default `execution` still rejects the
unsupported full requirement. Type loading does not silently grant execution capabilities.

Source definitions must be strict UTF-8 exact JSON, at most 256 KiB each, and match their pinned
semantic identity. Files must stay inside the manifest directory, including symlink resolution.
The bootstrap registry accepts 1–64 sources across accounts; duplicate aliases or ambiguous bindings
within an account fail startup. This static bounded registry is not the final multi-tenant registry.

Namespace, Coordination identity, and each account/source alias are conditionally persisted before
HTTP starts. Reusing an alias for different provider/timeline identities or changing the namespace
fails startup. Adding new aliases/accounts is allowed. Removing a configured alias does not erase its
stored binding or source history. No migration utility is supplied. If `MYOS_ADMISSION_NAMESPACE` is
also set, it must equal the manifest namespace. All source and document operations share the same
store instance and admission namespace.

## Private reader custody

A private source may add the following `reader` object alongside its provider and timeline pins:

```json
"reader": {
  "publicKey": "<64 lowercase hexadecimal characters>",
  "secretArn": "arn:aws:secretsmanager:eu-west-1:123456789012:secret:reader-Example",
  "secretVersion": "<exact immutable version ID>"
}
```

The secret is loaded once at startup through the service's AWS identity. It must contain exactly
`format: myos.reader-identity/1`, `account`, `coordination`, `provider`, `timeline`, `publicKey` and
`privateKeyPkcs8` (base64 Ed25519 PKCS8). All identities must match the selected account and source.
The host checks the public/private key pair; malformed secret diagnostics omit parser causes and key
material. Grant the host IAM access only to the explicitly selected secrets. Inline private keys and
moving version aliases are rejected. Rotation of secret custody with the same key is allowed; changing
the bound reader key requires an explicit source migration and cannot silently reuse an existing alias.

Each read, entry or content request signs the published exact inner-request identity. The unsigned
inner request remains the evidence being verified. Signed credential envelopes are not archived and
cannot be used for owner mutations. Current provider permission is checked on every read: a revoked
source retries without progress, while an independently adopted public control source can continue.
The registry does not implicitly adopt that control source; configure its exact public definition too.

Reader configuration enables **private evidence acquisition**. Private receiver execution additionally
requires the explicit [historical-access policy](TIMELINE-PRIVATE-ACCESS.md), its document declaration
and independently adopted public control source; otherwise it returns `WAITING`. A current transport
grant is never substituted for historical authorization of an occurrence.

## Automatic acquisition and execution

With `automatic` configured, Main polls each adopted source, verifies discovery and signed
pagination, then publishes a complete range through the ordinary archive verifier. It sends no cookies
or credentials and follows no redirects. The timeout covers the response body. No archive lock spans
network I/O. Failed providers back off independently, up to 60 seconds; receiver execution uses separate
workers so a slow network request does not occupy the business-processing workers.

Each source reuses its verified provider descriptor for at most five minutes. Any failed attempt
discards that reuse, so a reduced provider page-size limit is rediscovered on the next attempt.
An expired descriptor must refresh successfully; there is no stale fallback. The cached reader
retains the adopted provider, control-timeline identity and page limit, never a prior read result or
permission decision. Every response still undergoes request, signature and history verification.
Private reader signatures are applied separately on every request. Descriptor reuse does not cache a grant.

`pollMillis` accepts 100–60000 and acquisition `workers` accepts 1–8. Omitting `automatic` retains the
explicit acquisition/step API below. Static private sources require a pinned reader key at startup;
directory private sources use the operator custody registration in the directory guide.

Each new or updated document atomically publishes its Timeline subscriptions with its checkpoint.
Acquisition atomically publishes a durable source notification. Fanout pages through subscribers,
and receiver wakeups remain discoverable after a crash or lost in-memory hint. Conditional idle
transitions cannot erase a newer arrival. Rejected business execution blocks that receiver for
inspection and explicit retry; it does not block unrelated receivers. Parent events use the existing
durable propagation path.

Work discovery is scoped by the complete immutable account binding: namespace, Coordination edition,
private-access policy and directory profile. A worker also needs every source named in a receiver's
subscription. An incomplete static worker leaves that job indexed for a capable worker; even an
incorrect local hint cannot consume it. A worker never scans another binding's queue. Established
source and receiver jobs retain their binding, so ordinary updates add no account lookup or scope
hashing per occurrence.

Upgrade from releases through122: stop older workers before starting this version. Static source
binding re-indexes each configured source and recovers its existing subscriptions through bounded
fanout pages. A crash or lost response leaves recovery indexed for the next process. Directory users
can repeat adoption with the same exact pins to recover that source. Failed receivers remain blocked
until an explicit retry. No document/source history or business effect is rewritten. Mixed old/new
workers and downgrading queue indexes require an explicit migration and are not supported.
Receivers from releases that never stored subscriptions still need subscription migration before
automatic mode; this release does not scan every stored document to invent those subscriptions.

```json
POST /timelines/status
{"account": "example", "source": "orders"}

POST /documents/timeline/status
{"account": "example", "document": "order-7"}

POST /documents/timeline/retry
{"account": "example", "document": "order-7"}
```

A later read can have a different range identity because its anchor moved to the previously closed
head. After full verification, unchanged completeness/head/successor facts reuse the existing committed
receipt: no additional history batch, source fanout or business version. Wider completeness, a new
closed occurrence or a newly authenticated successor remains real progress and is persisted.

One read attempt remains bounded to 30 seconds, 100000 entries/records and 16 MiB of wire and
retained content, with each HTTP response limited to 4 MiB. Large histories now commit chunks after
at most eight pages, or earlier to reserve resources. The host fetches and verifies the last entry's
reusable acceptance attestation, assembles ordinary `Timeline Successor Evidence`, and publishes it
through the existing archive verifier. The unread checkpoint is never marked complete. The last
entry remains pending; every nonterminal chunk closes at least one preceding original occurrence.

Missing exact content now triggers a source-scoped `/timelines/content` lookup. The consumer verifies
provider signature, inner-request correlation, inline content identity and transport headers before
retrying ordinary validation. A returned definition is not mistaken for an admitted value. Each
attempt permits at most 256 distinct content fetches, sharing the original byte, record and deadline
bounds. A repeated unresolved identity fails instead of looping. Wrong content, unavailable closure
and exhausted limits cannot publish a partial prefix. Existing retained closure avoids refetching.
This inline content-response path does not supply missing cyclic owning-set proofs; those still need
explicit portable `LanguageRecord` evidence. It does not fetch a new checkpoint to conceal a gap.

The runner reports `CATCHING_UP` and schedules another bounded attempt immediately. It reads after
the durable prefix head, including the pending successor again. A restart reconstructs that prefix
from ordinary retained evidence; no stored cursor or unchecked progress token grants authority.
The final chunk uses a complete range proof. This permits backlogs larger than one attempt without
raising its resource limits. A single oversized entry or an unavailable acceptance endpoint still
fails safely. Cold archive replay is still proportional to
its retained proof history, with bounded read-ahead, rather than constant-time restoration.

## Acquire evidence and execute

Submit exact signed range or authenticated-successor evidence as a JSON **string**. It remains the
original exact JSON document; the request must not parse and normalize that evidence on its behalf.
Optional `records` maps content BlueIds to portable `LanguageRecord` JSON objects. Supplying a record
does not authenticate it; signatures, history and resolved content still undergo verification.

```json
POST /timelines/acquire
{
  "account": "example",
  "source": "orders",
  "evidence": "<exact signed evidence JSON text>",
  "records": {}
}
```

The response contains `position`, `evidence`, `completeBefore`, newly acquired `entries`, and
`duplicate`. An identical acquisition returns its original receipt; verified equivalent coverage
returns the already committed receipt, whose evidence identity and entries may differ from the proposal.
Bad signatures, unavailable
referenced content and invalid history cannot advance the source head. The existing HTTP body bound
is 4 MiB; the explicit records map is additionally bounded to 100,000 entries.

```json
POST /timelines/head
{"account": "example", "source": "orders"}

POST /documents/timeline/step
{"account": "example", "document": "order-7"}
```

Head returns the pinned timeline/provider identities, acquisition position and completeness bound.
Step derives every required source from the document's actual receiving declarations. Callers cannot
supply a smaller required set, change provider keys or grant themselves native admission. The response
contains `status`, `reason` and, when execution was attempted, its `result`. Dispositions and exact
ordering rules are described in [Timeline ingress](TIMELINE-INGRESS.md).

For example, if an order listens to an active and a quiet source, acquiring an entry only from the
active source returns `WAITING`. Both sources must establish completeness strictly beyond its
timestamp. Once ready, the entry executes exactly once and the order's public event reaches its
agreement through the ordinary durable outbox. Calling step again returns `IDLE` when caught up.
A conditional publication conflict returns `RETRY`; the automatic worker immediately prepares a
fresh receiving view. For example, a parent acknowledging the preceding event can update the child's
propagation wake during execution. No source occurrence, command receipt or business result is
published by the losing transaction. Unexpected failures still retain indexed work for recovery.
A forged claim sent to `/documents/admit` does not bypass this path.

`GET /health` reports `timelineProcessing: true` and `nativeAdmission: true` when this manifest is
loaded. Without it the Timeline endpoints reject processing as unconfigured. Unknown account/alias
pairs return 404; invalid request fields return 400; semantic evidence errors return 422.
The account-local alias is a storage lookup, not a user authorization check.

`GET /metrics` also reports fixed-name Timeline phase counters: calls and inclusive nanoseconds for
discovery, HTTP reads, verification, acquisition, publication, fanout and receiver execution. These
operator diagnostics contain no source, account or document identifiers. Concurrent sources and
nested phases overlap; adding these totals does not reconstruct one operation's critical path.

## Qualification and remaining boundaries

`TimelineConfigurationTest` owns trusted startup, pins, file bounds, immutable bindings, conditional
conflicts and lost publication responses. `TimelineHttpTest` owns actual HTTP acquisition/execution,
strict quiet-source closure, missing referenced content, forged claims, parent events and restart.
Existing archive/ingress tests own signatures, ordering, atomic progress and original input receipts.
`TimelinePullTest` and `TimelineHttpTransportTest` own signed pagination, HTTPS and acquisition bounds;
`TimelineWorkTest` owns durable fanout, lost hints and publication races. `TimelineRunnerTest` owns
automatic execution, multi-chunk catchup, provider isolation, restart and competing-host behavior.

`AutomaticTimelineProbe` launches a real Main subprocess against a local signed HTTPS provider.
It performs no manual acquisition or receiver-step calls. It checks invalid signatures, multi-page
reads, strict quiet-source closure, unchanged-poll deduplication and child/parent effects. When
`MYOS_TABLE` selects an isolated DynamoDB table, it also checks a fresh Main process discovering an
arrival published while the host was stopped. Its localhost TLS fixture is confined to the separate
qualification artifact and explicitly selected trust store; it is absent from the service distribution.

```bash
./gradlew build installDist qualificationJar
java -cp 'build/install/myos-core-better/lib/*:build/libs/myos-core-better-0.1.0-experiment-qualification.jar' \
  myos.better.AutomaticTimelineProbe native-bench-local-example 2 4
```

The default local run uses memory and does not claim durable restart. An AWS run needs an isolated
table because workers discover work from their store's shared index; the user's live table is not a
safe place for a second runtime with temporary provider bindings. Iteration 107's reviewed controller
and resource/result records are retained with its qualification evidence.

`TimelineServiceProbe` launches the real `Main` subprocess and uses only HTTP APIs. On DynamoDB it
stops that process, starts a new one with the same configuration and proves original-entry continuation
without duplication. Signing keys exist only in the qualification process's memory. The local MemoryStore
variant does not claim process-restart persistence. Run the live qualification only explicitly:

```bash
./gradlew check build installDist qualificationJar
python3 scripts/run_native_admission.py --protocol timeline-service --warmups 4 --samples 8
```

The launcher checks artifact hashes and the isolated AWS deployment, serializes against deployment,
stops the ordinary service during qualification, and restores its exact profile even on failure.
The temporary runtime owns its source configuration. A successful probe does not enable automatic
native processing in a separately deployed manifest without sources. Timings distinguish acquisition,
HTTP child execution and propagation completion; they are bounded acceptance observations.

Still unfinished: dynamic account-owned source management and provider creation, subscription migration
for older receivers, private historical permission enforcement, receiving-history rebase,
Mandates/Time, full Coordination and original MyOS API parity. This profile preserves independent
child/parent commits, not graph-wide proof or rollback semantics.
