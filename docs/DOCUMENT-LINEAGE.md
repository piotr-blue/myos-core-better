# Established document identities

When `MYOS_ADMISSION_NAMESPACE` is configured, the host indexes the authored initial BlueId and
each committed local body BlueId alongside the document's state and immutable history. This is
internal infrastructure for native operation routing. It does not install a Timeline service or
grant permission to execute an operation.

An operation can refer to an earlier identity after the document changes. Looking through every
historical version to find that identity would make admission progressively slower. The index
resolves membership with two index reads and one exact historical-document read, independent of
the number of committed versions. The size of that historical document still affects read cost.

## Publication and lookup

Each managed document has a `LINEAGE` row. Its immutable fields bind the authored initial identity
to the first checkpoint and execution edition. Its revision advances with every document version,
certifying that the index covers every publication through that version.

An identity partition contains one immutable membership per managed document. Identical content
can belong to independently created documents without overwriting their memberships. Each member
records its first publication version, exact checkpoint identity and edition. Repeated body identities
retain their first publication. All keys are scoped to the managed account.

The state, immutable history, coverage row and any new memberships share the same conditional
transaction. Index preparation uses one batched read. A normal changing-body publication adds a
coverage write and a membership write, not a separate transaction. New children contribute their
own index records and count toward the existing 100-item transaction limit.

`DocumentLineage.find(account, document, receiverVersion, identity)` reads coverage first, then
membership, then the one named historical publication. These are deliberately ordered reads:
DynamoDB BatchGet does not provide a transactional snapshot. Combining a missing pre-commit
membership with post-commit coverage could otherwise falsely establish absence.

Lookup checks the exact historical checkpoint and edition, excludes identities first introduced
after the receiving version, and refuses incomplete coverage. Discovery through `known` checks a
real indexed publication; merely making content available does not establish a managed lineage.

## Initial children and history boundaries

Fresh children can initialize before their host attachment is emitted. The library therefore retains
their original authored identity separately as `DocumentRuntime.Attachment.initialBlueId()`.
The host publishes that identity with the child's initialized version. This transient value changes
neither canonical snapshot serialization nor gas. It is not a portable history proof or permission.

The index covers authored initial identities and committed **local** body versions. It does not claim
membership for transient intra-invocation states or arbitrary expanded graph projections. The local
host's document identities and eventual propagation remain distinct from canonical graph proofs.
The current host still rejects shared members within one fresh graph and distributed cycles.

The index is trusted private storage written by the host. A serialized index-shaped JSON object or
a hash supplied by a client cannot create authority. Access checks and operation eligibility remain
separate native-protocol obligations.

## Deployment and recovery

The default namespace-disabled host does not create or read this index. Enabling native admission
for an existing unindexed document requires explicit migration; there is no automatic migration tool
yet. Every writer to an indexed document must maintain coverage. An older writer that advances state
without the index leaves a detectable gap: lookup at the newer version and subsequent indexed
publication fail rather than pretending history is complete.

A peer committing during preparation causes a normal retry against fresh state. Lost transaction
responses reconcile through the existing command receipt. Failed initialization publishes no lineage.
Index and checkpoint data currently have no retention/garbage-collection policy.

## Qualification and remaining integration

`DocumentLineageTest` covers original and historical identities, version/account/document isolation,
identical independently created children, repeated identities, failed initialization, missing coverage,
mixed writers, publication races, lost responses, corrupt associations and the bounded three-read
lookup at 1 and 128 operations. Existing native-admission and acquired-source execution tests run with
index publication enabled.

The iteration95 benchmark compares the full local admit/publication call with iteration94, including
the added index work. Exact gas, document identities and checkpoint sizes match. This is a correctness
capability with storage overhead, not a claimed speedup. It is not an AWS/native-service qualification.
The [native ingress](TIMELINE-INGRESS.md) now uses this index for public-source operation/observer
routing and derives sources from current declarations. Private access, historical rebase and the
complete receiving-history protocol remain unfinished.
