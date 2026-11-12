# Owner-local execution on AWS — 10 November 2026

## What this iteration changes

This carries the protected owner-local execution path from `blue-java-better` into the independent-owner DynamoDB host. It does not introduce a new authored Blue document model. A Payment, its Order and the Agreement remain ordinary Contracts documents, with the same canonical BlueIds as whole-document execution under the selected gas policy.

The application checkout is `myos-aws-test-better`; its Gradle distribution is named `myos-core-better`.

The native execution policy is `GasSchedule.ADDITIVE_PROTOTYPE`, persisted as `contracts-additive-prototype-3`. This is an explicit experimental accounting edition. Existing histories retain their own saved schedules. The deployed creation default remains `COMPOSITIONAL_V1`, so this deployment does not silently change the semantics of existing or arbitrary clause documents.

## Processing sequence

1. The timeline subsystem verifies an original signed entry and its completeness evidence. Existing durable scheduling selects its order, owning unit and historical source results.
2. An eligible owner restores its own protected checkpoint. Unchanged embedded owners remain content references; their execution bodies and native preparation are not restored.
3. The invocation still receives verifier-issued native Timeline admission evidence. A content provider is not permission to process an input.
4. The ordinary Contracts interpreter runs the selected owner's workflows. A parent attaches the selected child's prior/current BlueIds, events, gas observations and causal metadata delta.
5. The host writes only changed member, content, language-admission and operation-view records into persistent indexes. The original signed entry is retained too, because the resulting channel checkpoint refers to it after restart.
6. Immutable content is flushed before the existing conditional owner-manifest transaction commits the new head, history, receipt, progress and outbox. A failed CAS cannot publish partial state.
7. The next affected parent repeats this process. The benchmark requires exactly three applied owners: Payment, Order, Agreement.

History inspection is a separate operation: it may reconstruct the full graph for the dashboard or the ordinary fallback. The protected owner checkpoint identity and reconstructed full snapshot identity are deliberately distinguished and verified at that boundary.

The host also records a maximum of three native execution claims per additive-policy owner/input. Reopening storage or manual retry does not reset this bound. Exhaustion is a durable host diagnostic, not invented semantic gas.

## Scope and limitations

The fast path currently supports the library's eligible static, acyclic scalar-update workflows with at most one distinct selected source completion per owner invocation. Multiple paths to that same source are allowed. Arbitrary structural changes, multi-source combinations, owning cycles, termination and failures use the ordinary whole-graph path when required. They are not claimed to have the fast-path timings.

Initial creation still initializes and publishes the complete starting graph. Returning a complete expanded graph to a dashboard is also naturally larger for a larger graph. Neither is included in an operation-only timing.

The benchmark deliberately uses one shared signed public timeline for its Payments. The existing timeline acceptance/coverage machinery visits its consumers. This cost is measured separately and can grow with the subscriber count, even though only three documents execute. Removing graph restoration from document execution does not remove this separate subscription fan-out.

The Agreement's own shallow body and embedding metadata still become wider with more references. This implementation does not claim mathematical constant time, million-document qualification, production-scale account sharding, or complete clause-package eligibility. The coherent scheduling manifest is still shared by an account.

## Reproduction

From the application checkout, set `MYOS_API_KEY` to an API key for your account and run:

```bash
python3 scripts/qualify_owner_preview.py --orders 10,100 --samples 3
```

The script creates synthetic local timelines and an Agreement containing Orders and Payments on your account. It calls `record` on one Payment, waits for that exact entry in the Agreement's committed history, and verifies Payment, Order and Agreement totals. Each call adds 42. It checks idempotent retries and verifies that the root history reports `owner-local` execution. Output includes document handles and a report path. Without an API key, the maintainer version uses AWS credentials to create isolated suppressed-email QA users.

For the dedicated storage qualification:

```bash
./gradlew installDist qualificationJar
python3 scripts/qualify_owners.py   --stack myos-better-canonical-20261108   --orders 10,100,1000 --samples 5 --cold   --gas-schedule ADDITIVE_PROTOTYPE
```

This creates and terminates an isolated AWS worker. It does not benchmark on the live console host. It uses the configured AWS account and a unique test-data namespace. The whole-document oracle runs after the timed measurements using independent storage.

## Verification and measurements

