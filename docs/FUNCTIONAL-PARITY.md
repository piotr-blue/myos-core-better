# Functional parity with original Core

This work compares observable bootstrap, acquisition and timer behavior against original Core
at `8b71a0e7de48aaeb79bddddb25ae04e0fc0f2d85`. Equivalent behavior does not mean identical historical
package identities or identical proof wire formats. Better advertises its exact execution selection.
Full functional parity has not yet been reached.

## Completed behavior in the November 11 iteration

| Area | Behavior and executable evidence |
| --- | --- |
| Participant accounts | Same-account and collaborative agreement replicas retain identical bound definitions and independently authorized participant Timelines. `CoreCollaborativeBootstrapTest`. |
| Human representatives | Party leads, named representatives and explicit channel mappings retain authenticated human actors. Membership/credential checks fence the actual Timeline commit; legacy account-wide callers cannot impersonate a representative. Operation discovery is scoped to the current user. `HostedTimelineWriterTest`, `CoreBootstrapRepresentativeTest`. |
| First-time email recipients | Pure preparation reserves no resources. Accepted bootstrap atomically reserves the account and signup notice; verified login claims it with ownership and membership fences. Duplicate users cannot claim it twice. Recycling an email creates a new reservation and does not reassign the former account. Account runtime/gas provisioning runs before initialization and is idempotent. `CorePendingRecipientsTest`, `CoreBootstrapNewRecipientTest`. |
| Registered email | Resolve the email's currently owned account; reject ambiguous identities, mismatched account/email and stale ownership. Freeze recipient checks with durable job acceptance; accepted work replays its original selection. `CoreBootstrapParticipantsTest`, `CoreBootstrapJobsTest`, `CoreBootstrapPartyTest`. |
| Party authoring | Bind typed Parties under `/parties`, Activation, explicit custom paths and native/referenced lists. Preserve other list elements; exclude nested document templates and executable scopes. Party and lead account identities must agree. `CoreBootstrapPartyTest`. |
| Read-only recipients | Activation reader groups receive the same bound agreement, use managed account reader custody and receive no writer Timeline. Authored reader alternatives must include a live key belonging to that account. `CoreBootstrapPartyTest`. |
| Initiator | Require the initiating account to be an actual participant, as original Core does. No implicit outside-reader registration. `CoreCollaborativeBootstrapTest`. |
| Import retries | Distinguish availability and invalid evidence; stop ordinary retries after three failed attempts. Access denial and missing content remain wakeable. Explicit retry fences late failures, including an in-flight first request. `TimelinePollingTest`, `CoreTimelineImportsTest`. |
| Native integrity recovery | Invalid evidence blocks existing independent owners; a verified import success alone does not clear that block. Explicit document retry is separate. Unrelated owners remain runnable, in-flight publication is fenced, and new lifetimes do not inherit repaired historical faults. `OwnerSourceFailuresTest`. |
| Timers | The shipped console runtime enables the Time edition already imported by its business packages. Discovery advertises the Time protocol/specification and timer capability. Real document-facade tests exercise pending-timer inspection and two logical timer stages. Existing owner-time tests cover ordering, cancellation, child propagation, restart, private-source completeness and rate limits. |

## Runtime and recovery boundaries

Use `MYOS_OWNERS_FAST=true` with `examples/clause-tutorial/runtime-console.json`. Its Time publication
is `6QHd1RcXJnddfLyePbtHouNYEpis6CXe4mMu4pNBqZrU`. The separately supported unrestricted-context
Time edition requires its own explicit selection; it must not replace the Time identity imported
by an existing business package without qualifying those packages together.

Source-integrity faults upgrade an affected owner's coordination metadata to
`independent-owner-frontiers/4`. An older binary rejects this format instead of ignoring its
recovery requirements. Source rollback alone is therefore insufficient once an account has entered
that format. Upgrade all workers sharing that account's storage before relying on the new recovery
behavior. No AWS configuration, runtime state or benchmark traffic was changed by this iteration.

Representative Timelines use `myos.hosted-timeline/2`, carrying an immutable human writer policy.
Older binaries reject these Timelines instead of ignoring the policy. All publishers reading this
storage need the updated host before representative Timelines are created. Existing ordinary
Timelines retain format 1. Collaborative bootstrap now indexes the initial document immediately,
so targeted Timeline operations do not depend on a prior document GET.

Healthy accounts retain the existing metadata format and processing path. Faulted accounts lazily
index direct Timeline bindings; repeated integrity checks do not enumerate all embedded-owner
dependencies. The first adoption of an old owner's bindings may read its existing dependency list.

## Delivery and compatibility

First-time recipient ownership is claimed by the verified identity adapter, not by clicking an email
link. Signup notices use `CORE_SIGNUP_DELIVERY`; the configured delivery worker shares the existing
SES transport with invitations. The default account preference selects the newly claimed workspace.
If directory indexing is unavailable, the claim waits instead of silently allocating a personal Owner.
New recipient runtimes retain their default gas entitlement, and resuming bootstrap cannot refill gas.
Upgrade identity and bootstrap workers together before enabling first-time recipient onboarding.

Gas activity now uses a fixed-width, nondecreasing timestamp plus the committed metadata sequence
for outbox ordering. Displayed creation times retain the observed clock value. Regression coverage
includes differing fractional precision and a backward clock; historical projected event order is
not rewritten.

## Remaining equivalence gaps

- Actual signup email delivery needs the existing configured SES sender, configuration set and console
  origin. Tests use an injected sender and send no real messages. The signup queue is separate from
  invitation work, so older invitation workers do not consume signup notices.
- Agent representative writers, automatic Agent inclusion, selected-document representation coverage
  for READ_SELECTED members, mandate workflows and external Agent execution. Human representative
  publication currently requires an active WRITE, ADMIN or OWNER membership.
- Original account runtime activation/retry API and associated mandate/representation workflows.
- The new native integrity latch is integrated with independent owners and durable directory polling.
  Static configured polling and the canonical-graph fallback still need equivalent recovery review.
- Participant resolution currently permits at most 90 observed acceptance conditions. This can
  constrain a large email roster before the original 64-participant bound.
- Historical original-Core execution/proof editions remain separate from Better's exact runtime.

The route manifest remains partial for these capabilities; unsupported behavior is not reported as
successful. See `dashboard-api-parity.json` for route-level status.

## Validation

The final third-iteration `format check installDist` passed all 1,202 host tests, with no failures,
errors or skips, and built the runnable distribution. Evidence includes human representative publication,
revocation during the publication transaction, bootstrap membership fencing, route visibility, exact
replay, cross-account propagation, first-time recipients, concurrent verified claims, email recycling,
directory rebuilds, signup delivery retry and normal recipient gas entitlement. A full-suite gas activity
ordering failure was reproduced with deterministic clock inputs and fixed; its original failure and the
passing regression are retained in the evidence directory.

The first iteration also passed all 365 UI tests and rebuilt the production bundle. Later iterations did
not change UI source. This is local functional evidence, not a new AWS latency benchmark or a claim that
the remaining equivalence gaps are complete. No live deployment, real signup email or benchmark traffic
was started by this work.
