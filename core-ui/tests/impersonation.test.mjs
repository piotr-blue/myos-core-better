import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const compiled = (await build({ stdin: { contents: 'export * from "./src/transport"; export * from "./src/impersonation-state"; export * from "./src/explorer-request";', resolveDir: process.cwd(), loader: 'ts' }, bundle: true, write: false, format: 'esm' })).outputFiles[0].text;
const fresh = () => import('data:text/javascript;base64,' + Buffer.from(compiled + '\n//' + Math.random()).toString('base64'));
const token = 'core_s_' + 'a'.repeat(43);
function storage(value) { const map = new Map(value ? [['myos-impersonation', JSON.stringify(value)]] : []); globalThis.sessionStorage = { getItem: key => map.get(key) ?? null, setItem: (key, v) => map.set(key, v), removeItem: key => map.delete(key) }; return map; }
const session = () => ({ token, userId: 'target', email: 'target@example.test', expiresAt: Date.now() + 3600000 });
test('delegation sends the tab bearer and captured workspace guard while omitting the operator cookie', async () => {
  storage(session()); const sdk = await fresh(); let sent;
  globalThis.fetch = async (_url, options) => { sent = options; return Response.json({}); };
  await sdk.authenticatedFetch('documents', {}, { userId: 'target', accountId: 'workspace', sessionContext: 'target-guard' }, false);
  assert.equal(sent.credentials, 'omit'); const headers = new Headers(sent.headers);
  assert.equal(headers.get('Authorization'), `Bearer ${token}`); assert.equal(headers.get('X-MyOS-Account'), 'workspace'); assert.equal(headers.get('X-MyOS-Session-Context'), 'target-guard');
});
test('revoked delegation never refreshes or falls back to the ordinary cookie, including after reload', async () => {
  const state = storage(session()); const paths = []; globalThis.fetch = async url => { paths.push(url); return Response.json({}, { status: 401 }); };
  const sdk = await fresh(); assert.equal((await sdk.authenticatedFetch('documents')).status, 401);
  assert.equal(JSON.parse(state.get('myos-impersonation')).ended, true);
  const reloaded = await fresh(); assert.equal((await reloaded.authenticatedFetch('documents')).status, 401);
  assert.deepEqual(paths, ['/api/blue/v1/documents']);
});
test('expired or malformed retained delegation fails closed before network access', async () => {
  const sdk = await fresh(); globalThis.fetch = () => assert.fail('An ended delegation cannot send a request');
  const state = storage({ ...session(), expiresAt: Date.now() - 1 });
  assert.equal((await sdk.authenticatedFetch('documents')).status, 401);
  state.set('myos-impersonation', 'broken'); assert.equal((await sdk.authenticatedFetch('documents')).status, 401);
});
test('launch strips the credential fragment and inherited workspace choices before API work', async () => {
  const state = storage(); for (const key of ['myos-core-account', 'myos-core-account-choice-required', 'myos:selected-account', 'myos-account-choice-required']) state.set(key, 'old-account');
  globalThis.location = { pathname: '/developers/app/', hash: '#impersonate=' + encodeURIComponent(JSON.stringify(session())) };
  let replacement; globalThis.history = { replaceState: (_state, _unused, path) => { replacement = path; } };
  const sdk = await fresh(); sdk.captureImpersonation(); assert.equal(replacement, '/developers/app/#documents'); assert.equal(state.size, 1); assert.equal(sdk.impersonation().token, token);
});
test('ending delegation uses its base guard even after the selected workspace is no longer available', async () => {
  const state = storage({ ...session(), sessionContext: 'base-delegate' });
  let destination; globalThis.location = { origin: 'https://portal.example.test', replace: value => { destination = value; } };
  const source = (await build({ stdin: { contents: 'export {endImpersonation} from "./src/impersonation"; export {setAccountContext} from "./src/account-context";', resolveDir: process.cwd(), loader: 'tsx' }, loader: { '.css': 'empty' }, jsx: 'automatic', bundle: true, write: false, format: 'esm' })).outputFiles[0].text;
  const sdk = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  sdk.setAccountContext({ userId: 'target', accountId: 'removed-workspace', sessionContext: 'removed-workspace-guard', membership: { generation: 1, revision: 1 } });
  let sent; globalThis.fetch = async (_url, options) => { sent = options; return Response.json({ ended: true }); };
  await sdk.endImpersonation();
  const headers = new Headers(sent.headers);
  assert.equal(headers.get('X-MyOS-Session-Context'), 'base-delegate');
  assert.equal(headers.has('X-MyOS-Account'), false);
  assert.equal(destination, 'https://portal.example.test/?returnToAdmin=1#admin');
  assert.equal(JSON.parse(state.get('myos-impersonation')).ended, true);
});

