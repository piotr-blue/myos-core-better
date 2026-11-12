# Published Timeline channels through the durable host

The internal `TimelineIngress` now connects adopted, verified source archives to the exact published
Coordination Timeline Channel and Public Timeline Channel bindings. It executes the original entry
through Contracts/BEX and commits document changes and source progress together. The normal service
can install it through an explicit [Timeline configuration](TIMELINE-SERVICE.md) and expose acquisition/
step HTTP endpoints. Automatic provider transport/scheduling is described in [the service guide](TIMELINE-SERVICE.md);
private business admission requires the explicit [historical-access policy](TIMELINE-PRIVATE-ACCESS.md).

This path uses real signed Timeline evidence and the published channel adapter. It does not use the
separate HMAC fixture retained by the generic native-admission regression tests.

## One step

1. Lock and inspect the current local document through `Session.reception()`. The Contracts registry
   resolves inheritance, exact headers, channel lifetimes, composites and raw event observers.
2. Match declarations to the host's adopted source registry: exact timeline identity, or exact provider
   identity plus URL. Ignore other accounts; refuse ambiguous matches. Missing sources wait.
3. Derive the full current required-source set and freeze each verified archive. Even a quiet source
   must close strictly beyond the selected entry timestamp. The selection order is timestamp,
   timeline identity, then original entry identity.
4. Read the original message through that frozen source view. Contracts recognizes operation requests.
   `DocumentLineage` checks membership against this receiver's complete versioned history.
   An identity absent from that history does not address this receiver, even if no other managed
   document owns it. It cannot block the receiver's queue. Missing or incomplete receiver history
   still fails closed. A raw workflow can observe an operation addressed elsewhere without executing
   its operation. An ordinary event with no declared raw listener is also inapplicable: the host
   advances its source disposition without changing the document or manufacturing parent updates.
   This is host input selection; direct canonical Contracts execution retains its published rules.
5. Compare checkpoints using their original Timeline order. Local command-envelope IDs are mapped to
   original occurrences by immutable records published with source progress and the document result.
6. Bind the expected snapshot, original entry, timestamp, host-scoped input identity, declarations,
   generations and assessments to one synchronous invocation. The published verifier rejects any
   differing request or any request outside this scope. Concurrent threads have independent scopes;
   every scope is removed on success or failure.
7. Run actual Contracts/BEX, then conditionally publish the result, history, lineage, command receipt,
   source progress and original-checkpoint mapping. Existing child outboxes drive independent parent
   commits. A parent rejection cannot undo its already committed child.

The host-owned scope is not a wire capability. Supplying its JSON-shaped audit marker to `/documents/admit`
does not construct authority. The source archive, store and runtime registration are trusted deployment
configuration, not fields accepted from an event sender.

## Integration shape

These classes are currently package-private host infrastructure, not a published application SDK:

```java
Repository repository = new Repository(store);
TimelineIngress ingress = new TimelineIngress(repository, coordinationPublication);
DocumentRuntime runtime = DocumentRuntime.newBuilder()
    .language(languageWithVerifiedPackagesAndSources)
    .edition(DocumentRuntime.HOST_LINKS_EDITION)
    .timelineChannels(ingress.channels())
    .build();

// Configure Core with this runtime, the same store and a stable admission namespace.
TimelineIngress.Outcome result = ingress.step(core, account, document, adoptedArchives);
```

The archives argument is the trusted registry of available sources. The document's actual declarations
determine the required subset; callers do not get to choose a convenient incomplete set. Acquisition
and network/provider scheduling remain separate from this one-step method.

## Dispositions and recovery

- `APPLIED`: actual execution committed with its original source position.
- `INAPPLICABLE`: every receiving channel has an established negative assessment. Progress and
  assessments commit with a receiver-version check, without manufacturing document execution.
- `WAITING`: source adoption, strict completeness, target history or private access is missing.
- `PENDING_ACCESS`: the selected historical policy has verified insufficient access; no input is consumed.
- `IDLE`: no acquired candidate remains, or the document has no active Timeline receivers.
- `FAILED`: verified business execution failed. Its receipt is retained; the occurrence remains
  pending. Recovery needs a deliberate policy, not automatic skipping.
