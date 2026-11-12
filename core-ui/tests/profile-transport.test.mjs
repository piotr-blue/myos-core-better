import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';

const compiled = (await build({ stdin: { contents: 'export * from "./src/transport"; export * from "./src/account-context"; export * from "./src/user-profile";', resolveDir: process.cwd(), loader: 'ts' }, bundle: true, write: false, format: 'esm' })).outputFiles[0].text;
const sdk = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));

test('generated multipart avatar requests retain browser framing through authenticated transport', async () => {
  globalThis.location = { origin: 'https://console.example.test' };
  sdk.setAccountContext(undefined, { userId: 'anna', sessionContext: 'user-guard' });
  const body = new FormData(); body.append('file', new Blob(['image'], { type: 'image/png' }), 'avatar.png'); body.append('expectedRevision', '2');
  const calls = [];
  globalThis.fetch = async (path, options) => { calls.push({ path, options }); return Response.json({ profileRevision: 3 }); };
  await sdk.authenticatedFetch('/api/blue/v1/me/avatar', { method: 'PUT', body, headers: { 'Idempotency-Key': 'upload-intent' } });
  assert.equal(new Headers(calls[0].options.headers).has('Content-Type'), false, 'Fetch owns the multipart boundary');
  assert.equal(new Headers(calls[0].options.headers).get('X-MyOS-Session-Context'), 'user-guard');
  assert.equal(calls[0].options.body, body);
});

test('late profile response cannot update a replacement User session even without an account switch', async () => {
  const stored = new Map();
  globalThis.sessionStorage = { getItem: key => stored.get(key) ?? null, setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) };
  const user = { userId: 'anna', sessionContext: 'original-user-guard' };
  sdk.setAccountContext(undefined, user);
  let finish, started;
  const ready = new Promise(resolve => { started = resolve; });
  const pending = sdk.userProfileCommand(user, 'profile', {}, () => { started(); return new Promise(resolve => { finish = resolve; }); });
  await ready;
  sdk.setAccountContext(undefined, { userId: 'bob', sessionContext: 'replacement-guard' });
  finish({ userId: 'anna', firstName: 'Updated', profileRevision: 2 });
  await assert.rejects(pending, failure => failure.name === 'AbortError');
});

test('avatar preflight keeps byte-size bounds for image files with ambiguous MIME', () => {
  for (const type of ['', 'application/octet-stream']) {
    assert.match(sdk.avatarFileError({ type, size: 0 }), /nonempty/);
    assert.match(sdk.avatarFileError({ type, size: 5 * 1024 * 1024 + 1 }), /5 MiB/);
  }
});
