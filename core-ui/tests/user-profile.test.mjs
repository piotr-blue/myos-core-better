import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

const compiled = (await build({
  entryPoints: ['src/main.tsx'], bundle: true, write: false,
  format: 'iife', platform: 'browser', jsx: 'automatic',
  loader: { '.css': 'empty' }, define: { 'process.env.NODE_ENV': '"production"' },
})).outputFiles[0].text;
const membership = { generation: 1, revision: 1, role: 'ADMIN', status: 'ACTIVE' };
const account = { accountId: 'workspace', kind: 'BUSINESS', profile: { displayName: 'Studio', website: 'https://studio.example', address: 'Main street' }, revision: 1, membership, userId: 'anna', sessionContext: 'selected-guard', accountModel: 'MEMBERSHIPS_V1', sessionExpiresAt: 4102444800000, capabilities: {} };
const user = { userId: 'anna', email: 'anna@example.test', firstName: 'Anna', lastName: 'Kowalska', imageUrl: 'https://console.example.test/api/blue/v1/users/anna/avatar/v1', profileRevision: 1, sessionContext: 'user-guard', sessionExpiresAt: account.sessionExpiresAt, accountModel: account.accountModel, preferences: { defaultAccountId: null, revision: 1 }, accounts: [] };
async function until(predicate, message) {
  const deadline = Date.now() + 2000;
  while (!predicate() && Date.now() < deadline) await delay(5);
  assert.ok(predicate(), message);
}
function openConsole(t, { selected, route = 'settings/profile', request } = {}) {
  const dom = new JSDOM('<div id="root"></div>', { url: `https://console.example.test/#${route}`, runScripts: 'outside-only', pretendToBeVisual: true });
  const { window } = dom;
  window.URL.createObjectURL = () => 'blob:preview';
  window.URL.revokeObjectURL = () => {};
  t.after(() => window.close());
  window.Headers = Headers; window.Request = Request; window.Response = Response;
  window.TextEncoder = TextEncoder; window.TextDecoder = TextDecoder;
  Object.defineProperty(window, 'crypto', { value: webcrypto });
  window.BroadcastChannel = class { close() {} postMessage() {} };
  window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  window.HTMLDialogElement.prototype.close = function () { this.open = false; };
  window.scrollTo = () => {};
  let currentUser = structuredClone(user);
  if (selected) {
    currentUser.accounts = [selected]; currentUser.preferences.defaultAccountId = selected.accountId;
    window.sessionStorage.setItem('myos-core-account', JSON.stringify({ accountId: selected.accountId, userId: user.userId, generation: 1 }));
  }
  const calls = [];
  window.fetch = async (path, options = {}) => {
    calls.push({ path, options });
    const custom = await request?.(path, options);
    if (custom) return custom;
    if (path === '/api/config') return Response.json({});
    if (path === '/api/blue/v1/me') return Response.json(currentUser);
    if (path === '/api/blue/v1/account') return Response.json(selected);
    if (path === '/api/blue/v1/live') return new Promise(() => {});
    if (path === '/api/blue/v1/packages') return Response.json({ items: [] });
    throw new Error(`Unexpected request: ${path}`);
  };
  window.eval(compiled);
  return { window, document: window.document, calls, updateUser: next => { currentUser = next; } };
}
function edit(app, name, value) {
  const input = app.document.querySelector(`input[name="${name}"]`);
  assert.ok(input, `${name} field exists`);
  Object.getOwnPropertyDescriptor(app.window.HTMLInputElement.prototype, 'value').set.call(input, value);
  input.dispatchEvent(new app.window.Event('input', { bubbles: true }));
}
const button = (app, text) => [...app.document.querySelectorAll('button')].find(item => item.textContent === text);

