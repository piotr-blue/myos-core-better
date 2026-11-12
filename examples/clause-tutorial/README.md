# Agreement → Order → Payment, on two Timelines

This example uses the pinned Activation, Documents and Payment clauses. The host has no Agreement, Order or Payment branches. The complete source is [agreement.blue](agreement.blue); `packages.json` records the exact publications and archive hashes.

For a click-by-click AWS walkthrough, see [AWS-CLAUSE-WALKTHROUGH.md](../../docs/AWS-CLAUSE-WALKTHROUGH.md). The full native flow currently has startup and scheduling latency; functional acceptance is not a claim of low end-to-end latency.

The shipped `runtime-console.json` enables native timers. Run it with `MYOS_OWNERS_FAST=true`.
Its Time publication matches the edition imported by these business packages; runtime discovery
includes the Time protocol and specification identities. The separate unrestricted-context Time
edition remains available through its own explicitly selected runtime profile.

## Start it in the console

1. Sign in at https://d2ya9daeiur7nz.cloudfront.net.
2. Open **Documents → Bootstrap document → Clause tutorial**.
3. Select **Bootstrap participants**, then **Inspect participants**.
4. Leave `customer` and `seller` on **Create new Timeline**, or select your existing Timelines.
5. Press **Bootstrap**. The document's Activation clause checks the supplied access configuration. This teaching example has `required: []`, so it deliberately requires no separate participant approval.

Both roles are represented by your signed-in account in this walkthrough. The Core bootstrap API
also supports collaborative account participants, registered-email resolution, typed Parties and
Activation reader groups. New-email provisioning and representative/Agent writers remain pending.
Participant mapping does not grant another person's consent.

To initialize a document whose participant channels and reader access are already supplied, select **Initialize supplied document** instead. It sends your YAML/JSON directly to document initialization. It does not provision replacement Timelines.

**Source** preserves the authored input and, for bootstrap, separately exposes the bound input. **State** displays exact local content with readable scalar/type presentation; it does not eagerly inline every inherited clause program. **Links** shows independently stored embedded documents. **History** preserves versioned input/outcome and events, and allows opening earlier state.

## Create the Order

On the Agreement's **Operations** tab, choose `placeOrder`. The console selects the `customer` channel and its writable Timeline. Enter:

```yaml
orderId: order-1
```

Press **Call operation**. `Submitted` means the request has been published on the Timeline. Processing is asynchronous; use History/Events to inspect its outcome. Open **Links → /orders/order-1** after the Order appears.

The Documents clause constructs the Order, maps `buyer → customer` and `supplier → seller`, and initializes its Activation and Payment clauses. It reuses the participant Timelines; it does not create two more.

## Prepare and complete its Payment

On the Order, invoke `preparePayment` through `buyer`, with `{}`. Open **Links → /payment**.

This is a manual receipt Payment Instruction. It records participant assertions; it does not move money through a bank.

Invoke these operations in order, waiting for each outcome before the next:

| Document | Operation | Channel | Request |
| --- | --- | --- | --- |
| Payment | `acceptPaymentInstruction` | `guarantor` | `{}` |
| Payment | `reportTransfer` | `payer` | request below |
| Payment | `acknowledgeTransfer` | `guarantor` | the same request |

```yaml
requestId: transfer-1
reference: bank-demo-1
```

The Payment maps `payer → buyer`, and both `payee` and `guarantor → supplier`. Consequently, all three business documents use the same two Timelines.

After acknowledgment:

```yaml
# Payment
payment:
  accepted: true
  completed: 200
  transactionId: manual:transfer-1

# Order
paidTotal: 200

# Agreement
paidTotal: 200
```

The amount is in minor units: 200 EUR minor units = EUR 2.00.

The Payment emits `PaymentInstruction/Payment Event` with `kind: Manual Receipt Acknowledged`. The Order's ordinary Embedded Node Channel receives it, updates `paidTotal`, and emits `Order Payment Received`. The Agreement receives that event, updates its own `paidTotal`, and emits `Agreement Payment Received`. These listeners are in the source document, not Java.

Open **Timelines** on each document to confirm the same Timeline IDs. Follow a Timeline to inspect the original signed request and entry. Use **History → Inspect version 1** to see the initial zero total, then **Latest state** to return to 200.

## Use an API key

Create a key under **API Keys**. Copy the secret when it is shown; the server will not disclose it again. Supply it as `Authorization: Bearer ...` to ordinary document/Timeline APIs. A key acts as its owner and cannot create or revoke API keys. Revoke it from the console when finished.

For example, after putting the key in your local shell's `MYOS_API_KEY` variable:

```bash
curl --fail --silent \
  -H "Authorization: Bearer $MYOS_API_KEY" \
  https://d2ya9daeiur7nz.cloudfront.net/api/examples
```

A participant bootstrap request is `POST /api/bootstrap` with an `Idempotency-Key` header and this shape:

```json
{
  "source": "<contents of agreement.blue>",
  "participantMappings": [
    {"channelName": "customer"},
    {"channelName": "seller", "timeline": "<existing Timeline blueId>"}
  ]
}
```

Omit `timeline` to create one. `POST /api/bootstrap/preview` discovers the required roles without creating resources. `POST /api/documents` with `{"source":"..."}` performs direct initialization.

`GET /api/documents/{id}/operations` returns the runtime's operation declarations, receiving channels and current target identity. To publish an operation, use `POST /api/documents/{id}/operations`, with a fresh `Idempotency-Key` for a new action:

```json
{
  "timeline": "<customer Timeline blueId>",
  "channel": "customer",
  "operation": "placeOrder",
  "request": {"orderId": "order-1"}
}
```

Retry the same action with the same key and unchanged body after a lost reply. A successful submission returns `SUBMITTED`; it is not a claim that the document accepted the business request. The provider assigns actor and timestamp. An optional `target` plus `requireExactDocumentVersion: true` requires that exact published document version.

## Verification

`ClauseTutorialTest` executes the complete journey through the signed Timeline provider and automatic input worker, including private-reader access. It asserts both parent totals, Payment completion, and reuse of precisely two participant Timelines. `DocumentBootstrapTest` covers inherited roles, preview without provisioning, owner validation, retries, existing-Timeline reuse, retained authored source and fully bound direct initialization.

`core-ui/scripts/qualify-clause-tutorial.mjs` runs the same journey against the deployed console, through its real forms and operation dialogs, and records URLs, states, events and wall-clock timings. Its results belong to the deployment report, not to a claim of production performance at scale.
