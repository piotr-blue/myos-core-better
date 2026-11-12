# Scoped native admission

The host now connects the library's native verifier to durable commands. This is a generic ingress
protocol, `myos.native-input/1`, not an implementation of the published Timeline, Time, Mandates or
Coordination services. Package import does not install executable verifiers.

Configuring the namespace also enables atomic [document-lineage indexing](DOCUMENT-LINEAGE.md).
Existing unindexed histories and gaps left by older writers require explicit migration; the host
does not infer complete lineage from the current body. No migration utility is provided yet.

## Trusted scope

Set `MYOS_ADMISSION_NAMESPACE` to a stable operator-controlled namespace, unique to this logical
store. Every process sharing the store must use the same namespace. Preserve it across restart;
changing it changes command identity and requires an explicit migration policy. Do not derive it from
an HTTP request or a field inside a proof. Account and document come from the selected store target.

For an external command the host computes:

```text
scope = {format: "myos.native-input/1", namespace, account, document, command}
runtimeCommand = "myos.native-input/1:" + lowercaseHex(SHA256(sortedKeyJsonUtf8(scope)))
inputId = BlueId({command: runtimeCommand, eventIdentity: payloadBlueIdAsText})
```

The scope contains only strings; JSON object keys are sorted. `eventIdentity` is text, not a resolving
Blue reference. The installed verifier receives `inputId()`, the unchanged `eventId()`, exact snapshot,
timestamp, channel declaration/generation and its untrusted evidence. A protocol must verify all
bindings it requires, including this input identity, using its own trusted authority. The host does
not turn an arbitrary callback into authentication. The tests use actual HMAC signatures with a
clearly test-only key; that registration/key is absent from the service artifact.

The existing runtime manifest still does not load a production native verifier. Java integrations can
install one through `DocumentRuntime.newBuilder().nativeChannel(...)`. Merely setting a namespace
neither installs a protocol nor makes a package's native requirements available.

## HTTP and durable input

The private service accepts `POST /documents/admit` when a namespace is configured:

```json
{
  "account": "alice",
  "document": "agreement",
  "command": "input-42",
  "event": {"kind": "Application Event"},
  "timestamp": 42,
  "evidence": {
    "<exact-channel-type-blueId>": {"signature": "<protocol-proof>"}
  }
}
```

The two angle-bracket values are placeholders, not valid identities/proofs. Only the six shown fields
are accepted; callers cannot override namespace or trusted target bindings. Evidence uses the library's
bounded integer/text/boolean/null/list/map record domain. The complete captured native input is capped
at128 KiB before execution. Native inputs have a separate fingerprint domain from legacy commands.

The original event, timestamp, evidence, namespace, target and protocol format are captured before
execution. A verified command's state, history, events and command receipt publish atomically. The
receipt can be read through `/documents/command`. Changed payload or changed evidence cannot reuse a
completed command ID. An exact retry after restart or an uncertain commit returns the retained result;
it does not execute the document or reuse its authority for a different command.

A conditional conflict discards the speculative session and re-verifies against the newly loaded
snapshot. A previously valid snapshot-bound permit may then be stale. The caller must prepare a new
permit; the host does not silently reinterpret it for the changed document.

## Rejection and command ownership

`DocumentRuntime.Session.admit` returns an `AdmissionResult`: the update plus whether every active
native verifier completed. At least one verifier must be active. The distinction matters:

- **Unverified:** no state, event or command receipt is written. A condition-only transaction checks
  that the rejection used the current state revision. If that revision changed, the host retries.
  The command ID remains available for an authorized request.
- **Verified, workflow failed:** the document remains unchanged, but the original input and failure
  receipt publish conditionally. Retrying the same command returns that failure.
- **Verified, applied:** state, effects, history and receipt publish together.

The legacy `/documents/process` and `/documents/call` paths refuse documents that currently require
native admission before reserving any command identifier. Otherwise a forged evidence-free request
could block a later valid native request. This preflight is a constant-time empty-registration check
for the default runtime; only configured native runtimes inspect the current local declarations.

DynamoDB and the in-memory store both support condition-only transactions. These check the current
revision without creating a receipt. See the official
[TransactWriteItems action contract](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_TransactWriteItems.html).
No durable invalid-proof audit is promised; adding abuse telemetry must not allocate authoritative
command IDs for unauthenticated requests.

Trusted child delivery is not external channel ingress. `DocumentRuntime.receive` relies on the host's
source-version and link-generation checks; it does not demand unrelated external-channel evidence or
select those external channels. A parent can have both protected external inputs and child listeners.
This does not make caller-supplied child events trusted: the HTTP adapter exposes no raw receive API.

## What this does not establish

The service remains private loopback/SSM access, without the original MyOS identity/account gateway.
The namespace and configured verifier are trusted deployment inputs. There is no automatic proof
preparer, ordered native timeline, closure/frontier proof, timer registration/cancellation service,
external effect executor or production key lifecycle yet. Exact native package identities must remain
unsupported until their complete protocol is implemented. Existing application and runtime editions
keep their original command envelopes outside this explicitly selected ingress path.

`NativeAdmissionTest` owns scope replay, all local editions, captured input, cold restart, uncertain
publication, stale-proof conflicts, command poisoning, authorized failure and child propagation.
`NativeAdmissionHttpTest` owns HTTP proof transport and namespace refusal. `DynamoTransactionTest`
checks that the SDK sends only the intended condition check and surfaces conflicts for retry. These
ordinary tests do not contact AWS; live qualification is reported separately.


## Explicit live qualification

Build `qualificationJar`, then run `python3 scripts/run_native_admission.py --warmups 16 --samples 32`
using the same MYOS_AWS_PROPERTIES configuration as deployment. The launcher pins the deployed runtime
and creates a fresh synthetic account; it leaves the main service running. It prints SSM command and
S3 report identifiers. Observe that command to terminal status before interpreting the uploaded result.
The separately packaged probe generates an ephemeral Ed25519 key and a synthetic channel registration;
it is not a production key manager or published Coordination/Time implementation. Release83's live
result and its limitations are recorded in OVERNIGHT-REPORT-2026-11-05.md.


## Internal acquired-source ingress

The host's package-private `Core.admit(..., TimelineInputs.Plan)` joins a frozen original occurrence
with the same native admission path. It binds identity, timestamp, receiver version and source-progress
revision, then commits successful BEX state and progress together. Recent verifier-issued headers can
avoid revalidating predecessor history after session renewal; they cannot authorize business input.
The HTTP adapter exposes no Plan constructor or caller-supplied progress. See
[source processing](TIMELINE-PROCESSING.md) for the explicit-source scope and remaining native protocol work.

The internal [Timeline ingress](TIMELINE-INGRESS.md) now uses the published bindings with real acquired
public-source history, declaration-derived source requirements, lineage and original-order checks.
It remains separately configured Java infrastructure. Private admission requires the explicit
[historical-access policy](TIMELINE-PRIVATE-ACCESS.md); receiving rebase remains unfinished.
The main HTTP service installs Timeline processing only through an explicit runtime manifest.
