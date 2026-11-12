# Compatibility and audit boundary

This candidate comprises `blue-java-better`, the local SQLite model `myos-simplified`, and the DynamoDB application whose checkout is `myos-aws-test-better` and Gradle name is `myos-core-better`.

## Authored documents versus storage

Authored documents use the bundled Language, BEX, Contracts and Coordination packages. Embedded state remains ordinary Blue content and BlueId references. `OwnerFrontiers`, local checkpoints, content pages, dependency sets, owner aliases, claims, outboxes and receipts are private host/runtime records. They are not new authored `Core/*` types, and the dashboard API is not an alternative document language.

For eligible static scalar changes the host restores the selected owner, attaches a selected child's protected completion and runs native Contracts. It publishes only changed records. Cycles are native strongly connected owning units; they use atomic whole-unit execution, not the scalar fast path. Structural edits, unsupported combinations and failed local invocations use the ordinary fallback. No claim is made that arbitrary published clauses or cyclic/structural workloads have the scalar workload's latency.

## Gas and equivalence

The new path explicitly selects `ADDITIVE_PROTOTYPE` (`contracts-additive-prototype-3`). Existing histories keep their saved schedule. The live creation default remains `COMPOSITIONAL_V1`; scripts select the new schedule for new test graphs. This is an intentional accounting change, not a claim of identical gas to the original `blue-java` under a different edition.

The accepted additive comparison boundary is canonical document BlueId, gas, and ordered public event occurrences. A whole graph and its split execution can have different physical host receipts and storage evidence. Cache hits, expansion/collapse and storage representation must not change semantic gas. Attempt limits and storage/time/memory caps remain separate host protections. Full tariff and overflow/rollback tests are in the library, alongside `docs/additive-gas-v2` and the later owner-local documentation.

The native parity oracle deliberately has independent storage and no protected source-result reuse. It is still the same Blue interpreter. This catches partitioning, delivery and persistence discrepancies; it does not independently validate every interpreter rule against another implementation. The new accounting edition still requires specification review.

## Ordering and independent owners

Entries are ordered by timestamp, timeline identity and entry identity. Equal timestamps across timelines are allowed. Processing time T needs exclusive completeness above T for every applicable source. Internal timelines share a serialized authority fence; external prefixes and their evidence are verified independently. Publishing a source prefix, obligations and its shared bound is one coherent conditional cut.

A child can advance independently of a parent. The parent consumes the selected historical child completion, not the child's latest state. Removing an embedding can suppress later delivery to that parent without stopping the independent child. Native strongly connected components are indivisible owning units. The tests cover tied entries, detachment, historical catch-up, shared parents, cycles, duplicate delivery and expired claims.

## Indexed delivery and completeness

A verified operation with an exact target uses an index of historical target identities plus broad observers. Unknown classification or an operation without a resolved target retains conservative delivery. Broadcasts reach observers. Native timeline admission and permission verification still run; the routing index does not grant authority. Irrelevant source cursors are allowed to remain behind and are skipped when a later relevant entry is selected.

Each owner retains a deduplicated set of external timeline dependencies and a shared-local-authority flag. Binding reference counts preserve duplicate occurrences during removal. Ordinary external bound advancement updates one shared source record, not every subscriber and ancestor. Changing source authority or graph topology updates dependency sets.

An account-wide minimum external bound is a sufficient positive certificate: when it covers T, every external source covers T. If an unrelated provider is behind, the scheduler checks only the selected owner's dependency set. An unrelated lagging provider never becomes a mandatory barrier. This fallback can cost O(distinct external sources) when the positive certificate cannot be used. It does not restore sibling execution bodies. The coherent account manifest is still a contention boundary; multi-account production sharding is not qualified here.

Legacy owner metadata builds missing routing/dependency indexes inside the existing conditional cut. This one-time migration can visit old subscribers and is distinct from steady-state input processing. Historical source cursors are not eagerly rewritten for inputs proven irrelevant.

## Storage and security boundary

Immutable content is tenant scoped and hash checked. Selected metadata paths and their bounded record bodies are fetched in batches; unrelated index branches are not prefetched. Small protected metadata records use a single content page rather than a chunk manifest plus body. Existing chunked records remain readable.

Native publication still validates the claim, predecessor, document controls and coherent manifest. Content is durable before a conditional publication points to it. A failed transaction leaves unreachable immutable content, never a visible partial owner state. The source completion, selected historical result and original signed input remain available after restart. There are still multiple sequential storage phases, so network latency is not eliminated.

Timeline reverse subscription pointers are prepared as immutable candidates in bounded batches. The receiver record is authoritative and is published atomically with its document. Fan-out fences abandoned additions and removals against that receiver; publication schedules catch-up. Historical pointers remain after removal and can be reused. Safe compaction of churned pointers remains future work.

New metadata includes a direct-page value encoding that old binaries do not understand. Rollback after new writes requires a compatible reader or restoration of the old storage snapshot; merely switching to the pre-change binary is not a supported downgrade. Deployment verification and recovery tests must retain this boundary.

## Explicit exclusions

This candidate is not proof of unlimited graph scalability, arbitrary-clause fast-path eligibility, consensus among independent hosts, deterministic externally fetched proof selection, production multi-account contention behavior or a complete security certification. Large expanded HTTP submissions still have the existing 1 MiB request limit. The audit report separates successful tests, measured slow paths and unmeasured cases.