test('Agent settings preserve multiple identities and scope pause and disconnect to the selected Agent', async t => {
  const builtin = { accountId: account.accountId, agentId: 'builtin', kind: 'BUILT_IN', role: 'READ_ALL', inclusion: true, execution: 'DISABLED', actorStatus: 'UNQUALIFIED', revision: 1 };
  let dot = { ...builtin, agentId: 'dot', kind: 'EXTERNAL', name: 'Dot', role: 'READ_SELECTED', inclusion: false, execution: 'EXTERNAL', actorStatus: 'QUALIFIED', status: 'ACTIVE', revision: 4 };
  const connection = { connectionId: 'link', agentId: dot.agentId, clientId: 'openai', status: 'ACTIVE', scopes: ['documents:read'], revision: 3 };
  const app = openConsole(t, { selected: account, route: 'settings/agents', request: (path, options) => {
    if (path.endsWith('/account/agents')) return Response.json({ items: [builtin, dot] });
    if (path.endsWith('/account/agents/dot') && options.method === 'PATCH') {
      assert.deepEqual(JSON.parse(options.body), { status: 'PAUSED', expectedRevision: 4 });
      dot = { ...dot, status: 'PAUSED', revision: 5 }; return Response.json(dot);
    }
    if (path.endsWith('/dot/connections')) return Response.json({ items: [connection] });
    if (path.endsWith('/dot/connections/link') && options.method === 'DELETE') {
      assert.deepEqual(JSON.parse(options.body), { expectedRevision: 3 });
      return Response.json({ ...connection, status: 'REVOKED', revision: 4 });
    }
  } });
  await until(() => button(app, 'Dot') && button(app, 'MyOS Agent'), 'External and built-in Agents load together');
  assert.equal(button(app, 'Dot').getAttribute('aria-expanded'), 'false');
  assert.equal(button(app, 'Pause Agent'), undefined, 'Details are collapsed until an agent is selected');
  button(app, 'Dot').click();
  await until(() => button(app, 'Pause Agent'), 'The selected agent expands');
  assert.equal(button(app, 'Dot').getAttribute('aria-expanded'), 'true');
  assert.equal(button(app, 'MyOS Agent').getAttribute('aria-expanded'), 'false');
  assert.equal(app.document.querySelector('section[aria-label="Dot"] select').value, 'READ_SELECTED');
  button(app, 'Pause Agent').click();
  await until(() => button(app, 'Resume Agent'), 'Pause response updates the same identity');
  await until(() => button(app, 'Disconnect'), 'Only this Agent connections are shown');
  button(app, 'Disconnect').click();
  await until(() => app.document.body.textContent.includes('Disconnected'), 'Revoke acknowledgement is reflected');
  const mutations = app.calls.filter(call => ['PATCH', 'DELETE'].includes(call.options.method));
  assert.equal(mutations.length, 2);
  for (const call of mutations) {
    const headers = new Headers(call.options.headers);
    assert.equal(headers.get('X-MyOS-Account'), account.accountId);
    assert.equal(headers.get('X-MyOS-Session-Context'), account.sessionContext);
    assert.ok(headers.get('Idempotency-Key'));
  }
});

test('self settings opens before account creation and uses the User profile, not an account avatar', async t => {
  const app = openConsole(t);
  await until(() => app.document.querySelector('input[name="firstName"]'), 'A User with no accounts can edit their profile');
  assert.equal(app.document.querySelector('input[name="firstName"]').value, 'Anna');
  assert.equal(app.document.querySelector('input[name="email"]').readOnly, true);
  assert.equal(app.document.querySelector('input[name="email"]').value, user.email);
  assert.equal(app.document.querySelector('[aria-label="Account menu"] img').src, user.imageUrl);
  assert.equal(app.calls.filter(call => call.path.endsWith('/account')).length, 0);
  assert.ok(app.document.querySelector('a[href="#accounts"]'));
});

test('User profile is unchanged when the selected workspace changes', async t => {
  for (const selected of [account, { ...account, accountId: 'other', profile: { displayName: 'Other company' } }]) {
    const app = openConsole(t, { selected });
    await until(() => app.document.querySelector('input[name="firstName"]'), 'Profile opens with a workspace');
    assert.equal(app.document.querySelector('input[name="firstName"]').value, user.firstName);
    assert.equal(app.document.querySelector('[aria-label="Account menu"] img').src, user.imageUrl);
  }
});

test('verified email refresh updates its read-only field without replacing a profile draft', async t => {
  const app = openConsole(t);
  await until(() => app.document.querySelector('input[name="firstName"]'), 'Profile opens');
  edit(app, 'firstName', 'Unsaved Anna');
  const refreshed = { ...user, email: 'anna.verified@example.test' };
  app.updateUser(refreshed);
  app.window.dispatchEvent(new app.window.Event('focus'));
  await until(() => app.document.querySelector('input[name="email"]').value === refreshed.email,
    'Verified email updates even though profileRevision and session guard are unchanged');
  assert.equal(app.document.querySelector('input[name="email"]').readOnly, true);
  assert.equal(app.document.querySelector('input[name="firstName"]').value, 'Unsaved Anna');
  assert.equal(app.document.querySelector('[aria-label="Account menu"] img').src, user.imageUrl);
  assert.equal(button(app, 'Save my profile').disabled, false);
  assert.equal(button(app, 'Reload latest profile'), undefined, 'Authority email refresh is not a profile conflict');
  assert.equal(app.calls.some(call => ['PATCH', 'PUT', 'DELETE', 'POST'].includes(call.options.method)), false);
});