test('Explorer session requests, admission polling and streams use only the impersonated bearer', async () => {
  storage(session()); const sdk = await fresh(); const calls = [];
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response('data: ready\n\n', { headers: { 'Content-Type': 'text/event-stream' } }); };
  const operation = { method: 'GET', path: '/api/blue/v1/documents', parameters: [], responses: {} };
  const draft = { parameters: {}, body: '', sendBody: false, mediaType: 'application/json', accept: 'application/json', auth: { mode: 'session' } };
  const request = sdk.buildRequest(operation, draft);
  await sdk.executeRequest(request); await sdk.executeRequest(sdk.admissionRequest(request, 'receipt'));
  await sdk.executeStream(sdk.buildRequest({ ...operation, path: '/api/blue/v1/live' }, draft), undefined, () => {}, () => {});
  for (const { options } of calls) { assert.equal(options.credentials, 'omit'); assert.equal(new Headers(options.headers).get('Authorization'), `Bearer ${token}`); }
  assert.equal(sdk.copyCurl(request).includes(token), false);
  sdk.endLocalImpersonation(); assert.equal((await sdk.executeRequest(request)).status, 401); assert.equal(calls.length, 3);
  const explicit = sdk.buildRequest(operation, { ...draft, auth: { mode: 'bearer', token: 'explicit-fixture-key' } });
  await sdk.executeRequest(explicit); assert.equal(new Headers(calls.at(-1).options.headers).get('Authorization'), 'Bearer explicit-fixture-key');
});

test('admin return bootstrap cannot clear an active delegation and clears ended context only on the return route', async () => {
  const state = storage(session()); const sdk = await fresh();
  globalThis.location = new URL('https://portal.example.test/developers/app/?returnToAdmin=1#admin');
  globalThis.history = { replaceState() {} };
  assert.equal(sdk.captureAdminReturn(), false);
  assert.equal(sdk.impersonation().token, token);
  sdk.endLocalImpersonation();
  globalThis.location.href = 'https://portal.example.test/developers/app/#admin';
  assert.equal(sdk.captureAdminReturn(), false);
  assert.equal(sdk.impersonation().ended, true);
  globalThis.location.href = 'https://portal.example.test/developers/app/?returnToAdmin=1#admin';
  state.set('myos-core-account', 'delegated-workspace');
  assert.equal(sdk.captureAdminReturn(), true);
  assert.equal(state.size, 0);
});

for (const replacement of ['removed', 'replaced']) test(`restored delegated document fails closed when storage is ${replacement}`, async () => {
  const original = session(); const state = storage(original); const sdk = await fresh();
  assert.equal(sdk.impersonation().token, token);
  const newer = JSON.stringify({ ...original, token: 'core_s_' + 'b'.repeat(43), userId: 'other' });
  if (replacement === 'removed') state.delete('myos-impersonation');
  else state.set('myos-impersonation', newer);
  globalThis.fetch = () => assert.fail('A restored delegation must not adopt the operator cookie or a newer session');
  assert.equal((await sdk.authenticatedFetch('me')).status, 401);
  assert.equal(sdk.impersonation().ended, true);
  assert.equal(state.get('myos-impersonation'), replacement === 'removed' ? undefined : newer);
  state.set('myos-impersonation', JSON.stringify(original));
  assert.equal((await sdk.authenticatedFetch('me')).status, 401);
  assert.equal(sdk.impersonation().ended, true);
});
