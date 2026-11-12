# MyOS Core console and bootstrap parity

The target is the original MyOS Core experience, backed by Better's independent-document runtime.
The reference checkout, Python implementation and package sources remain unchanged.

## Implemented in iteration 136

| Area | Available behavior | Verification |
| --- | --- | --- |
| Documents | Root listing; Overview, State, original Source, Operations, Timelines, Links, History, Events and Processing; version/view URLs | HTTP tests and deployed browser journey |
| Timelines | Create, append, inspect original signed entry and attestation, message-only view, loaded-entry filtering, sharing and revocation | Provider tests and browser/native-protocol qualification |
| Operations | Runtime-discovered declarations, including inherited operation types; YAML/JSON requests; channel and writable Timeline selection; optional exact-version target | Signed-input tests and clause browser journey |
| Direct initialization | Initialize supplied YAML/JSON with its existing bindings | Bootstrap/direct-create tests; generic counter browser/API journey |
| Participant bootstrap | Preview inherited Activation parties, create or reuse owner Timelines, provision readers/access, retain authored and bound source, resume the same command after interruption | Bootstrap tests and actual browser provisioning |
| API keys | Create, list, one-time secret display, bearer use, expiry and revoke; bearer keys cannot administer keys | Account/HTTP tests and deployed create/use/revoke journey |
| Clause tutorial | Activation + Documents Agreement creates Activation + Payment Order, which creates a manual Payment Instruction; all three reuse two participant Timelines | Local complete journey and deployed browser qualifier |
| Parent reactions | Payment receipt updates Order, then Agreement, through ordinary document listeners | Assertions on Payment completion and both totals, including historical state |

The runnable source and instructions are in [the clause tutorial](../examples/clause-tutorial/README.md).
The [AWS walkthrough](AWS-CLAUSE-WALKTHROUGH.md) gives the exact user steps and measured latency limitations.
Exact build, deployment and browser evidence belongs to
`reports/overnight-2026-11-05/manifest-136.json`; the code report is
[CONSOLE-NATIVE-BOOTSTRAP-136.md](CONSOLE-NATIVE-BOOTSTRAP-136.md).

## Boundaries and remaining differences

This is not full original MyOS Core parity. The original Terms/Timers controls, cross-account/email
invitation bootstrap, representative/agent provisioning, multi-account memberships and broader
lifecycle/redrive views remain. Bootstrap currently binds roles represented by the signed-in owner;
it explicitly rejects unsupported foreign participant locators. It does not grant another person's
consent. The tutorial deliberately uses `required: []` to focus on document creation and payment.

The console deploys the `console-native` profile. Signed Timeline requests are consumed automatically;
submission is asynchronous and does not itself prove business acceptance. Current receiving Timelines
are shown on the Timelines tab; selecting an old document version does not turn that tab into a
historical receiving-configuration viewer. History does retain old state, original input, outcomes
and events where those records were published by the host.

A human session manages credentials. A bearer key uses its owner's document and Timeline resources,
but cannot manage keys or select another account. Revocation applies to the next authentication;
a request authenticated before revocation may finish.

Parent propagation is durable and eventual. It is not graph-wide atomic rollback, a global
Coordination proof, or distributed-cycle qualification. Control-only Timeline coverage establishes
historical access without generating business deliveries; a declared channel on that same source
still receives its entries. A shared business Timeline still wakes all of its subscribed receivers.
Addressed routing at very large scale and production HA remain separate work.

Java implements provisioning, authorization, exact content, transactions and dispatch. Agreement,
Order and Payment rules are in the pinned packages and example source, not host-side conditions.
