# MyOS Core TypeScript SDK

A low-level client generated with OpenAPI Generator `typescript-fetch` from
`core-ui/public/openapi.json`, the same Core Swagger export served at
`/api/blue/v1/openapi.json`. The Java customer target contract is not an input.
The package exports the generated APIs together with technical polling helpers
maintained in `helpers/`. This checkout reuses the `@blue-labs/myos-sdk` source and
package metadata from MyOS Core. Start with the [canonical preview walkthrough](../../docs/canonical-preview.md)
for this host's supported document flow. SDK contract coverage is separate from
complete backend feature parity. This checkout validates the SDK locally and does
not include the upstream npm publishing workflow.

## Account creation readiness

`createAccount` returns HTTP 201 when account data and its durable creation receipt
are saved. `provisioning: PENDING` is successful acceptance while background
registration and authorization preparation continue. Keep the same request body
and Idempotency-Key when polling `createAccount`; select the account only after
`provisioning: READY`. Polling does not activate a workspace.

This expands the response enum from READY-only to PENDING or READY. Consumers
must handle PENDING before deploying against this server behavior; older Java
SDK enum decoders reject it, and callers that immediately select every newly
created account must be updated. SDK publication and version selection remain
separate release steps.

## Account lookup and User profiles

The current generated contract uses `lookupAccounts` instead of `resolveAccount`.
The response is an account page with flat `displayName`/`website` fields and
bounded `matchedUsers`. Account IDs, exact User emails and text selectors combine
with AND; entries within a list combine with OR. Collect all pages before checking
whether every requested ID was returned. Discovery does not grant document access.

```ts
const page = await accounts.lookupAccounts({
  lookupAccounts: { emails: ['anna@example.test'], q: 'studio', limit: 20 },
  xMyOSSessionContext: user.sessionContext,
});
await accounts.updateMyProfile({
  idempotencyKey: crypto.randomUUID(),
  xMyOSSessionContext: user.sessionContext,
  updateMyProfile: { expectedRevision: user.profileRevision, firstName: 'Anna', lastName: null },
});
await accounts.putMyAvatar({
  idempotencyKey: crypto.randomUUID(),
  xMyOSSessionContext: user.sessionContext,
  expectedRevision: refreshedUser.profileRevision,
  file: selectedFile,
});
```

`getMe()` includes nullable names/avatar and `profileRevision`. Profile editing
uses a User session, including Users with no accounts; an account API key cannot
edit a User. `null` clears a name while omission preserves it. The generated
avatar upload uses browser `FormData`: do not force a JSON Content-Type or supply
a multipart boundary. `getUserAvatar()` returns a Blob, not JSON. Browser image
elements can use `imageUrl` with the existing Core cookie; the image read needs
no selected-account context header. There is no compatibility call to the removed
resolve endpoint and no SDK publication as part of local generation.

## Generate and verify

Use Node.js 22 or later and JDK 25, matching this repository. Set `JAVA_HOME` if needed.
From the repository root:

```bash
npm ci --prefix sdk/typescript
npm --prefix sdk/typescript run generate
npm --prefix sdk/typescript run check
```

`generator.json` pins the CLI JAR version, SHA-256 and generator options. The
first generation downloads that exact JAR from Maven Central into ignored
`reports/sdk-generator/`; every run checks its checksum. TypeScript is pinned in
`package-lock.json`. No global CLI or floating `latest` installation is used.

Commit the generated sources and `generation.json` together. Never edit
`generated/` manually. `check:generated` regenerates in a temporary directory and
compares every source file and provenance without modifying the checked-in SDK.
The provenance records the Swagger, generator, configuration and generation-script
hashes. The generation script removes trailing whitespace from source output.
Generation does not depend on a running backend or authentication. The package
entry point `index.ts` also exports the separately maintained `helpers/`; neither
is overwritten by regeneration.

For an intentional API change, update/export the Core contract using the existing
repository workflow, then run `generate` and review the SDK diff. Do not overwrite
the repository contract with an arbitrary remote service response.

Before using a deployed backend, verify it serves the same contract:

```bash
MYOS_URL=https://core.api.dev.myos.blue npm --prefix sdk/typescript run check:backend
```

