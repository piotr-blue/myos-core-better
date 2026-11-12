> Historical deployment report. The current isolated canonical preview and reproduction commands are in [canonical-preview.md](canonical-preview.md). These earlier timings and edition labels are not claims about the current build.

# Independent owners: reproduce the AWS tests

Console: https://d2ya9daeiur7nz.cloudfront.net. Sign in with your existing account.
New documents use `blue.independent-owners/rooted-1`; existing documents retain their original execution path.

## Published clauses: Agreement → Order → Payment

From the project directory, run:

```sh
python3 scripts/reproduce_canonical.py \
  --url https://d2ya9daeiur7nz.cloudfront.net \
  --email piotr@bluecontract.com --orders 2 \
  --report reports/my-clause-run.json
```

The password is requested interactively and is not written to the report. Alternatively set
`MYOS_API_KEY` to an API key created in the console and omit `--email`.
The script reports the created Agreement URL. It uses published Activation, Documents, Order
and Payment clauses from the pinned runtime manifest, through the normal signed timeline API.
It performs these operations:

1. Initialize the Agreement and its two participant timelines.
2. Call `customer/placeOrder` with `{ "orderId": "order-1" }`.
3. Open `/orders/order-1`, then call `buyer/preparePayment` with `{}`.
4. Open that Order's `/payment`, then call `guarantor/acceptPaymentInstruction` with `{}`.
5. Call `payer/reportTransfer` with `{ "requestId": "transfer-1", "reference": "bank-1" }`.
6. Call `guarantor/acknowledgeTransfer` with the same request.
7. Verify Payment `/payment/completed`, Order `/paidTotal`, and Agreement `/paidTotal` equal 200.

With two Orders the script repeats the Payment workflow and checks the Agreement reaches 400.
It verifies both participant timeline IDs are shared across all three levels, every submitted
original has an APPLIED history entry, and unrelated first-Order identities remain unchanged.
Use `--orders 128` for growth qualification. Intermediate measurements are saved after each
completed operation, so a failure does not erase earlier evidence. The script stops on a server
error; it does not silently retry or claim success for unobserved operations.

The shared large test agreement is
[the 156-Order agreement](https://d2ya9daeiur7nz.cloudfront.net/#documents/graph-fast-b65952f8c4a604538afc2cf4d421256ef1f7c05ef99ffc4a).
It reached `paidTotal: 1400`: seven Orders have completed Payments; the remaining Orders are unpaid.
A smaller [one-Order example](https://d2ya9daeiur7nz.cloudfront.net/#documents/graph-fast-d8621612e4d7e9a67338f378566419507fe279accd81de98)
completed the same workflow on the final release and has `paidTotal: 200`.
To try the workflow manually, add an unused ID, for example `my-order-157`, then follow steps 3–7.
Do not pay an already completed Payment again. The same channel and operation names apply.

In the console use the operations panel for the same channel/operation pairs. Open embedded
Order and Payment documents through their links. History records retain original input, gas,
emissions and the resulting version. The API script measures client-observed latency, including
submission and polling. Its `engineMillis` field is the host's recorded execution interval,
which includes restore/admission I/O; it is not pure CPU time.

## Local and external timelines together

The live isolated external provider is https://dzkbzs1daaeut.cloudfront.net/api/provider.
It uses the current Coordination edition and a separate provider identity/process. Imports
use signed HTTPS acquisition, even though the qualification processes share one EC2 instance.

Its public test timelines are:

- `6bd1f9VEkDYyyhAys8wzSK7aRPaJgN5MDXV76whz8WEm`
- `BVLnBzLP7m8NDbqBJKskwrGLUM7LomjMrp21cxcWuofX`

The private test timeline is `8xzj3T2mqKTSYr3338MPm3qN1mthWhhNUJbCNcE5BWX2`.
Reading it requires a grant for the reader key; an import never grants itself access.

Use the Timelines page's import action and the endpoint above. The API equivalent is:

```http
POST /api/blue/v1/timelines/import
Authorization: Bearer <your-api-key>
Content-Type: application/json

{
  "endpoint": "https://dzkbzs1daaeut.cloudfront.net/api/provider",
  "timelineId": "6bd1f9VEkDYyyhAys8wzSK7aRPaJgN5MDXV76whz8WEm",
  "readerKeyId": "<your-reader-key-id>",
  "name": "External public input"
}
```

Import registers periodic acquisition. The Retry action explicitly requests acquisition;
`POST /api/blue/v1/timelines/<id>/retry` with `{}` is its API equivalent. Document processing
also requests a refresh when a candidate is beyond the retained verified coverage.

Create a document from `examples/owners/mixed-timelines.blue` using the console's bootstrap
flow. It replays the two external histories (each currently contains `amount: 7`), reaching
`count: 14`, and provisions its own local input channel. Calling `local/increment` adds 5.
External imports can proceed independently of this document. They are not polled once per
embedded node or synchronously fetched for every entry when coverage already suffices.

The provider has no public mutation endpoints. `ExternalProviderProbe` is an explicit
qualification tool; administration runs through SSM under the instance role. Its protected key
stays on the host. The parameterized CloudFormation infrastructure is in
`infra/external-provider-qualification.json`. After creating that stack:

```sh
./gradlew -PblueLibrary=../blue-java-better installDist qualificationJar
python3 scripts/deploy_external_qualification.py --external-stack <external-stack-name>
```

The command prints an SSM command ID; verify it reaches Success before using the endpoint.
The script reuses the protected identity on upgrades. It does not write private keys into reports.

## What the evidence establishes

See `owner-frontiers.md` for the algorithm, storage boundary and limitations. The local test suite
covers canonical equal-time ordering, child removal, restart, leases, corrupt evidence, mixed
completeness and initial cycles. AWS reports distinguish the small native parity workload from
the full published-clause journey. Passing the former is not a substitute for qualifying the latter.


## Explicit AWS storage qualification

Run `./gradlew -PblueLibrary=../blue-java-better installDist qualificationJar`, then
`python3 scripts/qualify_owners.py --orders 1,128` using your AWS credentials. This provisions and
terminates a temporary dedicated EC2 worker. It does not run a second benchmark JVM on the live
application host. It writes isolated DynamoDB test accounts; retained evidence is deliberately
not deleted by the script. The JVM uses two CPUs, a 1 GiB heap and a 1.5 GiB memory limit.
The eight checks compare canonical document identity, gas and native completion receipt. These
are simple native documents, not the full published-clause tutorial, and their immutable caches
are warm after initialization. See the report for the separate end-to-end and restart measurements.