test('profile saves use the User guard and preserve edits on revision conflict', async t => {
  const app = openConsole(t, { selected: account, request: (path, options) => path.endsWith('/me/profile') ? Response.json({ title: 'revision_conflict', detail: 'The profile changed in another session.' }, { status: 409 }) : undefined });
  await until(() => app.document.querySelector('input[name="firstName"]'), 'Profile opens');
  edit(app, 'firstName', 'Ania');
  button(app, 'Save my profile').click();
  await until(() => app.document.querySelector('[role="alert"]'), 'Conflict is visible');
  assert.equal(app.document.querySelector('input[name="firstName"]').value, 'Ania');
  assert.ok(button(app, 'Reload latest profile'));
  const call = app.calls.find(call => call.path.endsWith('/me/profile'));
  assert.equal(new Headers(call.options.headers).get('X-MyOS-Session-Context'), user.sessionContext);
  assert.equal(new Headers(call.options.headers).has('X-MyOS-Account'), false);
  assert.ok(new Headers(call.options.headers).get('Idempotency-Key'));
  assert.deepEqual(JSON.parse(call.options.body), { firstName: 'Ania', lastName: 'Kowalska', expectedRevision: 1 });
});

test('website is an Account field and only admins can edit it', async t => {
  for (const role of ['ADMIN', 'READ']) {
    const app = openConsole(t, { selected: { ...account, membership: { ...membership, role } }, route: 'settings/accounts' });
    await until(() => app.document.querySelector('input[name="website"]'), 'Account website is shown');
    const website = app.document.querySelector('input[name="website"]');
    assert.equal(website.value, account.profile.website);
    assert.equal(website.disabled, role !== 'ADMIN');
    assert.equal(app.document.querySelector('input[name="firstName"]'), null);
  }
});

test('avatar upload retains its intent after an unknown response and updates the menu only on success', async t => {
  let attempts = 0, finish;
  const next = { ...user, imageUrl: 'https://console.example.test/api/blue/v1/users/anna/avatar/v2', profileRevision: 2 };
  const app = openConsole(t, { request: (path, options) => {
    if (!path.endsWith('/me/avatar')) return;
    if (++attempts === 1) throw new TypeError('Response lost');
    return new Promise(resolve => { finish = () => { app.updateUser(next); resolve(Response.json(next)); }; });
  } });
  await until(() => app.document.querySelector('input[type="file"]'), 'Avatar input opens');
  const input = app.document.querySelector('input[type="file"]');
  const file = new app.window.File(['png-data'], 'avatar.png', { type: 'image/png' });
  file.arrayBuffer = async () => new TextEncoder().encode('png-data').buffer;
  Object.defineProperty(input, 'files', { configurable: true, value: [file] });
  input.dispatchEvent(new app.window.Event('change', { bubbles: true }));
  await until(() => !button(app, 'Save avatar').disabled, 'Valid upload is enabled');
  button(app, 'Save avatar').click();
  await until(() => app.document.querySelector('[role="alert"]'), 'Unknown upload result is visible');
  assert.equal(app.document.querySelector('[aria-label="Account menu"] img').src, user.imageUrl);
  assert.equal(input.files[0], file, 'Retry retains the selected file');
  button(app, 'Save avatar').click();
  await until(() => attempts === 2, 'Upload retry starts');
  assert.equal(app.document.querySelector('input[name="firstName"]').disabled, true);
  assert.equal(button(app, 'Remove avatar').disabled, true);
  const uploads = app.calls.filter(call => call.path.endsWith('/me/avatar'));
  assert.equal(new Headers(uploads[0].options.headers).get('Idempotency-Key'), new Headers(uploads[1].options.headers).get('Idempotency-Key'));
  assert.equal(new Headers(uploads[1].options.headers).has('Content-Type'), false);
  assert.equal(uploads[1].options.body.get('expectedRevision'), '1');
  assert.equal(uploads[1].options.body.get('file').name, 'avatar.png');
  finish();
  await until(() => app.document.querySelector('[aria-label="Account menu"] img')?.src === next.imageUrl, 'Confirmed avatar is shown in the User menu');
  assert.equal(button(app, 'Save avatar').disabled, true, 'Successful upload clears the pending file');
});