This uses the generated `SystemApi.getOpenApi()` against the real backend and
compares the response with the committed Swagger. It performs no writes and does
not certify authenticated document execution. A different deployment may expose
another contract even when its `info.version` matches; the check compares the
complete parsed specification. Build/deploy the matching Core release or use the
SDK from the matching source revision when the check fails.

Package selection for bootstrap uses a JSON array: `packages: [exactBlueId]`.
The generated type now matches that wire format. Replace any previous
`Set<string>` value; it serialized as an object and was rejected by the server.
Package identity and uniqueness validation remain server-side.

## Use the generated API directly

Build with `npm --prefix sdk/typescript run build`. Another local Node project can
depend on this directory with a `file:` dependency. Set `MYOS_URL` to the origin
without a trailing slash or `/api/blue/v1` suffix. Set `MYOS_API_KEY` to an account-scoped developer
API key. User/session authentication additionally needs the selected session context;
the console adapter supplies that context explicitly. Never commit or print credentials.

```ts
import { Configuration, DocumentsApi, AdmissionsApi, ResponseError } from '@blue-labs/myos-sdk';

const configuration = new Configuration({
  basePath: process.env.MYOS_URL!,
  accessToken: process.env.MYOS_API_KEY!,
});
const documents = new DocumentsApi(configuration);
const admissions = new AdmissionsApi(configuration);

try {
  const response = await documents.createDocumentRaw({
    idempotencyKey: 'unique-logical-command-id',
    createDocument: {
      inputMode: 'source',
      document: { name: 'SDK example', description: 'A plain Blue document' },
    },
  });
  const accepted = await response.value();
  console.log(response.raw.status, response.raw.headers.get('Location'));
  console.log(accepted.admissionId, accepted.status);

  // One observation; use waitForAdmission below for bounded polling.
  const observed = await admissions.getAdmission({ admissionId: accepted.admissionId });
  if (observed.kind === 'DOCUMENT' && observed.status === 'SUCCEEDED' && observed.details) {
    const document = await documents.getDocument({ sessionId: observed.details.sessionId });
    console.log(document.sessionId, document.documentId, document.processing);
  } else if (observed.status === 'FAILED') {
    console.error(observed.problem);
  }
} catch (error) {
  if (error instanceof ResponseError) {
    console.error(error.response.status, await error.response.json());
  } else {
    throw error;
  }
}
```

The non-`Raw` methods return parsed bodies directly. `Raw` methods also expose
status and headers, including `Location`. Optional request overrides support
`signal: AbortSignal.timeout(...)`. Reuse the same idempotency key and identical
body when retrying an uncertain mutation; a new logical command needs a new key.
No automatic retries are added by this package.

`DOCUMENT` success proves `REGISTRATION_STORED`; `OPERATION`/`TIMELINE_ENTRY`
success proves `TIMELINE_ENTRY_STORED`. Neither proves document initialization or
business completion. A fetched failed admission is HTTP 200. Inspect document
state, processing and history separately. Session IDs, original document IDs,
current content BlueIds, native timelines and entry IDs are distinct.

`TimelinesApi.appendEntry` exposes full native messages, including the difference
between omitted `prevEntry` and explicit null. `DocumentsApi.invokeOperation`
requires a full `message` and timeline; it does not add the native target from the
session path. Read routes through `getDocumentOperations`. See the
[adapted API contract](../../docs/api/openapi-adapted.yaml) for the wire semantics.

## Content values

`ContentsApi.getContent` returns the Blue value directly. `retainContent` takes
`exactContentSubmission: { content: exactValue }` and returns an asynchronous
admission; it has no inputMode or goal. `createContent` retains source/exact and
goal. The former content view and collection methods are removed: read the full
value with `getContent` instead. Cyclic member GET includes references, not its
internal proof. PUT can verify such a member only when that proof is accessible.

## Bootstrap admissions

New bootstrap requests use `document`, optional `inputMode`, and
`participantMappings` selecting existing MyOS accounts by exact `accountId`.
A supported human-representation mapping may also name an active `representativeId`.
The former `participants` and `initialMessages` fields are rejected. The response
is HTTP 202 with a `DOCUMENT_BOOTSTRAP` admission and a polling `Location`.
Its `details` stay null while pending or failed; success contains only
`{ sessionId }` and proves registrations were stored, not document processing.

