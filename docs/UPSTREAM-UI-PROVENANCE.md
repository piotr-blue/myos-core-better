# Reused dashboard source

The post-login UI is copied from local MyOS Core revision
`8b71a0e7de48aaeb79bddddb25ae04e0fc0f2d85`.

Production files under `core-ui/src` retain their original bytes except:

- `portal-auth.tsx`: `PortalSignIn` mounts the existing Better Cognito form. It does not redirect to the original public site.
- `live.ts`: graph-root changes invalidate an open embedded member without refreshing unrelated documents.
- `process-packages.ts` and new `data/process-better.json`: select the exact reviewed Better runtime and deployed package roots; unsupported lesson dependencies remain explicit.
- New `better-auth.tsx`, `better-auth-api.ts`, `better-auth.css`: preserve the existing Better login/signup experience. CSS is restricted to authentication elements.

`main.tsx`, signed-in components, styles, operation forms, resource readers,
workspace selection and generated SDK are reused without replacement.
The SDK is pinned at its original 0.3.1; no generation or publication was performed.
`account-ui` is the same pinned archive used by MyOS Core.

Test changes:

- `live.test.mjs`: one additional graph/member invalidation test. 
- `process-packages.test.mjs`: verifies the exact Better selection and rejects altered runtime identities and unavailable roots.
- Four login-boundary assertions in `workspace-loading.test.mjs` now test the
  explicitly retained Better form and destination preservation.
- `lesson-model.test.mjs` reads a copied fixture for the public examples instead
  of requiring the separate marketing-portal source tree.
- `observability-react-roots.test.mjs` tests the separate public portal root,
  which is outside this port. It is retained under `docs/upstream-reference`
  and not counted among the 363 passing dashboard tests.

The original OpenAPI and package-selection catalogue fixtures are copied with
unchanged bytes. They define the parity target, not a declaration that Better
already implements every advertised capability. See `dashboard-api-parity.json`.

Backend reuse in iteration 19: `AvatarUpload`, `AvatarImages`, `HttpDeadline`,
`HttpCancellation` and the six `HttpDeadlineTest` scenarios come from the same source revision.
`CoreImagesTest`/`CoreImagesHttpTest` adapt the original `HostAvatarsTest`/`AvatarHttpTest`
contracts to Better's AtomicStore and HTTP fixture. Storage publication differs as documented
in `DASHBOARD-PARITY.md`; original SQL/tenant infrastructure is not copied or simulated.