test('remove avatar is revision checked and keeps unsaved name edits', async t => {
  let finishRemove;
  const next = { ...user, imageUrl: null, profileRevision: 2 };
  const app = openConsole(t, { request: (path, options) => {
    if (path.endsWith('/me/avatar') && options.method === 'DELETE')
      return new Promise(resolve => { finishRemove = () => { app.updateUser(next); resolve(Response.json(next)); }; });
  } });
  await until(() => app.document.querySelector('input[name="firstName"]'), 'Profile opens');
  edit(app, 'firstName', 'Unsaved Anna');
  button(app, 'Remove avatar').click();
  await until(() => finishRemove && button(app, 'Removing…')?.disabled, 'Avatar removal is pending');
  assert.equal(app.document.querySelector('[aria-label="Account menu"] img')?.src, user.imageUrl, 'Pending removal retains the current avatar');
  assert.equal(app.document.querySelector('input[name="firstName"]').disabled, true);
  finishRemove();
  await until(() => app.document.querySelector('[aria-label="Account menu"] img') === null &&
    app.document.querySelector('input[name="firstName"]')?.disabled === false, 'Avatar removal and User refresh complete');
  assert.equal(app.document.querySelector('input[name="firstName"]').value, 'Unsaved Anna');
  assert.ok(app.document.querySelector('[aria-label="Account menu"] img') === null, 'Removed avatar is absent from the User menu');
  const request = app.calls.find(call => call.options.method === 'DELETE');
  assert.deepEqual(JSON.parse(request.options.body), { expectedRevision: 1 });
});

test('unsupported avatar files are rejected without a profile write', async t => {
  const app = openConsole(t);
  await until(() => app.document.querySelector('input[type="file"]'), 'Avatar input opens');
  const input = app.document.querySelector('input[type="file"]');
  Object.defineProperty(input, 'files', { value: [new app.window.File(['<svg/>'], 'avatar.svg', { type: 'image/svg+xml' })] });
  input.dispatchEvent(new app.window.Event('change', { bubbles: true }));
  await until(() => app.document.querySelector('[role="alert"]'), 'Invalid format is explained');
  assert.equal(button(app, 'Save avatar').disabled, true);
  assert.equal(app.calls.filter(call => call.path.endsWith('/me/avatar')).length, 0);
});

for (const mime of ['', 'application/octet-stream']) test(`unlabelled or generic PNG (${mime || 'empty MIME'}) reaches multipart upload`, async t => {
  const next = { ...user, imageUrl: 'https://console.example.test/api/blue/v1/users/anna/avatar/v2', profileRevision: 2 };
  const app = openConsole(t, { request: path => {
    if (path.endsWith('/me/avatar')) { app.updateUser(next); return Response.json(next); }
  } });
  await until(() => app.document.querySelector('input[type="file"]'), 'Avatar input opens');
  const bytes = Uint8Array.from(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=', 'base64'));
  const file = new app.window.File([bytes], 'avatar.png', { type: mime });
  file.arrayBuffer = async () => bytes.buffer;
  const input = app.document.querySelector('input[type="file"]');
  Object.defineProperty(input, 'files', { value: [file] });
  input.dispatchEvent(new app.window.Event('change', { bubbles: true }));
  await until(() => !button(app, 'Save avatar').disabled, 'Core must validate a PNG whose browser MIME is unavailable');
  button(app, 'Save avatar').click();
  await until(() => app.document.querySelector('[aria-label="Account menu"] img')?.src === next.imageUrl, 'Successful Core response installs the avatar');
  const upload = app.calls.find(call => call.path.endsWith('/me/avatar'));
  assert.equal(upload.options.body.get('file').type, mime);
  assert.equal(upload.options.body.get('file').size, bytes.byteLength);
  assert.equal(new Headers(upload.options.headers).has('Content-Type'), false);
});

test('ambiguous MIME preserves Core image validation errors and the prior avatar', async t => {
  const app = openConsole(t, { request: path => path.endsWith('/me/avatar')
    ? Response.json({ title: 'invalid_avatar', detail: 'The uploaded bytes are not a supported image.' }, { status: 422 }) : undefined });
  await until(() => app.document.querySelector('input[type="file"]'), 'Avatar input opens');
  const file = new app.window.File(['not an image'], 'avatar.png', { type: 'application/octet-stream' });
  file.arrayBuffer = async () => new TextEncoder().encode('not an image').buffer;
  const input = app.document.querySelector('input[type="file"]');
  Object.defineProperty(input, 'files', { value: [file] });
  input.dispatchEvent(new app.window.Event('change', { bubbles: true }));
  await until(() => !button(app, 'Save avatar').disabled, 'Core decides the actual format');
  button(app, 'Save avatar').click();
  await until(() => app.document.querySelector('[role="alert"]')?.textContent === 'The uploaded bytes are not a supported image.', 'Backend validation error is preserved');
  assert.equal(app.document.querySelector('[aria-label="Account menu"] img').src, user.imageUrl);
  assert.equal(input.files[0], file);
});
