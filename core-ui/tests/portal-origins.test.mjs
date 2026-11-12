import test from 'node:test';
import assert from 'node:assert/strict';
import { currentPortalOrigin, portalEnvironment } from '../portal-origins.mjs';
import { build } from 'esbuild';

for (const environment of ['dev', 'prod']) {
  test(`${environment}: sign-in stays on either approved alias while API calls keep their original host`, async () => {
    const boundary = portalEnvironment(environment);
    const [canonical, legacy] = boundary.origins;
    const compiled = (await build({ entryPoints: ['src/portal-location.ts'], bundle: true, write: false, format: 'esm',
      define: { __MYOS_PORTAL_CONFIG__: JSON.stringify({ base: '/developers/app/', api: boundary.coreApi, web: canonical }) } })).outputFiles[0].text;
    const location = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
    const previous = globalThis.location;
    try {
      for (const origin of [canonical, legacy]) {
        globalThis.location = { origin, pathname: '/developers/app/', hash: '#documents' };
        const link = new URL(location.sharedSignIn());
        assert.equal(link.origin, origin);
        assert.equal(link.pathname, '/sign-in');
        assert.equal(link.searchParams.get('returnTo'), '/developers/app/#documents');
        assert.equal(location.portalOrigin(), origin);
        assert.equal(location.apiUrl('/api/blue/v1/me'), boundary.coreApi + '/api/blue/v1/me');
      }
    } finally { globalThis.location = previous; }
    for (const other of ['https://foreign.invalid', 'https://myos.blue', 'https://dev.myos.blue']) {
      assert.equal(currentPortalOrigin(canonical, other), boundary.origins.includes(other) ? other : canonical);
    }
  });
}

test('local and standalone portal configurations retain their own origins', () => {
  assert.equal(currentPortalOrigin('https://myos.localhost:4453', 'https://other.localhost:4453'), 'https://myos.localhost:4453');
  assert.equal(currentPortalOrigin('', 'http://localhost:8080'), 'http://localhost:8080');
});
