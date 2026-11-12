import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';

const operation = (path = '/api/blue/v1/documents') => ({ method: 'GET', path, parameters: [], responses: {} });
const draft = (auth = { mode: 'session' }) => ({ parameters: {}, body: '', sendBody: false, mediaType: 'application/json', accept: 'application/json', auth });

for (const host of ['dev.myos.blue', 'myos.blue', 'dev.beta.myos.blue', 'beta.myos.blue', '']) {
  const apiHost = host === 'dev.myos.blue' ? 'dev.beta.myos.blue' : host === 'myos.blue' ? 'beta.myos.blue' : host;
  const portal = { base: host ? '/developers/app/' : '/', api: host ? `https://core.api.${apiHost}` : '', web: host ? `https://${host}` : '' };
  const compiled = (await build({ entryPoints: ['src/explorer-request.ts'], bundle: true, write: false, format: 'esm',
    define: { __MYOS_PORTAL_CONFIG__: JSON.stringify(portal) } })).outputFiles[0].text;
  const engine = await import('data:text/javascript;base64,' + Buffer.from(compiled + `\n//# sourceURL=explorer-${host || 'standalone'}.js`).toString('base64'));

  test(`${host || 'standalone'}: document reads use the API origin and session cookies`, async () => {
    const request = engine.buildRequest(operation(), draft());
    const url = portal.api + '/api/blue/v1/documents';
    globalThis.fetch = async (actual, init) => {
      assert.equal(actual, url);
      assert.equal(init.credentials, 'include');
      assert.equal(new Headers(init.headers).get('X-MyOS-Client'), 'console');
      return new Response('{"items":[],"nextPageToken":null}', { status: 200 });
    };
    const response = await engine.executeRequest(request);
    assert.equal(response.status, 200);
    assert.deepEqual(JSON.parse(response.rawBody).items, []);
    assert.equal(response.url, url);
    const previousLocation = globalThis.location;
    try {
      globalThis.location = { origin: portal.web || 'http://localhost:8080' };
      assert.ok(engine.copyCurl(request).includes((portal.api || globalThis.location.origin) + request.url));
    } finally { globalThis.location = previousLocation; }
  });

  test(`${host || 'standalone'}: admission tracking and streams keep the API origin and bearer isolation`, async () => {
    const request = engine.buildRequest(operation(), draft({ mode: 'bearer', token: 'test-only-token' }));
    const admission = engine.admissionRequest(request, 'receipt#0');
    globalThis.fetch = async (actual, init) => {
      assert.equal(actual, portal.api + '/api/blue/v1/admissions/receipt%230');
      assert.equal(init.credentials, 'omit');
      assert.equal(init.headers.Authorization, 'Bearer test-only-token');
      return new Response('{"status":"SUCCEEDED"}');
    };
    await engine.executeRequest(admission);
    globalThis.fetch = async (actual, init) => {
      assert.equal(actual, portal.api + '/api/blue/v1/live');
      assert.equal(init.credentials, 'include');
      return new Response('data: ready\n\n', { headers: { 'Content-Type': 'text/event-stream' } });
    };
    const frames = [];
    await engine.executeStream(engine.buildRequest(operation('/api/blue/v1/live'), draft()), undefined,
      response => assert.equal(response.url, portal.api + '/api/blue/v1/live'), frame => frames.push(frame));
    assert.equal(frames[0].data, 'ready');
    globalThis.fetch = async () => assert.fail('External URLs must never receive credentials');
    await assert.rejects(engine.executeRequest({ ...request, url: 'https://other.test/api/blue/v1/documents' }));
  });
}