```ts
const response = await documents.bootstrapDocumentRaw({
  idempotencyKey: 'unique-shared-command-id',
  bootstrapDocument: {
    document: sharedDocument,
    inputMode: 'source',
    participantMappings: [
      { channelName: 'alice', accountId: existingAliceAccountId },
      { channelName: 'bob', accountId: existingBobAccountId },
    ],
  },
});
const result = await response.value();
// One observation; use a bounded polling loop until SUCCEEDED or FAILED.
const observed = await admissions.getAdmission({ admissionId: result.admissionId });
if (observed.kind === 'DOCUMENT_BOOTSTRAP' && observed.status === 'SUCCEEDED' && observed.details) {
  console.log(observed.details.sessionId);
} else if (observed.status === 'FAILED') {
  console.error(observed.problem);
}
```

`bootstrapDocument` and `bootstrapDocumentRaw` return a typed `BootstrapAdmission`.
Accepted work retains an immutable plan; subsequent operations and native party
approvals keep their own authority checks.

## Wait for asynchronous results

All helpers are imported from `@blue-labs/myos-sdk` alongside the generated APIs:

```ts
import { waitForAdmission, waitForDocument, waitFor, AdmissionFailed } from '@blue-labs/myos-sdk';

const options = { timeoutMs: 60_000, pollIntervalMs: 250 };
const accepted = await documents.createDocument(request);
const receipt = await waitForAdmission(admissions, accepted, options);
const document = await waitForDocument(documents, receipt.details!.sessionId, options);

// After submitting an operation, wait separately for its observable effect.
const updated = await waitFor(
  signal => documents.getDocumentContent({ sessionId: document.sessionId }, { signal }),
  content => content.count === 5,
  options,
);
```

`waitForAdmission` preserves the receipt's TypeScript subtype, validates its ID
and kind, and throws `AdmissionFailed` on `FAILED`. Its `admission` property keeps
the complete failed receipt and problem details. An optional `onUpdate` callback
receives the first observation and subsequent status transitions.

The helper pins the accepted receipt ID and kind before waiting. Later changes to
the caller's DTO cannot redirect polling. `submitReviewedOperation` likewise keeps
the original document session through admission and native-input observation. Treat
request payloads and returned observations as immutable application values.

Acceptance can precede a backend's asynchronous read projection. For an already accepted
`PENDING` receipt, `waitForAdmission` tolerates initial HTTP 404 responses only
when the problem code is `ADMISSION_NOT_FOUND`, within the same local deadline.
After the first successful projected read, a later 404 propagates immediately.
Other HTTP errors, including authorization failures and other 404 codes, always
propagate. Keep the same account and session context throughout the wait. A
receipt that never becomes visible times out; it is not reported as successful.
Direct `getAdmission` calls retain their ordinary 404 behavior.

`waitForDocument` waits for non-null `documentId` and `lastStable` and returns them
with narrowed types. It reports `ERROR`, `HOST_INCOMPLETE`, and `PAUSED` processing
states. An initialized snapshot does not prove that a later operation completed;
use `waitFor` with a condition on the state relevant to your operation.

`WaitOptions` provides `timeoutMs` (default 60,000), `pollIntervalMs` (default 250)
and an optional caller `signal`. Both timing values must be positive integers no
greater than 2,147,483,647. Forward the signal supplied to `waitFor` into each read
so cancellation and deadlines also interrupt in-flight requests. The admission
and document helpers do this automatically. Stopping a wait does not cancel an
accepted server command. Apart from the initial admission projection absence
described above, HTTP and other read errors propagate without retries.

`submitReviewedOperation` applies one `timeoutMs` budget to the initial submission,
admission polling and native input polling together. Invalid timer options and an
already-aborted signal fail before submitting the write. A timeout after submission
can leave a committed command even if its response was lost. Recover by submitting
the identical reviewed version, route, request and idempotency key; the helper never
retries or substitutes a newer document version automatically.

Helpers use standard timers and AbortSignal APIs, without Node-specific imports,
environment variables, logging, or process handlers. Application code owns login,
context selection, token handling and any explicit transport retry policy.

## Generator compatibility and boundaries

