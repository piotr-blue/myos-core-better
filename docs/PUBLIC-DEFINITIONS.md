# Public definitions and cold accounts

Previously, `SharedDefinitions.retain` wrote a complete installed package catalogue into
each tenant when its first input needed retained definitions. That call could execute while
an owner held a 120-second claim. Tens of thousands of catalogue pages competed with useful
work and could outlive the claim. Identical public package records were copied per account.

## Publication before processing

`MYOS_PUBLIC_DEFINITIONS=true` makes `Main` prepare the operator-selected package catalogue
before starting HTTP servers or workers. `PublicDefinitions` accepts validated installed
packages, never document-submitted records. A dedicated repository keeps this startup work
outside all tenant content batches. It publishes immutable pages before conditionally
publishing one catalogue marker. Concurrent starters must agree on the root; a failed page
write leaves no visible marker. A retry can reuse immutable bytes safely.

Small records (at most 2 KiB of UTF-8 JSON) reside directly in index leaves. Larger records
retain the existing chunked content format. Public index leaves are bounded by 128 entries
and 32 KiB; readers continue to accept the existing index format. This is a host storage
change. BlueIds, document paths, clauses, gas and signed timelines are unchanged.

The reserved scope `@public-definitions` cannot pass tenant identifier validation. DynamoDB
stores its pages under `SYS#PUBLIC_DEFINITIONS#C#…`, separate from `A#tenant#C#…` private
content. The metadata marker is keyed by the installed package identity set. Old package
sets remain available; removing a package from current configuration does not rewrite an
existing snapshot's pinned reference.

## Reading and authority

New snapshots may retain `public:<root>` in `definitionContent`. `SnapshotContent` accepts
both that reference and legacy tenant-local roots. `PublicDefinitionContent` reads public
pages in their own scope and returns the selected exact record as inline JSON to existing
snapshot readers. Public block addresses never masquerade as private tenant addresses.
The full portable export remains explicit; point reads do not enumerate the catalogue.

Owner processing only reads a prepared marker. Missing preparation is an error, not a
request to initialize storage under a claim. Account-private definitions and timeline bytes
remain private. Finding public record bytes supplies no channel admission, reader grant or
business authorization. Local snapshot content keeps precedence.

## Rollout and rollback

1. Build and install this reader-compatible release with `--public-definitions` omitted.
   It reads both root forms and continues to write legacy tenant references. Preserve the
   exact installed package profile; this deployment uses `--runtime-profile console-deployed`.
2. Verify existing documents, `/health` and `/ready`, then mark this release healthy.
3. Deploy the same compatible code with `--public-definitions`. Startup prepares the catalogue
   before workers start. The deployment allows up to 180 health attempts for first setup.
4. Test fresh accounts, cold reads and payment propagation. After a public reference has been
   written, rollback is restricted to a release containing this reader. Do not reinstall an
   older binary that only understands hexadecimal tenant-local roots.

Setting the flag false on the compatible release changes future writes; it does not remove
the public reader or invalidate existing references. Keep the prepared public pages and
markers in backups. No garbage collector is introduced: deletion requires a separate
retention/root-reachability protocol and restore rehearsal.

`PublicDefinitionsTest` checks content equality, two-account reuse without writes, legacy
reads, local precedence, failed publication, concurrent startup and private-content isolation.
`PublicCatalogueProbe` measures publication pages/bytes and cold reads on real DynamoDB.
The matched `OwnerScaleProbe` checks signed inputs and whole-native parity at 1, 128 and
1,000 Orders. Neither probe is a claim that arbitrary flat dictionary insertion is constant
time or that the service is highly available.
