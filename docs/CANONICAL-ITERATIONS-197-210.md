# Canonical execution and AWS performance iterations

The current measurements and reproduction commands are in
[NATIVE-LATENCY-CURRENT.md](NATIVE-LATENCY-CURRENT.md). This records implementation
decisions, including experiments that needed correction before deployment.

| Iteration | Change and decision |
| --- | --- |
| 197–199 | Use the canonical Contracts graph transaction in the host. Retain atomic source consumption, history and graph-head publication. Add native Timeline ingress and member-aware console reads and operations. Keep the previous local execution edition for existing documents. |
| 199 | Qualify the actual Activation / IncrementalDocuments / Payment workflow with 128 Orders. All operations applied and both Payment totals propagated. Correct the separate scale probe: an unqualified shared channel had broadcast to every Payment; the corrected probe targets one Payment and asserts exactly three changed members. |
| 200 | Retain bulk radix-index changes and immutable record/index caches. Publish only final affected index pages, not an intermediate root for every record. |
| 201 | Retain request-local source discovery and permission-decision reuse for identical bindings. Scope it to one frozen input cut. Add engine, admission-through-commit and signed-entry-through-commit timings. |
| 202 | Retain batched cold reconstruction of radix pages, content records and block chunks. Every content hash is still checked. |
| 203 | Retain a bounded 8 MiB exact-record text cache owned by retained Language evidence. It contains rendering data, not validation or permission authority. |
| 204–207 | Compact ordinary native snapshot records using descendant BlueIds. Initial attempts exposed inline-only checkpoint decoding and changed contract inspection. Correct the decoder and preserve literal contract presentation. Full tests then passed; deploy 207. Cyclic records retain their owning proof. Inline and compact restoration must produce the same identities, execution and reads. |
| 208–209 | Retain at most four concurrent immutable-content write batches per publication. No mutable head commits until every batch succeeds. Correct a test recorder whose ArrayList lost concurrent call records; add concurrency-bound and failure-isolation tests. |
| 210 | Retain one deeply immutable native admission projection per invocation instead of copying the whole receiver inventory for each channel. Forked attempts share these immutable values. The next input still verifies independently. Also reuse already-owned immutable declaration headers when assembling the declaration index. |
| 211 | Skip the second canonical-rendering walk for native exact snapshot records. Exact child references already form the complete closure; public Language exports retain prefix witnesses. Compare compact/minimal and inline/witness exports through restored execution and identities. |
| 212 | Reuse the canonical byte encoding of unchanged document metadata and edges during snapshot hashing. Every committed byte and member key is unchanged. Test growing snapshots, Unicode keys, copying, edits and removals against independent full-wire hashing. Deployed and passed AWS 1/128/1024 Order and 2/8-member cycle checks; steady-state latency remained too high. |
| 213 | Use the topology's ordered outgoing-edge index for component partition charges, avoiding an all-edge scan per component. Logical charge order is unchanged. |
| 214 | Attach one immutable declaration plan to each live document. Copies share the plan; changed contracts, evidence or registry configuration invalidate it. The first test incorrectly substituted a newly initialized document for a state-only edit; corrected the test to preserve its lifecycle metadata. |
| 215 | Preserve configuration tokens and positive structural checks across private processor forks. Facts remain keyed by exact body, metadata, dependency values and evidence context. Input admission is never cached. Replace serialized JSON header-set keys with a typed immutable key. |
| 216 | Retain an immutable topology scheduling plan across phases, keeping readiness and completion state private. Reuse is invalidated by changed topology or groups. Preserve Unicode scalar ordering while decoding only the first differing UTF-16 position. Tests cover independent phase progress, cyclic promotion and supplementary characters. |
| 217 | Export only content absent from the prior immutable catalogue. Append new records to the persisted catalogue; reconstruct complete current portable snapshots on export. Tests cover delta/full equality, historical versions, missing retained evidence and cold restoration. The catalogue can contain older records; it is not itself a minimal portable export. |
| 218 | Retain lazy hash lookup alongside canonical traversal; remove the redundant global idle-proof LRU; fast-path ready idle singleton units and reuse the topology partition. Mutation and copy-isolation tests passed. |
| 219 | Cold execution reconstructs current dependencies from the retained catalogue without decoding obsolete historical records. Identity and proof validation remain in Language. |
| 220 | Fix an accidental all-live-members scan for every scheduling group. Pre-encode immutable idle proof records and retain marker lookups only for the exact body/configuration/evidence scope. Full tests and 257/2,049-document local inline-equality checks passed. |
| 221 | Reuse completed containing-reference checks only while the exact body, identity, outgoing edges and target identities match. A child change still propagates through every containing parent. |
| 222 | Use the JVM string-comparison intrinsic for comparisons whose code-unit difference cannot reverse Unicode scalar order. Preserve the scalar fallback at surrogate/BMP boundaries. |
| 223–224 | The first local Timeline message reuse candidate failed both clause journeys because it retained the producer provider context. It was not deployed. Add `Language.prepare(existingContent)` to retain exact materialization and evidence under the consumer provider context without JSON normalization; ordinary signature/permission verification and byte limits remain. The previously failing journeys and wire-equivalence/revocation tests then passed. |
| 225–226 | Limit local message transfer to its exact on-wire materialization. The receiver supplies its own providers; producer-only and collapsed hidden evidence remain unavailable. Expanded/collapsed presentations take the existing exact parser path. Isolation, expanded-type and native workflow tests cover both paths. |
| 227 | Diagnose 229 admission passes for one startup operation; batch up to 16 fully assessed, nonparticipating original inputs (128 KiB audit target), reusing the frozen graph and cut. Retain every order key, stop before a participating input, and retain cold-restart and raw-observer behavior. Verified non-operation/foreign-target classification avoids unnecessary message materialization. |
| 228 | Materialize the completed transaction snapshot once instead of copying the whole graph independently for identity, BlueId, export, reception and commit. Retain immutable receiver descriptions only for their owning snapshot; invalidate on direct commit and compaction, and transfer a candidate description only with its successful commit. |
| 229 | Carry a verified non-operation scheduling hint through pending source notifications. Such inputs wake raw observers and waiting receivers, but not idle operation-only graphs. Overlapping operation arrivals widen the entire pending pass; missing legacy hints remain conservative. This avoids rebuilding unrelated idle graphs when a new access grant is published. |

| 230 | Extend the AWS qualification with bounded feedback through 1-, 2- and 8-member cycles, whole-cycle rollback when a peer rejects, and another event after cold restoration. Keep these as explicit qualification cases, separate from the regular unit suite. The deployed runtime remains release 229. |

| 231 | Replace full-edge scans and per-turn global edge sorting with topology-owned member indexes. Check a patch's strict ancestor paths directly instead of scanning every sibling embedding. Retain decoded-pointer order and canonical gas. A new boundary test initially used an invalid scalar replacement; corrected it to a valid child object. Escaped-pointer, replacement, isolation and rollback cases cover the behavior. Retain after a local 2,049-document comparison: median inline 128.2 → 120.5 ms, persisted-memory 174.2 → 165.4 ms (eight samples; modest improvement, not a tail-latency claim). |

The final 231 regression run passed 2,482 library tests and 509 host tests, with
no failures or skips. Formatting, strict compilation, Javadoc and distribution
build passed. All 53 installed JARs matched the tested staging distribution before
deployment; console assets were verified against their source manifest.

No Python implementation or original `blue-java` code was changed in this work.
The new clause is a separate publication, not a replacement of existing identities.
No example-specific handlers were added to the execution engine.