The served OpenAPI 3.1 remains unchanged. `scripts/prepare-schemas.mjs` makes a
temporary code-generation view: scalar `const` becomes a single-value enum,
multi-type unions become `oneOf`, shared object `anyOf` branches become complete
object alternatives for TypeScript unions, and dates remain wire strings. Nullable
type arrays are retained. This addresses reproduced 7.26.0 errors where YAML
source documents were typed as object-only and nullable receipt dates as non-null.
Conditional JSON Schema validation remains a backend responsibility. The explicit
`package` name mapping retains that legal TypeScript property name instead of
renaming it to `_package`, which would disagree with unmodified wire responses.

`withoutRuntimeChecks` disables generated model conversion as well as model
checks, keeping request/response fields and exact content intact. HTTP request
parameter checks and `ResponseError` remain generated. Types describe the
contract; the SDK does not validate arbitrary Blue documents or filter unknown
response fields. There is no custom runtime serializer or business API layer.

A matching Swagger alone does not establish response conformance or complete backend
feature support. The SDK preserves returned values; it does not synthesize missing
fields or grant authority from a successful schema comparison.

Node's standard JSON numbers have JavaScript precision limits. Use safe integers
for ordinary JSON values. Content GET/PUT use direct values and `{content}`; they
do not accept an exact text/proof transport. For larger content integers, use
a lossless raw JSON/YAML HTTP client; the generated client's standard JSON
parser cannot preserve them. Document source text remains available for document
creation. Do not hash a display projection as exact Blue evidence.
The generated live endpoint is not a streaming/reconnect abstraction.

`npm run check` checks regeneration, compiles all generated APIs/models, compiles
positive/negative contract type examples, and exercises the generated client
against a disposable local HTTP server. These transport tests cover credentials,
idempotency, admissions, problem details, native messages, null/omission, URL
encoding, pagination and exact content. The separate read-only backend check is
opt-in. The [host test strategy](../../docs/TESTING.md) describes the separate
canonical native, signed-input, console and API integration gates. Running local
SDK checks neither contacts AWS nor proves a deployed document execution.

## Publication boundary

`package.json` and `package-lock.json` retain the reused package name and current
version. Generation, local builds and backend deployment do not publish npm packages.
This checkout does not include the upstream `Publish SDK`/`Verify` workflows or
`sdk_release.py`; do not assume those release commands exist here. A future release
must explicitly select its version, repository metadata, license and publishing
credentials. `npm pack --dry-run` can inspect the package contents without publishing.

### Wait for a business input

After `waitForAdmission` returns the stored timeline entry, use
`waitForNativeInput(documents, sessionId, entryBlueId)` to wait for that input’s
retained stable history or a terminal native decision. Hosted owner receipts expose
`positionRef`, `documentRef` and `receiptRef`. Original Core event payloads carry the
three identifiers in `position`, `document` and `receipt`. The helper accepts either
complete record for the requested session and entry. For a failed input, inspect
`disposition.code` and `disposition.epoch` when present. Before confirming that a
payment completed, read the Payment Instruction’s published business fields.

`projectBlueData` produces a lossy display projection of native scalar/list envelopes. It preserves structured type references and does not resolve types or establish authority. Keep the original content for exact identity, signatures, source editing and native requests. When reading large integers, parse the raw response losslessly before projection; ordinary JavaScript JSON numbers cannot represent every Blue Integer.

### Prepare an existing document operation

`DocumentsApi.prepareDocumentOperation` resolves and validates a discovered
operation for review without creating an Admission or submitting input. Supply
the exact dispatch key, selected authorized route and reviewed publication.
`requestJson` is optional; when supplied it preserves the native request shape
and exact integer/decimal values as text. Keep `messageJson`, definition digest
and publication with the reviewed proposal. Changed data requires new review;
preparation does not authorize a later submission or prove a business effect.

## Qualification support

The SDK tests use `scripts/core-baseline-sdk-probe.mjs` and
`scripts/core-checkpoint-sdk-acceptance.mjs` from the repository root. These two helpers are retained
from the original MyOS service alongside the reused SDK tests. Their unit tests replace transport
with deterministic fixtures; running `npm test` does not contact a deployed service. The standalone
helpers only run when explicitly invoked with a target and input on stdin. They do not discover
credentials or print credential-bearing failures. Passing these local fixture tests is not a claim
that the AWS deployment passed a migration or recovery campaign.
