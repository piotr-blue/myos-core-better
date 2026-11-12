> UI update (iteration 135): the special **Payment flow** widget and automatic **Run order/payment**
> endpoint have been removed. Existing documents still run through **Operations**; use **Links**
> to open their orders and payments, and **History** to inspect changes. Earlier widget instructions
> below describe the measured historical release, not the current console. Measurements are unchanged.

# Follow a payment through 512 documents

Open [your agreement](https://d2ya9daeiur7nz.cloudfront.net/#documents/demo-c68fc7e1eac59606529f12f9aadd4e306eb1b9c6a99a5458d930dc75d4693f63)
in the account that created it. Refresh the browser to load the updated console.

The original agreement was extended with 127 orders, each containing three payments. It now contains
one agreement, 128 orders and 383 payments: **512 independently processed documents**. The original
order still has its two payments. Growth took 37.6 seconds through the public HTTPS API with three
concurrent requests; this includes document initialization, storage and network travel.

## Run it yourself

1. Open **Payment flow**. Select an order and payment. **Load more orders** pages through all 128 orders.
2. Click **Record 42 and follow changes**. This records another demo payment; it can be repeated.
3. Watch the three cards: payment `recordedAmount`, order `paidTotal`, agreement `paidTotal`.
   Each increases by 42. The view also checks that each parent has received the selected child's version.
4. Open the order or agreement and select **History** or **Events** to inspect the retained events.
   History starts with the newest version; **Inspect version** opens an immutable historical state.
5. Choose another order and repeat. Payment flow reads only the selected chain and direct embedding
   references. It does not expand the whole agreement.

The actual workflows emit:

```text
Payment.record → Payment Settled(amount:42)
              → Order receives it, adds 42, emits Order Paid(amount:42)
              → Agreement receives it, adds 42, emits Agreement Payment Received(amount:42)
```

**Operations** now offers the original MyOS-style operation cards and request dialogs. The channel is
selected automatically, optional request shapes are visible, and requests remain editable YAML/JSON.
`addOrder` provides a complete order/payment example with a fresh key. `settle` can be called once per
payment and rejects a second settlement; `record` is the repeatable demonstration operation.

A lost browser response retains the original command and comparison state. Retrying that pending
payment uses its original command ID; a deliberate click after known success creates a new command.

## Recorded browser example (release106)

The recorded user-account browser run selected `/orders/demo-128` and `/payments/p0`:

| Document | Before | After |
| --- | ---: | ---: |
| [Payment 1 of Order 128](https://d2ya9daeiur7nz.cloudfront.net/#documents/doc-4cd7e38381a600b7033dbb2916e520fe3e9e21d0), `recordedAmount` | 0 | 42 |
| [Order 128](https://d2ya9daeiur7nz.cloudfront.net/#documents/doc-527493d3ae920e05b1052afc39a871096b74a628), `paidTotal` | 0 | 42 |
| Agreement, `paidTotal` | 4914 | 4956 |

Both parents' observed child versions advanced, and the agreement reached version 247. These are
the recorded test values; further operations will increase them. The accumulated agreement total
includes earlier benchmark and browser calls, not just this last payment.

The screenshot captured a **67.35 ms payment runtime**, a **481 ms browser payment response**, and
**1063 ms until the browser observed all updates**. This run followed deployment106. It is
separate from the warmed, server-local samples below; those medians are not a promise that every
browser click completes in 100 ms. Browser completion also includes HTTPS travel and follow-up reads.

## Measurements on this agreement

These measurements call the running service over loopback HTTP on its AWS host, with DynamoDB
publication and normal background workers. They exclude intercontinental browser network travel.
The matching before/after runs use 16 warmups and 32 samples on the original two payments. The random
run samples 16 distinct payments from 16 different orders, seed 1729, with no dedicated warmup.
Every measured call checks the payment, order and agreement totals.

| Scenario | Payment runtime median | Durable payment response median | Agreement completion median | Agreement completion p95 |
| --- | ---: | ---: | ---: | ---: |
| Before: 1 order, 4 documents |4.50ms|26.31ms|127.14ms|154.83ms|
| After: 128 orders, 512 documents; same payments |2.18ms|21.57ms|102.67ms|124.03ms|
| After: 16 randomly selected orders |1.82ms|25.34ms|118.42ms|178.43ms|

No size-related slowdown appeared in these samples. Faster numbers after growth do not prove that
larger graphs improve performance: JVM warmup and cloud variation can explain the difference. This
is a bounded functional/latency experiment, not a throughput SLA. Root aggregation remains serialized
when multiple payments update the same agreement.

Runtime covers the leaf runtime call, not checkpoint loading, durable publication or parent work.
Durable response includes the complete local HTTP call. Completion includes polling until the
agreement's expected total is visible. The console separately labels browser-observed timings,
which include network travel and state reads; those will be higher.

The paired measurements used deployment 104, artifact
`7b0375ea1eaf9f5d40e482ea8bf371e96691322f305f71dab634dd0a6a8125fd`.
The console update changes presentation and adds a direct embedding read; it does not change the
execution/storage algorithms measured here. Raw samples, inventory, browser evidence and release
pins are retained in `reports/overnight-2026-11-05/user-demo-106/` and manifest 106.

## Timelines and document history

History and Events show durable **document versions and emitted events**. The new **Timelines**
section separately supports private Timeline creation, signed YAML/JSON message append, and original
entry/attestation browsing. See [hosted Timelines](HOSTED-TIMELINES.md) for the account and key boundaries.

The payment walkthrough and Operations continue to run through the host-links runtime. Appending a
message in the Timeline screen does not yet call a document operation. Automatic public Timeline
acquisition and durable scheduling are separately qualified; the ordinary service's runtime manifest
still enables neither native admission nor Timeline processing. Release113 provides signed private
reader access and executable grants/revocation through the native API. Release120 adds the **Sharing** tab for public-key grants, current access inspection and revocation.
Native source adoption for business documents remains separate work.


## Earlier deployment check (iteration 128)

The current deployment retains this walkthrough and was verified again in the browser. Your original
512-document agreement remains at version 247 and paidTotal 4956 after a read-only inventory check.
The browser test used separate QA documents and exercised payment propagation, operation dialogs,
immutable History, Events, Timeline creation/append/content, and Sharing grant/revoke/regrant.
Its payment showed 6.03 ms runtime, 279 ms browser response and 583 ms until all updates were visible.
That single small-graph QA sample is separate from the 128-order measurements above.

Iteration 128 publishes small embedding-page sets with their conditional document updates. In the
paired AWS campaign, recovery of 64 committed payments took 3.17–3.33 seconds before and 2.50–2.85
after, about 18% shorter on average. Transactional page writes consume additional capacity; unhinted
single-payment timings were mixed. See [the comparison](PERFORMANCE-CONTENT-PUBLICATION-128.md).
The separately configured automatic Timeline directory also passed its AWS restart check. Creating
or appending a Timeline in the ordinary console still does not automatically call a business operation.


## Latest deployment check (iteration 132)

The updated library is deployed and the full console/browser qualification passes. Your agreement
was verified read-only at version 247, paidTotal 4956, with the same 128 orders/383 payments. Use the
Payment flow instructions above to call it; each successful repeat adds another 42 to the chosen
payment and its two parents.

A separate fresh QA graph showed 6.63 ms payment runtime, 248 ms browser response and 527 ms until all
three totals were observed. All three cards moved 84→126, and parent-observed versions were checked.
The AWS fixed-working-set comparison measured 112–124 ms payment-to-root completion with 128 orders
and 107–116 ms with 20,128; the same 16 active orders were used in each. These are server-side worker
measurements, excluding browser travel and untimed inventory, not a guarantee for every click.

The serializer now avoids temporary export trees while preserving exact checkpoint bytes. Local
export allocation fell 30–41%; whole-transition results were mixed, so no general speedup is claimed.
See [the full qualification](PERFORMANCE-EXACT-SERIALIZATION-132.md) for raw-evidence references,
limits and deployment details. Operation dialogs, History/Events, Timeline creation and signed
content inspection, and Sharing were all rechecked. Timeline append still does not automatically
call a business operation in the ordinary console.