- `RETRY`: a disposition or native execution publication conflicted, or its receiving version changed. Discard the frozen invocation and prepare again against fresh state.

Two workers selecting the same occurrence derive the same command. The losing publisher recovers
the winner's exact receipt. Different competing commands and changed receiving views require fresh
selection. Lost successful commit responses reconcile through the existing receipt; progress and
original-checkpoint mapping survive restart together.

Original checkpoint mappings use `TIMELINE_EVENT#<local-input-BlueId>`, tied to the matching immutable
`TIMELINE_INPUT#<revision>` publication. The lookup validates exact input identity, receiving version,
history association and timestamp. A missing or inconsistent mapping fails; the host never guesses
Timeline ordering from a local command envelope. Existing older source histories need migration
before they can use this path; no migration utility is provided yet.

## Explicit boundaries

Private timelines require the opt-in [historical-access policy](TIMELINE-PRIVATE-ACCESS.md).
Signed private content does not itself grant historical read permission. Arbitrary policy rebase,
retroactive authorization and more general private backlog delivery are
still required work.

Required source sets and the receiving declaration/lifetime shape are pinned in source progress.
Changing either requires an explicit historical rebase, which is not implemented yet. This includes
changing an actor while retaining the same timeline. Silently continuing from the old global cursor
could skip the new lifetime's obligations.

The index covers committed local document history, not arbitrary expanded graph identities. Original
MyOS authentication/UI/SSE/webhooks, full provider transport/services, Mandates/Time, distributed shared
documents/cycles, retention and production load qualification remain unfinished. This internal public
source path is not a claim of complete canonical Coordination. Installing its service profile
requires an explicit manifest; the ordinary deployment does not adopt temporary qualification sources.

## Test ownership and timing

`NativeReceptionTest` in the library owns semantic inspection. `TimelineIngressTest` owns actual signed
public-source execution, strict closure, both published bindings, actor matching, historical and exact
targets, composite routing, unrelated observers, negative dispositions, private-source waiting,
concurrent documents/workers, lost responses, missing checkpoint history, store binding, changed
lifetimes and child-to-parent propagation. `TimelineInputsTest` retains the detailed cursor/order tests.

The iteration96 native probe measures preparation through conditional publication, including receiving
inspection, source selection, lineage and original-checkpoint reads, native admission and real BEX.
Signing, acquisition and readback are outside that timed call; acquisition is reported separately.
It uses JSON-boundary MemoryStore, not AWS. The paired test-HMAC regression and non-native Hello
initialization measurements remain separate workloads; their timings must not be substituted for it.

## Explicit live qualification

`./gradlew qualificationJar installDist` builds `PublishedTimelineProbe` separately from the service.
Its only additional resource is the exact pinned Coordination publication shared with the tests.
The probe generates fresh Ed25519 provider keys in memory; it does not ship test private keys or
persist the generated private keys. Both published channel bindings execute actual BEX. It checks
source-completeness waits, signature rejection, forged invocation claims, negative actor assessments,
independent child/parent publication, duplicate selection and cold original-order continuation.

After deploying the built host-links distribution, explicitly run:

```bash
python3 scripts/run_native_admission.py --protocol published-timeline --warmups 16 --samples 32
```

The launcher verifies the AWS account, deployment, runtime JARs and separate tool digest. It takes
the deployment lock, stops the main service while the temporary provider runtime drains its parent
events, and restores the same service/profile on exit, including qualification failure. The main
service still has no automatic native feeder. The synthetic default protocol remains parentless and
keeps the service running. Neither mode selects existing application documents: each creates a fresh
`native-bench-*` account. Inspect the returned SSM command until terminal; a local observation timeout
does not mean that the remote command stopped.

Ingress timing includes receiving/source preparation through durable child publication. Propagated
timing additionally includes synchronous outbox drain and the parent commit. Acquisition is reported
separately; signing and business readback are outside both. Samples and storage counters are retained.
This is a bounded correctness qualification, not a provider HTTP service or a load/SLA benchmark.


The `timeline-service` qualification mode additionally starts the actual `Main` server, submits signed
evidence through HTTP, waits for asynchronous parent propagation, and restarts that process against
DynamoDB. See [the service guide](TIMELINE-SERVICE.md) for its distinct configuration and timing boundary.
