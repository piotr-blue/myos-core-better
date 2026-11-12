# Canonical preview: Agreement → Order → Payment

Open <https://d2buikczdox1rt.cloudfront.net> and sign in with your existing MyOS account. This preview
uses isolated document storage and the existing login pool. Your earlier documents at the previous
console URL were not moved or rewritten.

## Run the published clauses

From this checkout, using Python 3 for the API client only:

```sh
python3 scripts/reproduce_canonical.py \
  --url https://d2buikczdox1rt.cloudfront.net \
  --email piotr@bluecontract.com --orders 2 \
  --report reports/my-canonical-clause-run.json
```

Enter the password at the prompt. It is not saved in the report. Alternatively create an API key
in the console and supply it through `MYOS_API_KEY`, omitting `--email`. Do not put a password or
API key into a document. The script creates its own Agreement and two participant Timelines,
prints the document URL and records each completed operation. This is a normal authenticated API
journey, not a special Java implementation of the business example.

The pinned source and package configuration are in `examples/clause-tutorial`. In the console,
the corresponding manual operations are:

1. Bootstrap the Agreement with its two participant timelines.
2. Call `customer / placeOrder` with `{ "orderId": "order-1" }`.
3. Open `/orders/order-1`; call `buyer / preparePayment` with `{}`.
4. Open that Order's `/payment`; call `guarantor / acceptPaymentInstruction` with `{}`.
5. Call `payer / reportTransfer` with `{ "requestId": "transfer-1", "reference": "bank-1" }`.
6. Call `guarantor / acknowledgeTransfer` with the same request.
7. Check Payment `/payment/completed`, Order `/paidTotal` and Agreement `/paidTotal`: each is 200.

A second Order follows the same steps with fresh Order/transfer identifiers. The Agreement total
becomes 400. All levels reuse the same two participant timelines. History exposes the original
input, resulting version, gas and emitted events. Use a fresh identifier when adding another Order;
do not repeat a completed transfer as a new business action.

## What the timings mean

The reproduction script measures submission through observing the exact submitted entry in the
durable parent history, including HTTPS and polling. Business values are then asserted separately. The AWS qualification also records exclusive restore, admission, native
execution, preparation and commit phases for each owner attempt. Concurrent attempt durations
cannot be summed to infer wall time. Loading types and bootstrap are reported separately.

The cold synthetic scale probe recreates bounded owner storage caches before each input. It does
not assume the document graph remains in memory. The local growth probe closes and reopens its
durable runtime between inputs. Neither probe currently establishes constant latency at 1,000
Orders. Do not substitute kernel-only or warm-session timings for these measurements.

## Reproduce the engineering checks

```sh
./gradlew test installDist qualificationJar
python3 scripts/qualify_clauses.py --orders 2
python3 scripts/qualify_owners.py --orders 10,1000 --cold --profile
python3 scripts/qualify_preview.py --orders 2
```

The AWS scripts require the project's authorized AWS configuration. They use isolated QA accounts;
the first two allocate temporary benchmark workers and terminate them in `finally`. They do not
benchmark on the live console host. The HTTPS qualification uses suppressed-email QA users,
checks account isolation and API-key revocation, and keeps credentials in a private ignored file.

From `../myos-simplified`:

```sh
./gradlew test -PpackagesDir=../blue-packages
./gradlew orderGrowth -PpackagesDir=../blue-packages \
  -PqualificationOrders=10,1000 -PqualificationSamples=5 \
  -PqualificationOutput=build/qualification/my-growth-run
```

Choose a fresh output directory. Each measured case is compared against whole-root native
processing outside the timed interval. Static-cycle and asynchronous-ring scenarios have separate
qualification commands and retained reports in the campaign report.


## External and mixed-source checks

The retained public external provider is `https://dzkbzs1daaeut.cloudfront.net/api/provider`.
These engineering checks create QA imports and documents, but never append to that provider:

```sh
python3 scripts/qualify_external_preview.py \
  --endpoint https://dzkbzs1daaeut.cloudfront.net/api/provider \
  --timeline 6bd1f9VEkDYyyhAys8wzSK7aRPaJgN5MDXV76whz8WEm
python3 scripts/qualify_mixed_preview.py
```

The first requires a strictly newer verified completeness boundary after refresh while checking
imported writes are forbidden. The second replays two historical external amount=7 entries to 14,
then submits a local increment and checks 19 and its exact committed input. The example document
is `examples/owners/mixed-timelines.blue`. Its public source entries are fixed test fixtures; a
change to those entries intentionally fails the expected-value check.

External periodic polling requests the provider's freshest available frontier. Local provider
reads reuse valid coverage until new entries or demand require advancement. Both paths use the
published signed protocol; no remote coverage is inferred from the application's wall clock.


Timing field compatibility: the existing API's `executionNanos` includes root restoration,
admission and native invocation, and excludes result preparation and commit. The reproduction
report calls it `restoreAdmissionExecutionMillis`. Pure native work is the exclusive `execution`
phase in qualification JFR records. Earlier reports named this aggregate `engineMillis` or
`rootExecutionMillis`; those labels do not mean pure BEX/Contracts CPU time. Likewise the old
`nativeInitializationMillis` field includes initial preparation, declaration discovery and source
catch-up; current qualification names it `initialPrepareAdmissionMillis`.
