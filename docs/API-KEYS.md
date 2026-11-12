# Application API keys

## Current dashboard contract

New keys are owned by their issuing user and the selected account membership. Their authority follows
the current role; removal disables them, and rejoining never revives keys from a previous membership
generation. The `myos_better_u1_` format is deliberately rejected by earlier runtimes on rollback.
Legacy `myos_better_` keys remain account credentials until explicitly revoked; they are not silently
attributed to a user. Existing administrators can inspect/revoke those legacy keys.

Use the original Core SDK or `/api/blue/v1/api-keys` create/list/delete routes. A human member can manage
only their own new keys; keys cannot manage credentials. The native list is complete without query
parameters, with an explicit error beyond 4096 records rather than silent truncation. Explicit
`limit`/`pageToken` remain a Better compatibility extension. The legacy `/api/api-keys` paths retain
their metadata envelope and paginated shape; existing listing cursors should be restarted after upgrade.

New creation receipts retain the exact response encrypted for a 24-hour replay window. Retry the
same user/account command and name to recover a lost response; revocation, generation change or
expiry makes replay return `409 replay_expired`. Plaintext bearer secrets are never stored. The
master is the retained, version-pinned host secret configured through `infra/webhook-secret.json`,
with a distinct API-key encryption domain. Webhook ciphertext and native HMAC formats are unchanged.
Missing custody rejects new issuance. Keep the pinned key for future receipt recovery.

New records add `CORE_USER_KEYS#<account>#<user>` metadata/receipt partitions and owner/generation
fields to `API_KEY#<id>` authority. No document, timeline, canonical history or runtime semantics
change. Every key request verifies its digest and current membership without an authorization cache.
The single-owner console now drains in-flight account work before a revocation returns success.
Queued requests reauthenticate inside the account barrier, including legacy and native owner routes.
Uploads and response transport remain outside that barrier; bytes selected before revocation may
finish travelling afterward. Already-admitted native timeline work is not cancelled. A 30-second
barrier wait returns a retryable error rather than falsely acknowledging revocation. Membership
mutation and multi-host writer coordination remain separate work. Old runtime versions reject new key tokens, while legacy
keys continue to follow their existing contract.

The implementation and current qualification are tracked in [DASHBOARD-PARITY.md](DASHBOARD-PARITY.md).

## Historical account-only implementation

The following describes the original Better account-key API before membership-bound credentials.
Its persistence and replay behavior remains applicable to retained legacy keys only.

The console's **API Keys** page follows MyOS Core's create/list/revoke flow. Sign in, enter a name,
create a key and copy the secret. The secret is displayed only in that response and is cleared when
you leave or reload the page. The key list contains names, prefixes and status, never secrets.

Keys use the same account-owned document and Timeline endpoints as the console:

```bash
export MYOS_URL='https://d2ya9daeiur7nz.cloudfront.net'
# Set MYOS_API_KEY from the newly created secret in your shell's environment.
curl "$MYOS_URL/api/documents" \
  -H "Authorization: Bearer $MYOS_API_KEY"

curl "$MYOS_URL/api/timelines" \
  -H "Authorization: Bearer $MYOS_API_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: application-events-1' \
  --data '{"name":"Application events"}'
```

Supply a stable `Idempotency-Key` when retrying a document or Timeline mutation. API-key clients need
no browser Origin header. If they supply an Origin, it must match the console. Browser cookie
mutations still require same-origin JSON. Keys are accepted only through `Authorization: Bearer`,
not cookies or URL parameters. Invalid authorization never falls back to a cookie session.

The management endpoints are `GET /api/api-keys`, `POST /api/api-keys` with `{"name":"..."}`, and
`DELETE /api/api-keys/{id}` with an empty JSON object. They require an interactive owner identity;
a key cannot create, list or revoke credentials. Key listing is paginated with `nextCursor` and
`?cursor=...`. These paths retain Better's current API envelope and are not a claim of full Core SDK
wire compatibility or Core's multi-account membership support.

## Persistence and recovery

The service generates a 256-bit secret with the JDK secure random generator and stores only a SHA-256
digest. An independent random identifier selects the authority record. Creation atomically publishes
authority, account-local metadata and its command receipt. Revocation atomically updates authority
and listing. A strongly consistent read checks every bearer request; there is no credential cache.
A request authenticated before revocation may finish. Requests authenticated after its commit fail.

Retrying a successful creation returns its metadata and `secretUnavailable: true`; it cannot recover
a secret from a digest. If the first response was lost, revoke that key and create a replacement.
Changing the name under the same command is rejected. Concurrent identical creation publishes one
credential. Revocation is idempotent and survives a lost response or process restart.

This adds DynamoDB records in `API_KEY#<id>` and `API_KEYS#<account>` partitions; existing document,
Timeline, history and provider-identity records are unchanged. The prior runtime ignores these
records on rollback. Rolling back disables bearer-key access until a supporting runtime returns.

`ApiKeysTest` owns lifecycle, lost publication replies, concurrency, validation, pagination and
metadata secrecy. `ConsoleApiKeysTest` owns the real HTTP authentication/account/CSRF boundary and
actual document/Timeline calls. `core-ui/scripts/qualify-api-keys.mjs` uses a separate QA account,
creates a temporary key in the real console, calls documents without browser cookies, verifies
revocation and cleans up the key without writing its secret to reports.

## Iteration 134 verification

Decision: **keep and deploy**. The full host suite passed 383 tests; all four frontend intent tests
passed. The promoted build produced the same host artifact as the tested staging tree. Actual AWS
browser checks passed key creation/listing/reload, cookie-free create/call/read, refusal of credential
management by a key, immediate subsequent-request revocation and the mobile layout. The test key
was revoked in cleanup. The existing full console journey and signed native reader checks also passed.

Only `ConsoleServer` changed among existing host classes; `ApiKeys`, its identity record and
`ConsolePrincipal` are new. Document runtime/persistence bytecode and the library are unchanged.
There is no performance improvement claim or new large-graph campaign for this authentication change.
Each key-authenticated request adds one strongly consistent authority read. Ordinary cookie requests
do not perform that key lookup. The user's agreement was not mutated by these QA-account checks.

Evidence is indexed by `reports/overnight-2026-11-05/manifest-134.json`; the API-key screenshot and
reports are in its `console-parity-134/` folder. This completes the API-key part of the current request,
not the requested participant bootstrap, exact console parity or Activation/Documents/Payment tutorial.
