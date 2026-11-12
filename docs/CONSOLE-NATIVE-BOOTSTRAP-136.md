# Console and clause bootstrap iteration 136

Decision: keep the verified implementation. Deployment/browser results are recorded separately in `reports/overnight-2026-11-05/manifest-136.json`.

## What changed

The console uses the runtime's effective operation registry, including inherited custom operation types. Native operations publish a signed Operation Request on the selected owner Timeline. Actor and timestamp are assigned by the provider; target identity is checked against the document's published lineage. Submission and business execution remain distinct outcomes.

Creation has two explicit modes: initialize supplied YAML/JSON, or bootstrap participant bindings. Bootstrap discovery is pure and understands inherited Activation parties. Provisioning is resumable through deterministic Timeline, reader and document commands. Existing owner Timelines can be selected; two roles may reuse one source. Invalid/foreign mappings are rejected before resource creation. Authored source and bound source are retained separately.

API keys use the existing owner-scoped implementation: one-time disclosure, hashed persisted credentials, bounded expiry, revocation, and no API-key administration through a bearer key. Console history links immutable input/outcome records; exact local state avoids flattening inherited programs merely to render the page. Package type names come from explicit runtime mounts.

The host package registry now exposes explicitly deployed public definition records. They are shared content, not authorization. New immutable Timeline packets retain a pinned content-index root rather than duplicating a large closure in every packet; old packets remain readable. An old indexed packet cannot read private records published only under a later root.

## What the clause journey exposed

1. Pure local reads were routed through a JSON projection. This discarded exact identity information in partially specified definitions. Local reads now preserve their captured exact content.
2. Activation compared an exact local Timeline with a projected embedded access document. Exact pinned reads preserve nested identities on both sides.
3. Documents/Activation deliberately embed initialized Timeline Access references in their children. Identity records do not prove execution history. The reader can now explicitly supply an authenticated detached checkpoint; the runtime verifies the edition, content and selected identity, then forks it without replaying initialization events. An unrelated or unavailable checkpoint cannot authorize an import.
4. Parent listeners read a selected child before selecting its fields. Eager projection expanded the child's inherited programs and exceeded the host output budget. The host-link edition now composes embedded views lazily. Ordinary field reads remain local; keys/whole-collection reads can still do work proportional to what is selected.
5. A fresh source notification could race with failure publication, causing the same failed entry to be retried repeatedly. Failure blocking is now fenced against the document version and explicit retry generation; a new source prefix alone cannot erase it.

6. Archive acquisition omitted explicitly shared package definitions from durable packets, but replay omitted the corresponding shared lookup. A newly constructed archive now uses the same verified public catalogue; a regression recreates the archive before reading typed content.
7. An authenticated fork preserved a native channel checkpoint without its original-source order record. The host now carries a compact, immutable checkpoint-origin fact beside the fork's initial publication. Normal history is validated first; inherited facts are bound to the exact initial document, account and event timestamp. The restart test confirms that the old input is skipped and a later input executes independently.
8. Control sources required for private-access coverage were being consumed one entry at a time by business documents with no channel on those sources. Input selection now retains their verified coverage while delivering only declared sources. The access verifier still replays the grant history at the original input time. Tests retain revocation, restoration, union-of-readers and incomplete-coverage checks.

9. A new private reader replayed thousands of empty control polling acquisitions. Archive replay now retains a bounded, disposable index of verified quiet runs; access replay reads a bounded batch of relevant acquisitions and verifies the last signed checkpoint of each quiet run. It never skips real entries or permission changes. Cache loss reconstructs the index from verified source history. Pinned source cursors use the same bounded index to avoid a DynamoDB read per idle poll; a regression retains an older source view while a later entry arrives. The library also avoids creating a Contracts execution context when the verified suffix has no entries.
10. Old experimental forks created before checkpoint-origin preservation have missing historical facts. They now become visibly blocked instead of retrying continuously. Their authority is not fabricated; explicit repair remains necessary for those old test documents.

Older document editions keep their existing host projection behavior. The new runtime remains an experimental host-local protocol; this is not a claim of equivalence to full global Coordination proofs.

## Generic implementation boundary

Agreement/Order/Payment behavior is entirely in `examples/clause-tutorial/agreement.blue` and pinned package definitions. Java implements exact content, runtime dispatch, signed source ingestion, authenticated checkpoint imports, durable local transactions and parent delivery. The bootstrap adapter recognizes an exact Activation clause identity; it does not branch on an example name, Order path or Payment operation.

The tutorial reuses exactly two participant Timelines across Agreement, Order and Payment. Access documents are verified forks with their own processing state; reuse of a Timeline does not imply sharing one mutable Java object or copying another document's authority.

## Verification and limits

The full library suite passed 2,369 tests, including pinned-import provenance and local identity regression checks. The final full host suite passed 403 tests, including packet formats, archive replay, fork provenance, private access and the real clause journey. Frontend compilation and both frontend tests passed; 34 deployment/qualification script tests passed.

The real signed-input tutorial asserts that Payment completion, Order paid total and Agreement paid total all reach 200. The browser qualifier additionally checks owner forms, operation dialogs, Timeline reuse and historical-version navigation.

This does not establish complete original MyOS feature parity. Remaining areas include cross-account/email invitation bootstrap, additional representative/agent provisioning, the original Terms/Timers controls, richer lifecycle/redrive controls and distributed production qualification. Owner bootstrap deliberately refuses unsupported foreign participant locators rather than silently replacing them.

This iteration does not establish constant-cost native source ingestion for large networks: a shared Timeline still wakes its subscribed receivers. Indexed addressed routing is separate remaining work. Nor does it establish large-network throughput from a three-document tutorial. Use recorded browser and processing measurements as bounded observations, not a production SLA.

## Delivery qualification result

The final build passes general console and API-key browser acceptance. The pre-final resumed native
clause journey reached all three totals of 200 with historical zero retained. However, the final-build
fresh journey took about 73 seconds to create the Order, exceeded 90 seconds while waiting for Payment
creation, and a resumed run exceeded 90 seconds waiting for acknowledgment propagation. The full
native-clause flow is therefore not accepted as fast or production-ready. See the final diagnostics
and manifest; functional test success does not override this measured AWS limitation.

Final diagnostic: Payment had committed acceptance and transfer reporting, but was waiting for
coverage of its seller/guarantor Timeline. Both parent propagation queues were current, with zero
pending versions. The observed final blockage was before Payment execution, not delayed delivery
of a completed Payment event to its parents.