The sequential full build passed: 2,536 library tests and 830 AWS application tests, with zero failures or skipped tests. Strict Java compilation, formatting, Javadoc and qualification compilation passed. A further 13 selected `myos-simplified` local-execution/composition tests passed against the updated library.

The isolated AWS worker replayed all 15 measured inputs with a separate whole-document interpreter. Every final document BlueId, gas total and ordered public event occurrence matched. Every measured input committed exactly three owners. The AWS probe repeatedly targets the first Order; the local restart/parity tests also choose different Order indices. Each cost 228 gas in this public-timeline fixture. The oracle ran after timing, and the probe explicitly rejected silent fallback from the owner-local path.

### Actual DynamoDB measurements

Dedicated c7i.xlarge in eu-west-1, Corretto 25, two active processors, 1 GiB Java heap, 1.5 GiB service memory ceiling. Five samples at each size. Transport caches were disabled, and the owner storage/processor was reopened for every input. Common immutable package/type data and the JVM remain available; this is not a new JVM for every operation.

| Embedded Orders | Independent documents | Initial creation | Median changed-owner processing | Median timeline acquisition | Median acquisition + processing |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 21 | 1,327 ms | 680 ms | 179 ms | 837 ms |
| 100 | 201 | 2,534 ms | 568 ms | 305 ms | 873 ms |
| 1,000 | 2,001 | 34,785 ms | 661 ms | 1,823 ms | 2,478 ms |

The combined column is the median of each sample's sum, not the sum of two medians. Processing ranges were 622–770 ms, 545–610 ms and 634–687 ms respectively. These are server-side storage measurements; they exclude public HTTPS transport, client polling and the separate comparison oracle.

The local in-memory storage-model medians for the same fixture were 41.2, 28.8 and 41.8 ms. They are not DynamoDB or SQLite measurements and must not be presented as AWS latency.

At 1,000 Orders the median measured processing I/O includes 152 individual immutable-content calls, 30 batched-content calls, 36 mutable-record reads, six conditional commits and six content-publication calls. Their median elapsed components are approximately 265, 67, 69, 109 and 58 ms respectively. Per-sample totals show storage dominates the processing time. There are three business executions, not 2,001; the durable adapter still makes too many sequential network accesses.

Timeline acquisition has a separate scaling problem: `OwnerFrontiers.accept` visits the timeline's consumers, and external `acquire` updates their coverage. The native business-work optimization does not eliminate these loops. The shared external-timeline fixture makes that cost visible. This report does not claim end-to-end latency is independent of subscriber count.

### Public HTTPS and local MyOS timelines

The deployed API completed three repeated Payment operations at both 10 and 100 Orders, with unique Payment identities. All Payment/Order/Agreement totals reached 126, all root histories reported `owner-local`, and repeated identical idempotency keys returned their original submission without double execution. The ordinary private timeline access checks remained enabled.

Client-observed median submission-through-Agreement-history times were **1,560 ms for 10** and **1,575 ms for 100**. These include the client's network round trips and polling. Initial creation was 1,203 ms and 4,019 ms. The fixture uses private native channels and a slightly different event payload, so its gas is 224 rather than the public probe's 228. Its three affected documents' business results were checked after each operation; exact whole-versus-split event/gas parity is established by the independent DynamoDB probe above.

A fully inline 1,000-Order private-channel request was rejected with HTTP 413 before execution: it repeats complete timeline and permission documents and is approximately 3.7 MB, exceeding the existing 1 MiB JSON request limit. Compact JSON alone remains approximately 3.55 MB. No 1,000-Order public-HTTP success is claimed. The separate DynamoDB probe did execute 1,000 Orders successfully. Proper content-addressed ingestion/shared definitions should address the upload boundary instead of conflating it with execution speed.

Distinct instances require distinct identities when addressing an operation by BlueId. The first draft of the HTTP fixture accidentally authored identical Payments, so an identity-addressed operation legitimately addressed multiple copies. The final fixture includes a unique Payment key; the engine was not changed to hide this authored identity collision.

### Existing published-clause compatibility

