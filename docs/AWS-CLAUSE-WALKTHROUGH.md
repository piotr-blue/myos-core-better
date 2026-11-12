# Run Agreement → Order → Payment in your AWS account

Current implementation and acceptance: see [dashboard parity iterations](DASHBOARD-PARITY.md). The [older native latency report](NATIVE-LATENCY-CURRENT.md) is a retained baseline; the complete AWS millisecond target is still unmet.

Open [MyOS Better](https://d2ya9daeiur7nz.cloudfront.net) and sign in with your existing account.
No AWS CLI, local build or deployment is required. The latest native qualification also ran in
your account; use the report links to inspect it, or create a fresh document with the steps below.

## 1. Start the Agreement

1. Open **Documents** and click **Bootstrap document**.
2. Copy the complete [Core bootstrap payload](../examples/clause-tutorial/bootstrap-core.blue) into **Bootstrap payload**.
3. Replace both `REPLACE_WITH_YOUR_ACCOUNT_ID` values with **Your account** shown below the form.
4. This binds both `customer` and `seller` to your account and creates their participant Timelines.
5. Click **Bootstrap** once. If the request is interrupted, retry the same form; its command is idempotent.
6. Open **State**. Initially `paidTotal` is `0`, `orders` is empty and `activation.active` is `true`.
7. Open **Timelines** and note the two participant Timeline IDs.

This demonstration represents both participants with your account and uses `required: []`, so no
separate approval is needed. Cross-account/email invitation bootstrap is not implemented yet.

To start your own already-bound document instead, use **Start document** and paste
its YAML or JSON. That path uses its supplied Timeline bindings without provisioning replacements.

## 2. Create an Order through the Agreement's Documents clause

1. Open the Agreement's **Operations** tab and select `placeOrder`.
2. Keep the `customer` channel and its selected Timeline.
3. Enter this request in the operation input form (the equivalent JSON is `{"orderId":"order-1"}`):

```yaml
orderId: order-1
```

4. Click **Invoke operation** once. Entry acceptance confirms signed publication; wait for the processed result.
5. Open **Links**; refresh until `/orders/order-1` appears, then open it.
6. The Order has its own Activation and Payment clauses. Its **Timelines** must show the same two IDs.

Do not submit the same business action repeatedly while waiting. **Processing** shows receiver and
parent-delivery state; **History** and **Events** show committed outcomes.

## 3. Create the Payment Instruction

1. On the Order, open **Operations** and select `preparePayment`.
2. Keep the `buyer` channel; enter `{}` and call it once.
3. In **Links**, refresh until `/payment` appears, then open it.
4. Confirm that its **Timelines** still show the same two IDs.

## 4. Complete the Payment

Call these operations on the Payment, in order. Wait for each committed result before the next call.

| Operation | Channel | Request | Result to check |
| --- | --- | --- | --- |
| `acceptPaymentInstruction` | `guarantor` | `{}` | `payment.accepted: true` |
| `reportTransfer` | `payer` | the request below | `reportedTransfer` appears |
| `acknowledgeTransfer` | `guarantor` | the same request | `payment.completed: 200` |

```yaml
requestId: transfer-1
reference: bank-demo-1
```

This records a manual payment acknowledgment; it does not transfer bank funds. The amount is
200 EUR minor units, or EUR 2.00.

## 5. Verify propagation and history

Refresh **State** on each document:

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

The Payment emits a `PaymentInstruction/Payment Event` with kind `Manual Receipt Acknowledged`.
The Order's ordinary embedded-document listener emits `Order Payment Received`; the Agreement
reacts and emits `Agreement Payment Received`. These are Blue workflows, not Java example handlers.

On the Agreement, open **History → Epoch 0**. Its total remains `0`, including after a
browser reload. Use the epoch selector to return to the current state with total `200`. Use **Source** to compare the original
source with the input whose participant bindings bootstrap supplied. Follow a **Timeline** link to
inspect the original signed request, entry identity, timestamp and provider attestation.

## 6. Use an API key

Open **API Keys**, supply a name and create a key. Copy the secret once; it is hidden after dismissal
or reload. Keys act as your account and can call document and Timeline APIs. They cannot administer
other keys. Revoking a key rejects subsequent requests.

For a read-only check, put the secret in your local shell variable `MYOS_API_KEY`, then run:

```bash
curl --fail --silent \
  -H "Authorization: Bearer $MYOS_API_KEY" \
  https://d2ya9daeiur7nz.cloudfront.net/api/examples
```

The [complete tutorial](../examples/clause-tutorial/README.md) includes bootstrap and operation API
request shapes. The [source document](../examples/clause-tutorial/agreement-incremental.blue) is also available
locally; `bootstrap-core.blue` wraps that unchanged source for the original Core bootstrap form.

## Reproduce the measured journey

From `myos-aws-test-better`, run:

```sh
python3 scripts/reproduce_canonical.py --email piotr@bluecontract.com --orders 2
```

Enter your password at the prompt, or use `MYOS_API_KEY`. The script creates a fresh
agreement and two participant Timelines, completes both Payment workflows, and checks
all three totals and the shared Timeline bindings. It prints the resulting document URL.
Use `--orders 128 --report canonical-128.json` to repeat the larger growth scenario.

See [the retained baseline](NATIVE-LATENCY-CURRENT.md) and [current parity acceptance](DASHBOARD-PARITY.md) for measured timings.
The UI's **Submitted** response confirms signed publication, not business completion.
Engine time, durable commit, and remote client observation are reported separately.

## Use the original Timeline append form

Open a document and copy **Document ID**, not Session ID or State BlueId. Open a writer Timeline
from its **Timelines** tab. Click **Append entry**, paste that ID in **Document ID (required for
operation requests)**, and enter a native request in **Message (JSON or YAML)**. For the original
Shared counter template this is:

```yaml
type: Contracts/Operation Request
channel: alice
operation: increment
```

Click **Append message** once. The entry is signed and the ordinary input worker executes it.
Return to the document's **History** and **State**: the counter advances and the triggering entry
is retained. Authored requests may omit the duplicate native `document` field; exact or referenced
requests must already contain it. Delegated/imported write authority remains unavailable.

## Reproduce through the generated Core SDK

For an existing incremental Agreement, create an API key in its account, set `MYOS_API_KEY`, and
run from the repository root:

```bash
npm --prefix sdk/typescript ci
npm --prefix sdk/typescript run build
node scripts/reproduce_core_timeline.mjs \
  https://d2ya9daeiur7nz.cloudfront.net \
  YOUR_AGREEMENT_SESSION_ID \
  reports/core-timeline-journey.json
```

This adds one Order and one Payment, calls all five operations through the Timeline endpoint, and
checks Payment → Order → Agreement totals. It records preparation, admission, engine, durable
publication and observed client time separately. If a run stops, inspect the saved pending entry
before retrying; a new run creates another Order. Core requests use the unchanged generated SDK;
legacy read-only endpoints supply existing detailed timing and graph-value measurements.
