# Canonical execution migration

The library no longer exposes Local-1 or DocumentRuntime. New console creation selects canonical
Contracts. Configurations with `blue.document-local/*` fail explicitly and require replay from
original inputs; no existing data is rewritten or reinterpreted.

`OwnerGraphs` remains the independent-owner scheduler. `CanonicalGraphs` remains the whole-graph
oracle/processing integration. Both execute canonical Contracts. `Contracts.Execution` replaces the
former Java adapter name `ContractOwners`; this is not a Blue type or an authored document model.

The private `CanonicalRuntime` class adapts the old service storage envelope to `Contracts.Session`.
It contains no interpreter, detached-child execution, parent-delivery algorithm, alternate gas
policy or command-derived event identity. It refuses detached delivery. Historical overlay/link
records require migration, not an emulated execution. New scalable processing uses OwnerGraphs.

Canonical native admission binds the original event identity and timestamp. TimelineIngress keeps
its host-derived account/document/cut scope, but does not substitute a command wrapper for the
original Blue event identity. Host command IDs still provide idempotency in storage.

Existing Local-1 performance reports and document-local microsecond measurements do not qualify
this canonical path. The current iteration report states what was actually measured. Source
changes are not a deployment, and no live AWS data is migrated by this work.

The owner storage format is now `myos.canonical-owners/2`. Older owner publications require
replay; this is a host storage marker, not a Blue document type. Canonical allocation derives
member keys from the sealed invocation. Private creation-occurrence mappings preserve one
durable owner when the same child is replayed inside several roots. They never replace the
native member keys inside a snapshot.

Disposition policy: successful processing commits state and consumes the source occurrence;
verified business/gas rejection records a rejected occurrence with unchanged business state;
missing resources and unverified admission remain pending. Host exceptions are retried or exposed
as failures, not passed off as completed Blue processing.

`RETIRED-TESTS.json` records which tests belonged specifically to removed detached execution.
Other tests were migrated to canonical whole-graph processing; owner/frontier suites independently
cover durable splitting, exact receipts, cycles, shared sources and restart.