The normal `COMPOSITIONAL_V1` bootstrap succeeded on the initial deployment of this iteration, before the private cold-grant recovery fix. The final build passed the full application suite and the private-channel HTTPS/restart checks described above. `placeOrder`, `preparePayment`, `acceptPaymentInstruction`, `reportTransfer` and `acknowledgeTransfer` all committed; the Agreement reached `paidTotal: 200`. Client-observed times were 4,821, 5,512, 4,702, 4,538 and 4,541 ms respectively. This is a compatibility check, not evidence that arbitrary published-clause workflows now use the owner-local path. Anonymous access, cross-origin writes, account isolation, creation retry, native submissions and API-key creation/read/revocation were checked by the existing qualification script.

### Deployment

The tested distribution is deployed to **https://d2buikczdox1rt.cloudfront.net** on stack `myos-better-canonical-20261108`, instance `i-052007ebaf704c0ad`. SSM deployment completed successfully and the health check reported durable canonical processing, native admission, two workers and the preserved compositional creation default.

Artifact SHA-256: `bd8bec84c288aae90e63d29ecb23d035ecb1802423c83dde85277588f9526fcc`.

Deployment command: `bbaa0cdb-84a1-47cd-9021-d0fd03b88aa8`.
Qualification command: `a541baaf-b300-42bc-9da2-bdfe7bd52bf7`.
The temporary qualification instance was terminated after its successful run.

Use `gasSchedule: ADDITIVE_PROTOTYPE` in the creation request to select this new policy. The reproduction script does that explicitly. Saved policies are part of creation idempotency and cannot be silently changed by retrying a command.

### Cold-restart defect found and fixed

The first live restart check found a missing-resource error for a private-access grant. The record existed in DynamoDB. `TimelinePermissions` copied already-opened Language evidence into a new reader, but that did not retain the native owner's lazy provider. Warm execution hid the defect. The corrected code selects and exports only the declared grant's closure while its resolver is alive, then performs the same strict read and historical permission verification. It does not export unrelated Orders or grant authority merely because content exists. A cold-checkpoint regression forbids reads of an unrelated sibling; existing revocation and historical permission tests still pass.

The same check exposed a scheduling diagnostic problem: a root waiting on a temporarily deferred child could appear idle. A bounded walk of only the pending minimum's causal dependencies now preserves the wait/retry state. An unrelated graph remains independent; the three-attempt durable bound remains enforced.

After redeployment, the original failed input was still retained. It and a new input processed in order, bringing Payment, Order and Agreement totals to 210 at version 6. No stored document or timeline was edited to repair the result.

A second actual service restart then succeeded. A new input advanced version 6 to 7, with all three totals equal to 252, gas 224 and `owner-local` execution. The first client-observed operation after this restart took **7,257 ms**. This is a whole-process restart, unlike the storage-cache benchmark above. Its startup, native access-history, source refresh and network costs have not been individually isolated, so no precise attribution or fast cold-start claim is made.

The final build was requalified on a fresh isolated AWS worker. The table above and `aws-cold.json` refer to that final build. `aws-cold-initial.json` retains the earlier successful storage probe; it did not exercise the private cold-grant path that exposed the defect.

### Assessment and next engineering work

This deployment establishes the owner-local execution path and parity on real AWS storage. **It does not yet achieve local-runtime latency on AWS, or flat end-to-end ingestion latency.** Initial creation and the unchanged full-clause path are separate costs, not hidden fast-path successes.

The next work should target the measured host bottlenecks:

1. Pack the selected owner's checkpoint and required immutable records into a small number of reads, and batch remaining index/content lookups. Preserve laziness for unrelated children and all current admission checks.
2. Index operation-target consumers and observers so known targeted originals do not refresh every unrelated subscriber. Preserve the conservative path for unknown classification, broadcasts, changed subscriptions and retrospective catch-up.
3. Replace external completeness propagation fan-out with a shared coverage dependency/index where possible, while maintaining the exact timestamp/tie ordering and coherent readiness cut. Do not replace completeness evidence with a wall-clock assumption.
4. Provide bounded content-addressed document ingestion for large authored graphs and shared timeline/access definitions.
5. Qualify structural edits and cycles separately before expanding eligibility or changing the default creation policy. Preserve whole-interpreter equivalence rather than forcing unsupported work through a faster path.

Raw bounded measurements are retained alongside this report. QA credentials remain in the private reports directory and are not included here. No GitHub operation was performed.

