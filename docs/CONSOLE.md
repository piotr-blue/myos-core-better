# MyOS Better console

The isolated console uses the existing MyOS Core layout, document viewer, dialogs, data table,
fonts and styling. `core-ui/REUSE.json` records the imported component hashes. Its document screens
call the Better runtime; they do not proxy execution to the original MyOS deployment.

Open https://d2ya9daeiur7nz.cloudfront.net. Create an account, confirm its email and sign in.
The new Cognito pool is separate from the original MyOS identities. Each verified subject owns
an independent account; a request cannot select another user's account. Sessions use a Secure,
HttpOnly, SameSite=Strict cookie and expire after an hour. Sign in again after expiry.

## Run documents

- **Documents → Bootstrap document** opens an editable YAML/JSON source. Submit Hello Counter:
  initialization sets `initialized: true` and emits `Counter Initialized`. In Operations, call
  `increment` on `input`, with `{}`. The count becomes 1 and emits `Count Changed`.
- **Examples** lists ordinary editable source documents. Starting an example uses the same document
  creation endpoint as any application; the server has no order/payment orchestration endpoint.
- **Overview / State / Source / Operations / Timelines / Links / History / Events / Processing**
  provide document navigation. Views and historical versions are preserved in the URL and survive
  reload. Source shows the input supplied before initialization, including its original text.
- **Operations** uses declared operations and their request shapes. Supply the request yourself;
  the console no longer recognizes special operation names such as `addOrder`, `record` or `settle`.
- **Links** pages direct children and shows their versions received by the parent. Navigate down to
  a child and call its operations to observe propagation. There is no example-specific Payment flow tab.
- **History** starts with the latest versions. Expand **Input and outcome** to inspect the actual
  input, result and emitted events, or **Inspect version** to open immutable historical state.
  Records created before input references were retained explicitly report unavailable original input.
- **Processing** reads durable parent propagation and Timeline-work diagnostics. It does not imply
  that every native integration is configured. State expansion is explicit and bounded.

Bootstrap here means admitting and initializing a document in the document-local runtime. It does
not claim the original MyOS participant/email-to-Timeline bootstrap protocol. Native services,
shared-account permissions, webhooks, timers and the original console's full API surface remain
separate integration work. The console shows the supported document workflows.

A root is registered in the account's discovery index in its creation transaction. The index label
is its initial name. Older operator/benchmark roots are not backfilled into a public user's account.
Each mutation uses a stable idempotency key retained through an uncertain response and browser
refresh. A definitive validation rejection ends that attempt. Auth requests are never automatically
retried because signup and confirmation can send email.

## Create and inspect a private Timeline

1. Open **Timelines → Create timeline**, enter a name, and select **Create**.
2. Choose **Append message**, enter any YAML/JSON message, then **Append**.
3. Inspect the selected entry and expand **Provider attestation**. Reload the page and reopen Entry 1.
4. Append another YAML or JSON message; its predecessor refers to Entry 1. **Open latest entry**
   jumps straight to the newest content. **Message only** hides the entry envelope; filtering matches positions or BlueIds in the loaded page. **Timeline definition** shows its BlueId/provider/private flag.

These are immutable account-owned messages. They are not document operation calls. Use a document's
**Operations** to exercise processing and propagation. Other accounts cannot read
or append to your Timeline, even with its BlueId. The Sharing tab manages signed reader grants and
revocation. Connecting adopted sources to business documents in the owner console remains work.

## Application credentials

**API Keys** provides the Core-style create/list/revoke screen. A bearer key can use your document
and Timeline APIs without a browser session, but cannot manage keys. The secret appears once;
revocation is checked on each new request. See [API key usage and recovery](API-KEYS.md).

[The console parity map](CONSOLE-PARITY.md) tracks the requested supplied-Timeline creation, full
participant bootstrap, matching detail/history screens and clause-based order/payment tutorial.
These remain in progress; basic initialization is not the full bootstrap protocol.

## Build and deploy

```bash
npm ci --prefix core-ui
npm test --prefix core-ui
npm run --prefix core-ui build
python3 scripts/console_build.py
./gradlew build installDist
MYOS_AWS_PROPERTIES=/Users/piotr/data/myos-aws-test/aws-access.properties \
  python3 scripts/deploy.py --console --runtime-profile host-links \
    --workers 8 --session-entries 256 --session-mib 512 --session-uses 32
```

`console_build.py` records exact source/asset digests. Deployment refuses a stale console build.
Observe the returned SSM command until `Success`; submission alone is not deployment completion.

The edge stack `myos-better-console-20261105` owns CloudFront, its private VPC origin, an 8088 ingress
rule restricted to the CloudFront managed prefix list, and its Cognito pool/client. It references
only the existing Better instance. It creates no additional EC2 host or database. The 8080 operator
API remains loopback-only and inaccessible through the public console router.

The existing single-host and retention limitations still apply. No production HA claim is made.
The identity pool has Retain protection. Review date 2026-11-12 is a tag, not automatic cleanup.
Removing the edge stack disables its URL and ingress; explicitly decide whether to retain its
Cognito users. The Better DynamoDB table and original MyOS resources are separate.

The retained provider stack holds a separately provisioned signing key. Public pins in
`infra/hosted-provider.json` are loaded by deployment automatically; do not put private key material
there. See [key custody and remaining protocol work](HOSTED-TIMELINES.md). The host fetches one exact
secret version at startup and refuses identity changes. Restart keeps the provider and Timeline IDs.
