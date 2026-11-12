# Historical private Timeline admission

Iteration115 adds an **opt-in consuming-document policy**, `myos.timeline-access-at-input/1`.
It does not add fields to the published Coordination channel types or replace application business
authorization. The normal CloudFront console still uses host-links document execution; Timeline
append does not automatically invoke its payment documents.

Three decisions remain separate:

1. The provider checks the signing reader's **current** permission before returning private content.
2. This document policy requires selected keys to have access at the **original input's order**.
3. The published channel and Contracts workflows decide whether that original input participates and
   what business changes it produces.

Acquiring an entry with a current grant establishes neither historical permission nor business
eligibility. The original input order is `(timestamp, timeline BlueId, entry BlueId)`, compared in that
order, including ties. Required public control coverage must close strictly beyond its timestamp.

## Explicit deployment selection

Add this field to the `timelines` object in the [runtime manifest](TIMELINE-SERVICE.md):

```json
"privateAccess": "myos.timeline-access-at-input/1"
```

Also adopt the private Timeline and its provider's reserved **public** control Timeline under the
same account. Automatic private acquisition needs the pinned reader secret described in that guide.
The private source's signed provider descriptor establishes which control Timeline is reserved.
An arbitrary public Timeline, even one from the same provider, cannot substitute for it.

The selected policy is persisted with the namespace. Changing an existing namespace from the old
profile requires explicit migration; startup refuses a silent change. Leaving this option out
preserves the earlier behavior: private receivers wait for historical access support.

## Document declaration

This is a template: replace identity and public-key placeholders with actual values and supply the
initial access document's content through the document's Language environment. Public keys are
lowercase hexadecimal Ed25519 keys. Private keys never belong in a document.

```yaml
count: 0
timelineReadAccess:
  profile: myos.timeline-access-at-input/1
  requirements:
    - timeline: {blueId: '<private Timeline BlueId>'}
      keys: ['<required reader public key>']
      grants: [{blueId: '<initial Timeline Access document BlueId>'}]
contracts:
  incoming:
    type: Coordination/Timeline Channel
    timeline: {blueId: '<private Timeline BlueId>'}
    actor: {type: Coordination/Principal Actor, account: alice}
  countIncoming:
    type: Contracts/Sequential Workflow
    channel: incoming
    steps:
      - type: Contracts/Update Document
        changeset:
          - op: replace
            path: /count
            val: {$add: [{$document: /count}, {$event: /message/amount}]}
```

There must be exactly one requirement per active private receiving source. Each contains 1–32
distinct keys and initial grants; the whole policy permits at most 32 distinct grants and 32 sources.
Every required key must be covered. Multiple executed access documents contribute a union. A grant
counts only if its historical state includes the input's Timeline. The selected key set is the
document's declared policy, not an implicit list of all business parties.

The workflow above is exercised through the real private importer, signed public control history,
published Timeline Access BEX, native channel verification and durable Core transaction in
`PrivateTimelineIngressTest`. Its scenarios include:

| Established source history | Selected input | Result |
| --- | --- | --- |
| Grant100, private message200(amount42), revoke300 | Message200 | `APPLIED`; count42, although latest grant has no keys |
| Grant100, revoke200, message300(amount9), restore400 | Message300 | `PENDING_ACCESS`; count0, no consumed input or business version change |
| Separate grants cover two required keys | Private message200(amount7) | `APPLIED`; count7 |
| Private message acquired but control coverage missing | Private message200(amount5) | `WAITING`; after public control acquisition, `APPLIED`; count5 |
| Fresh Core and archives after the preceding commit | Next private message500(amount6) | `APPLIED`; count11, then `IDLE` |

These timestamps are controlled test units, not wall-clock dates. Public control occurrences used
only as dependencies are recorded `INAPPLICABLE`, preserving merged original order without invoking
the business document. They do not wait for an unrelated access document's business target lineage.

## Verification and publication

The host executes selected initial access documents against independently verified control history
using `TimelineAccess.track`. It selects the actual executed after-state at the input's full order.
It never trusts a stored `allowed: true`, an unchecked latest snapshot or a producer recovery
checkpoint as consumer authority. Admission binds the frozen document version, original source
input, closed control cut, initial/executed grant identities and required public keys. Native
evidence contains no reader credentials. Document outcome and input progress publish conditionally
together; a conflict discards the invocation and selects again.

Missing coverage is `WAITING`. Verified insufficient access is `PENDING_ACCESS`; it does not publish
a consumed disposition, execute business logic, or keep a worker in a hot retry loop. A later source
arrival can wake it, but **a later restoration cannot retroactively authorize that same input** under
this policy. This is intentional. Receiving-policy changes require historical rebase, which is not
implemented yet; deployments needing retroactive authorization should not select this profile.

## Cost and limits

A process cache shares canonical replay only for the exact account/provider/control/initial-grant
tuple. Different grants use independent locks; archive I/O and BEX execution do not hold the cache
registry lock. Active replays are not evicted. An already verified selection performs no archive
reads. Tenant isolation, full-key ties, cache eviction, a discarded-window gap, bounded catch-up,
restart and independent concurrent accounts have targeted tests.

There are at most64 cached replays,1024 recent permission changes per replay and32 source batches
per catch-up attempt. Old queries replay from the beginning when their state has been evicted.
These are **not an overall heap bound**: the underlying canonical consumer retains source/receipt
history. Cold recovery and old queries remain proportional to that retained history, and a batch
limit is not a wall-time budget. Heavy cache churn can repeat cold work. A validated compact consumer
checkpoint and a byte-based retention policy remain needed for long-lived production workloads.

`TimelineAccessHistoryProbe` reports cold replay separately from cached selection, with one versus65
control occurrences. It verifies exact publisher/consumer state identities and key sets. Its local
memory-store timings exclude HTTP, business execution and parent propagation; they must not be
presented as AWS payment timings. See the iteration report for measured results and retained raw data.


Iteration116 source qualification reduces cold65-entry replay to147.164ms for repeated requests and
166.372ms for changing requests, versus180.310/209.198ms for115 in paired local runs. It reuses grammar
validation inside one verified evidence view and scopes each independently verified entry header to
its invocation. Gas, receipts, events and historical documents remain equal. See the report for exact
methodology and allocation evidence. This does not remove cold-history or retention limits above.


## Co-located provider state reuse

The console host binds its own provider to the receiving service through the same protected
store. An access document that has never changed can reuse its actual committed initial
execution state after its original publication, once acquired control coverage closes the input.
No current permission is cached: a strong access-record read detects every update. Updated
grants, other owners and external providers retain the canonical historical replay path.
This optimization changes physical verification work, not the declared permission policy.
