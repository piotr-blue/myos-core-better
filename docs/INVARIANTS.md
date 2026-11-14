# Invariants at the durable host boundary

The host selects trusted evidence, orders inputs and publishes the native result. It must not
invent a second meaning for the authored document. Use this map with the [architecture](ARCHITECTURE.md)
and [test guide](TESTING.md) when changing a persistence, scheduling or API boundary.

| Boundary | Required invariant | Implementation and regression evidence |
| --- | --- | --- |
| Conditional publication | Head, receipt, consumed source position and scheduled work become visible together. An exception can mean an unknown commit outcome; retry by exact command identity. | [AtomicStore](../src/main/java/myos/better/AtomicStore.java), [competing commands](../src/test/java/myos/better/CoreConcurrencyTest.java), [process recovery](TESTING.md#recovery-across-process-termination) |
| Immutable bodies | A caller cannot alter a committed row through a retained nested map or list. Invalid later writes cannot partially publish earlier writes. | [Publication tests](../src/test/java/myos/better/MemoryPublicationTest.java) |
| Input ordering | The next original requires a complete prefix from each current source. A timeout or inaccessible provider cannot prove that no predecessor exists. | [Owner frontiers](owner-frontiers.md), [timeline source tests](../src/test/java/myos/better/OwnerTimelineSourcesTest.java) |
| Historical embedding | A parent reads the child version and generation bound to its source cut. Current child heads are not historical evidence. | [Source binding preparation](../src/main/java/myos/better/OwnerSourceBindings.java), [document lineage](DOCUMENT-LINEAGE.md) |
| Retry preparation | A losing attempt discards tentative mutable state. External delivery does not run inside a transaction retry loop. | [OwnerGraphs](../src/main/java/myos/better/OwnerGraphs.java), [architecture](ARCHITECTURE.md#how-a-duplicate-create-receives-the-original-reply) |
| Gas authority | Reservation, native claim and publication are fenced together. A settled/released claim leaves no active permit; replay cannot charge twice. | [Claims](../src/main/java/myos/better/OwnerGasClaims.java), [claims tests](../src/test/java/myos/better/OwnerGasClaimsTest.java), [recovery tests](../src/test/java/myos/better/OwnerGasRecoveryTest.java) |
| Work fairness | Recovery uses bounded passes. Paused work cannot keep a funded FIFO position ahead of runnable work. Competing workers honor the committed retry time. | [Gas work tests](../src/test/java/myos/better/OwnerGasWorkTest.java), [gas recovery tests](../src/test/java/myos/better/OwnerGasRecoveryTest.java) |
| Session cache | Loading reserves the document key; a concurrent child handoff cannot install a second session. Released leases cannot expose an entry later acquired by another caller. | [Session cache](../src/main/java/myos/better/SessionCache.java), [cache tests](../src/test/java/myos/better/SessionCacheTest.java) |
| Shutdown | Attempt every cancellation stage, preserve the first failure and suppress later ones. Do not close storage while workers can still use it. | [Runner shutdown tests](../src/test/java/myos/better/TimelineRunnerShutdownTest.java), [owner shutdown tests](../src/test/java/myos/better/OwnerWorkerShutdownTest.java) |
| Authentication | Claim checks use an explicit clock. A token that expires during key retrieval is not accepted; malformed claims do not trigger unnecessary provider access. | [Identity tests](../src/test/java/myos/better/ConsoleIdentityTest.java) |
| Signing custody | Stored key name, identity and immutable version must agree. A lost creation reply can recover the same key without issuing another authority. | [Custody tests](../src/test/java/myos/better/ReaderCustodyTest.java), [secret tests](../src/test/java/myos/better/SigningSecretTest.java) |
| SDK completion | Async waits share the original deadline and bind the reviewed document, operation, account and admission. An unrelated success cannot satisfy the wait. | [TypeScript helpers](../sdk/typescript/helpers), [SDK tests](../sdk/typescript/tests) |
| Storage JSON | Parser reuse cannot retain a growing global field-name table. Numeric precision, duplicate rejection, limits and byte-decoder behavior remain unchanged. | [JSON reader](../src/main/java/myos/better/Json.java), [reader tests](../src/test/java/myos/better/JsonReadTest.java) |
| Deployment | Runtime, package catalogue, console assets and environment are one attributable release. Preserve compatible readers of retained public definition roots. | [Public definitions](PUBLIC-DEFINITIONS.md), [operations](OPERATIONS.md), [catalogue lock](../infra/runtime-catalogue.lock.json) |

## Make time and failure observable

Following Caffeine's testability principle, use a controllable clock for logic and an explicit
synchronization point for overlap. Sleeps may pace a longevity workload; they should not be the
proof that a race occurred. A cancellation request and a completed worker join are distinct
outcomes. An emulator crash test proves the selected recovery boundary, not multi-AZ availability.
[Caffeine testing](https://github.com/ben-manes/caffeine/wiki/Testing)

Treat preparation as speculative until its conditional publication succeeds. This is the same
external-effect discipline described in FoundationDB's retry guidance; the actual transactions
here are DynamoDB/`AtomicStore` transactions, not FoundationDB transactions.
[FoundationDB developer guide](https://apple.github.io/foundationdb/developer-guide.html)

## Keep qualification comparable

A result needs exact executable hashes, package profile, data shape, operation schedule, CPU,
heap, collector, cache mode and timing boundary. Preserve raw failures and slow samples. A
constant three-owner cascade or gas debit is not a guarantee that root metadata and content
identity work stay constant as the graph grows. Native parity establishes semantic agreement;
it does not establish independent specification conformance or a production latency SLO.
