# Historical host documentation — superseded 8 November 2026

This preserves earlier experiments and measurement boundaries. It is not the current API or deployment guide. Use [the current README](../README.md).

# MyOS Core Better

The current independent-owner deployment, correctness checks, measured AWS timings and limits are
in the [8 November report](docs/owner-aws-report-2026-11-08.md). Follow the
[reproduction guide](docs/owner-reproduction.md) for Agreement → Order → Payment and mixed local /
external timelines; see [the ordering model](docs/owner-frontiers.md) for completeness and recovery.

This alternative MyOS host uses `blue-java-better` and DynamoDB. With `MYOS_OWNERS_FAST=true`,
new `graph-fast-*` documents use independently persisted native owners and initial cyclic units.
Each receiver consumes ordered historical inputs under verified completeness bounds. Existing
`graph-*` documents retain the earlier whole-graph adapter. SQLite is not used. Bounded immutable
page/type caches are shared; mutable document sessions are restored per attempt and discarded.

**Status: experimental, under active qualification.** Local business parity, restart recovery,
conditional publication, idempotency, concurrent hosts and tenant isolation have tests. The initial
AWS deployment is isolated, with an authenticated HTTPS console at
[MyOS Better](https://d2ya9daeiur7nz.cloudfront.net). Create an account, confirm its email, then open
**Examples** or **Documents → Bootstrap document**. **Timelines** supports private creation,
signed message append and entry browsing. **API Keys** creates and revokes application credentials
for the same owner account; see [usage](docs/API-KEYS.md). The console reuses the existing MyOS components;
execution and persistence use Better. See [console instructions](docs/CONSOLE.md).
Live storage and large-network qualification are recorded in
[the historical overnight report](docs/OVERNIGHT-REPORT-2026-11-05.md). The current owner adapter supports initial cyclic components and canonical rollback. Arbitrary
historical graph migration, portable proof parity and production high availability remain unfinished.

The [512-document payment walkthrough](docs/PAYMENT-WALKTHROUGH.md) records an earlier host
experiment. Its synthetic/kernel numbers do not qualify the current published-clause route.

The published Activation/Documents/Payment workflow uses the signed native Timeline route.
Its end-to-end latency target is still unmet; earlier kernel and synthetic-graph timings do not
qualify that route. See the [8 November report](docs/owner-aws-report-2026-11-08.md) for current
results, the deployed build, retained optimizations and remaining scaling work.

The earlier [canonical graph adapter](docs/CANONICAL-GRAPHS.md) remains available for existing
`graph-*` documents, with whole-graph publication and cyclic rollback.
Its bounded measurements retain exact canonical receipts and show the remaining size-dependent
cost. Follow the [AWS clause walkthrough](docs/AWS-CLAUSE-WALKTHROUGH.md) to try it.

## Run locally

Requires JDK 25 and the sibling `../blue-java-better` checkout.

```bash
./gradlew test installDist
build/install/myos-core-better/bin/myos-core-better benchmark 16 3 16
build/install/myos-core-better/bin/myos-core-better serve
```

Without `MYOS_TABLE`, the process uses an ephemeral test store. Set `MYOS_TABLE` and `AWS_REGION`
to select DynamoDB with the standard AWS credential chain. Deployed hosts use their instance role.
The operator HTTP listener binds to loopback for an authenticated SSM tunnel. The deployed console
uses a separate authenticated router; public requests derive their account from the verified login.

## Operator HTTP API for the earlier document-local editions

The endpoints below describe the earlier document-local host. For the authenticated console's
canonical graph API, see [canonical graphs](docs/CANONICAL-GRAPHS.md) and the
[clause tutorial](examples/clause-tutorial/README.md). Canonical child/parent changes commit together;
the asynchronous propagation endpoints below apply to the earlier editions.

Every operation request supplies an account, document and stable command identifier. Retrying the
same identifier and request returns the original result. Reusing an identifier for different input
is rejected. Unverified native admission does not reserve a command ID. Parent propagation is asynchronous; inspect the agreement after the relevant child
notification has arrived. All timestamps are explicit deterministic nonnegative integers.

- `GET /health` and `GET /metrics` (low-cardinality storage counts/times).
- `POST /documents/create`: `{account, document, command, source}`; source is YAML text or JSON data.
- `POST /documents/call`: `{account, document, command, channel, operation, request, timestamp}`.
- `POST /documents/process`: `{account, document, command, event, timestamp}` preserves an authored event envelope.
- `POST /documents/admit`: `{account, document, command, event, timestamp, evidence}`; explicit scoped native admission, requiring configured namespace and verifier. See [native ingress](docs/NATIVE-ADMISSION.md).
- `POST /timelines/head`, `POST /timelines/acquire` and `POST /documents/timeline/step`: explicitly
  configured signed-source acquisition and processing; see [Timeline service](docs/TIMELINE-SERVICE.md).
- `POST /documents/command`: `{account, document, command}` returns its original input and durable result.
- `POST /documents/propagation`: `{account, document}` inspects pending or blocked source delivery.
- `POST /documents/propagation/retry`: `{account, document, command}` explicitly retries a blocked source.
- `POST /documents/head`: `{account, document}` returns version and local identity without rendering data.
- `POST /documents/read`: `{account, document, expanded, version?}`. Omit `version` for the latest state; specify it for an immutable historical view. Full expansion is an explicit read cost.
- `POST /documents/children`: `{account, document, cursor?, limit?}` returns up to 100 direct links without opening children. Follow `nextCursor` until null; continuation pins the original parent version. Each item includes its readable child `version` and the `observedVersion` whose events reached the parent (zero means initialization is pending).
- `POST /documents/events`: `{account, document, after, limit}` returns versioned public events and `nextAfter`; `0` means the last page.
- `POST /documents/embedded`: `{account, document, path}` returns the durable child identifier.

The returned `executionNanos` measures the runtime call. It excludes initial checkpoint loading,
durable publication, queueing and later parent propagation, but includes host child reads requested
from inside the workflow. It is not necessarily a CPU-only measurement. Benchmark output separately reports the complete leaf call through publication and end-to-end completion, after 128 warm-up calls.
Synthetic scenario business rules are BEX/Contracts documents; Java does not implement the payment
or aggregation workflows.

## Storage and correctness

A conditional transaction publishes document state, its immutable version, the idempotency result,
reverse links and outbox. Small immutable index-page sets publish in that same transaction; larger
sets publish first, before their root pointer can commit. A failed publication cannot expose staged
pages through a document. See [the publication comparison](docs/PERFORMANCE-CONTENT-PUBLICATION-128.md).
Workers deliver source versions in order. A durable parent command identifier prevents duplicate
business effects when a notification is retried after a crash.

The index test changes one child among 20,000 siblings and verifies old-root reads, unchanged siblings,
deletions and bounded page writes. Whole-collection reads enumerate local links and materialize the selected collection. A root with
arbitrary business logic reacting to every child remains a serialization point.

See [architecture](docs/ARCHITECTURE.md) for the strategy and semantic tradeoffs. No package publisher
or changes to the original Python, MyOS deployment, or shared package publications are included.

The internal [published Timeline ingress](docs/TIMELINE-INGRESS.md) derives required public sources
from exact receiving declarations and executes signed entries through the published bindings.
Explicit [main-service configuration and HTTP processing](docs/TIMELINE-SERVICE.md) are available.
Opt-in automatic public HTTPS acquisition and durable receiver scheduling are available for configured
sources. Signed private reader access and operator source adoption are available. Receiving-policy
rebase and adoption/routing through the owner console remain unfinished.
Account-owned Timeline publication is available in the console; see [its boundaries](docs/HOSTED-TIMELINES.md).

The internal [timeline acquisition archive](docs/TIMELINE-ACQUISITION.md) stores verified source
evidence and immutable content through the same conditional store. Warm importers verify only new
batches; a fresh process replays retained evidence. The internal [source selector](docs/TIMELINE-PROCESSING.md) now publishes receiver progress with
actual document execution for explicit source sets. The configured automatic runner connects public
provider acquisition to this path; explicit signed-evidence submission remains available as well.

## Deployment

`scripts/prepare_infra.py` prepares a bounded template. `scripts/deploy.py` uploads a built distribution,
verifies its SHA-256 on the host and starts the private service. Both verify the selected AWS account.
Use `AWS_PROFILE` or explicitly select the existing local credentials file with `MYOS_AWS_PROPERTIES`;
credentials are not copied into this repository or artifacts.

The isolated stack is `myos-better-20261105` in `eu-west-1`. It contains one `c7i.large` host with no
public operator port, an encrypted DynamoDB table with point-in-time recovery and a private artifact
bucket. The separate console stack exposes HTTPS through a private CloudFront VPC origin; port8088
accepts the CloudFront managed prefix only. Deploy with `--console` to retain that listener; follow
[the complete console build/deploy steps](docs/CONSOLE.md).
Resources are retained for inspection; review date is 2026-11-12. Retention tags do not perform cleanup.
The DynamoDB table has a Retain policy. Cleanup must explicitly choose whether to preserve its data.

## Run the complete HTTP example

With the service running locally, use `/usr/bin/python3 scripts/demo.py`. It initializes an agreement,
adds an order containing two payments, records one payment and waits for the order and agreement
listeners. The final agreement has `paidTotal: 42`; its public event is `Agreement Payment Received`.
The authored definitions are in `examples/`; the inspection report is written to ignored `reports/`.
See [operations](docs/OPERATIONS.md) for the AWS tunnel, restart, rollback and cleanup commands.

The [pinned Hello/Activation example](examples/hello/README.md) loads actual package publications,
requires consent, validates an action and retains its events and original input. Configure packages
through [a verified runtime manifest](docs/RUNTIME-PACKAGES.md). The [AWS report](docs/AWS-PERFORMANCE.md)
contains the 80,001-document qualification and measured storage improvements.

The [overnight implementation report](docs/OVERNIGHT-REPORT-2026-11-05.md) summarizes retained and
reverted experiments, measured AWS results, and the remaining compatibility/production boundaries.

Full document views are bounded to 256 document visits, 262,144 structural traversal units,
4,194,304 UTF-16 text units (including keys) and depth 64. An over-limit read returns HTTP 413
with `VIEW_LIMIT_EXCEEDED`; use `/documents/children` and exact-version individual reads to explore
larger graphs. These are read resource bounds, independent of execution gas.


For a synthetic tree that has grown using non-sequential keys, use
`scripts/run_benchmark.py --probe --stored-links --orders <actual-order-count> --account <bench-account>`.
This enumerates a pinned root's stored order links before measurement; `targetEnumerationMillis`
reports that separate cost. Omitting `--active-orders` samples the full enumerated population.
The original probe mode intentionally retains its sequential `oN` key convention for earlier
controlled comparisons. Both modes use128 warmups, check every root total, and exclude target lookup
from operation timings. They are synchronous CLI probes, not concurrent HTTP saturation tests.


The [current real-worker scale probe](docs/PERFORMANCE-WORKING-SET-130.md) compares existing synthetic
graphs with sequential and concurrent payments. `scripts/run_graph_load.py` requires explicit small
and large `bench-*` accounts, pins the deployed runtime manifest/artifacts, and restores the main
service after its bounded campaign. It records all touched document deltas and acknowledgements.
Use `--active-orders 16` to hold active-set size/overlap constant across stored graph sizes; omit it
to sample the full population. Full inventory/target setup is reported separately from operation time.

The [independent-owner AWS reproduction](docs/owner-reproduction.md) covers the current canonical
owner scheduler, published Agreement/Order/Payment clauses, and imported external timelines.
See [the ordering and durability model](docs/owner-frontiers.md) before comparing these results
with earlier document-local performance experiments.
