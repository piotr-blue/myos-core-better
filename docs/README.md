# Documentation map

[14 November quality and API notes](QUALITY-2026-11-14.md) summarize the latest maintenance changes and verification boundaries.

Start with the [project overview](../README.md), [architecture](ARCHITECTURE.md) and
[current operations guide](OPERATIONS.md). Guides explain the maintained implementation. Dated
reports record one version, environment and experiment; their timings and limitations are not
automatically current.

## Run and operate the service

| Task | Guide |
| --- | --- |
| Run an Agreement, Order and Payment through the console | [Canonical preview](canonical-preview.md) |
| Build, deploy, inspect health and run bounded acceptance checks | [Operations](OPERATIONS.md) |
| Select exact packages and native capabilities | [Runtime packages](RUNTIME-PACKAGES.md) |
| Understand shared public definitions and compatible rollout | [Public definitions](PUBLIC-DEFINITIONS.md) |
| Review remaining release work | [Readiness decisions](PRODUCTION-READINESS.md), [security boundaries](../SECURITY.md) |

## Understand execution and recovery

| Boundary | Guide |
| --- | --- |
| Component ownership and design decisions | [Architecture](ARCHITECTURE.md) |
| Ordering, source completeness and independent owners | [Owner frontiers](owner-frontiers.md) |
| Native graph execution, publication and fallback | [Canonical graphs](CANONICAL-GRAPHS.md) |
| Timeline acquisition and retained progress | [Acquisition](TIMELINE-ACQUISITION.md), [directory](TIMELINE-DIRECTORY.md), [imports](TIMELINE-IMPORTS.md) |
| Private Timeline authority and custody | [Private access](TIMELINE-PRIVATE-ACCESS.md), [hosted Timelines](HOSTED-TIMELINES.md) |
| Document addressing across versions | [Lineage](DOCUMENT-LINEAGE.md) |
| Current functional coverage versus original Core | [Functional parity](FUNCTIONAL-PARITY.md), [dashboard inventory](DASHBOARD-PARITY.md) |

The library owns Blue Language, BEX, Contracts and semantic gas. The host owns durable storage,
source acquisition, ordering and authorization. Start with the sibling library's
[documentation map](../../blue-java-better/docs/README.md) for those language and execution APIs.
A faster local owner path must preserve the native result; a host timeout cannot authorize
skipping an unproven predecessor.

## Change and verify the code

| Task | Guide |
| --- | --- |
| Review ownership, trust and failure contracts | [Invariant map](INVARIANTS.md) |
| Build and review conventions | [Contributing](../CONTRIBUTING.md) |
| Find the suite responsible for an invariant | [Testing](TESTING.md) |
| Check API compatibility | [API baseline and checker](../api/README.md) |
| Interpret distributed tests and their limits | [Distributed qualification](DISTRIBUTED-QUALIFICATION.md) |
| Run a published clause workflow or sustained owner workload | [Canonical preview](canonical-preview.md), [sustained qualification](OWNER-SOAK.md) |

Ordinary checks use local fixtures. Explicit live qualification commands create work and require
the intended AWS environment. Report initialization, engine/storage processing and client-observed
completion separately; none is an interchangeable latency number.

## Historical evidence

- [10 November polish report](polish-2026-11-10/REPORT.md) and
  [semantic release report](semantic-release-2026-11-10/REPORT.md)
- [Earlier host guide](HISTORICAL-HOST-README.md) and [earlier console guide](CONSOLE.md)
- [8 November AWS owner measurements](owner-aws-report-2026-11-08.md)
- [Earlier owner reproduction](owner-reproduction.md)

Historical snapshots may describe removed APIs, earlier package editions or a different console
URL. Keep them as evidence; use the maintained guides above for new work. A passing qualification
is not a production maturity certificate or a claim of complete MyOS compatibility.
