# Importing a signed Timeline

The dashboard reuses the original Core import form. A managed reader is an account-owned
Ed25519 credential kept encrypted by the host. Its public key can be granted read access by a
provider; neither the public key nor the reader ID grants append permission.

## Reproduce the deployed example

1. Sign in at https://d2ya9daeiur7nz.cloudfront.net and select your personal workspace.
2. Open **Timelines → Import timeline**.
3. Enter:
   - Name: `Partner payments — import 52`
   - Provider endpoint: `https://d2ya9daeiur7nz.cloudfront.net/api/provider`
   - Timeline BlueId: `4ixjqc7moHEnvBfh6VVT94LZe6cupBjyrpSjrxQE78Ly`
   - Existing reader ID: `d97c1890845e5271bb2db05cddcc390189f0f1a25ecd40c94c32506c59d490f6`
4. Import. This repeats the existing account-owned import, preserving its provider/key pins.
5. Choose **Check now**, **Expand all**, then **Show message only**. The entry contains
   `kind: Cross-account import verification` and `amount: 17`.

Direct view: https://d2ya9daeiur7nz.cloudfront.net/#timelines/4ixjqc7moHEnvBfh6VVT94LZe6cupBjyrpSjrxQE78Ly

The existing workspace owns the source; the personal account only reads it. The example's
credential and grant are intentionally retained. Account selection currently probes an unfinished
admin-session endpoint; its 501 is an explicit remaining parity gap, not an import failure.

## Provision a reader for another import

Run on the trusted host with its existing DynamoDB table, region and pinned application custody
configuration. `MYOS_TABLE`, `AWS_REGION`, `MYOS_WEBHOOK_SECRET_ARN` and
`MYOS_WEBHOOK_SECRET_VERSION` must select that deployment. Do not copy private secret values into
shell arguments. The custody variables identify an existing pinned Secrets Manager object/version.

```sh
bin/myos-core-better reader-key ACCOUNT_ID 'Partner reader' UNIQUE_COMMAND_ID
```

The command returns `id`, `accountId`, `name`, `publicKey` and `createdAt`. Repeating the same account,
name and command returns the original public metadata. Give the public key to the provider's
owner for an ordinary Timeline Access grant; enter the returned ID in the original import form.
The command is operator-only: the removed public key-management API is not recreated.

An import accepts exactly one of `readerKeyId` or `readerPublicKey`, both resolved through current
account-owned custody. Discovery verifies the signed provider descriptor and exact requested
Timeline. HTTP destinations are public HTTPS on port 443, with no credentials/query/fragment or
redirects. Native source acquisition and verification remain authoritative. Public provider access
history is listed separately; private source possession never grants append or business authority.

## Verification and limits

`CoreTimelineImportsTest`, `CoreTimelineDiscoveryTest`, `CoreReaderKeysTest`,
`PublicTimelineTransportTest` and `TimelineArchiveTest` cover the boundary. Reproduce the isolated
original SDK and browser journeys after building:

```sh
./gradlew check installDist qualificationJar
python3 scripts/check_core_sdk.py
python3 scripts/check_dashboard.py --journey imports
```

The library dependency must be available at the path configured by `settings.gradle.kts`.
The loopback fixture uses fresh test credentials and no AWS or external provider account.

Original advanced access/delegate imports, public Timeline Access document registration and the
latched native integrity-error recovery lifecycle remain incomplete. Existing AWS-bound owned
reader registrations are unchanged. A rollback after managed imports requires a binary supporting
the new managed polling selector; an older binary fails closed. Native identities/history formats
have not changed.
