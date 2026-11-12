import { test } from 'node:test';
import assert from 'node:assert/strict';
import { browserObservability } from '../observability/browser.mjs';

const now = 1_800_000_000_000;
const api = 'https://core.api.dev.beta.myos.blue/api/blue/v1/documents';
const flush = async () => { for (let i = 0; i < 6; i++) await Promise.resolve(); };

function fixture(t, load, origin = 'https://dev.beta.myos.blue', env = 'dev') {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now });
  const window = new EventTarget();
  window.localStorage = { getItem: () => null };
  const restoreGlobals = [];
  for (const [name, value] of Object.entries({ window, location: { origin }, BroadcastChannel: class {} })) {
    const previous = Object.getOwnPropertyDescriptor(globalThis, name);
    Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
    restoreGlobals.push(() => previous ? Object.defineProperty(globalThis, name, previous) : delete globalThis[name]);
  }
  const calls = [];
  let configuration;
  const sdk = {
    init(value) { configuration = value; calls.push('init'); },
    stopSessionReplayRecording() { calls.push('stop'); },
    startSessionReplayRecording() { calls.push('start'); },
    setTrackingConsent(value) { calls.push(value); },
    addError() { calls.push('error'); },
    startView() { calls.push('view'); },
  };
  const observability = browserObservability({ env }, load ? () => load(sdk) : async () => sdk);
  t.after(() => { observability.setAuthenticated(false); restoreGlobals.forEach(restore => restore()); });
  return { observability, calls, window, configuration: () => configuration };
}

test('only a future verified epoch-millisecond deadline grants service recording', async t => {
  const f = fixture(t);
  for (const invalid of [undefined, null, '1800000060000', NaN, Infinity, now, now - 1, now / 1000, now + 0.5]) {
    f.observability.setAuthenticated(true, invalid);
    await flush();
    assert.deepEqual(f.calls, [], 'Malformed or absent expiry must not load the SDK');
  }
  f.observability.setAuthenticated(true, now + 60_000);
  await flush();
  assert.ok(f.calls.includes('start'));
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), true);
});

test('known expiry stops stale service recording without changing authentication state', async t => {
  const f = fixture(t);
  let authenticationEvents = 0;
  f.window.addEventListener('myos-session-ended', () => authenticationEvents++);
  f.observability.setAuthenticated(true, now + 60_000);
  await flush();
  t.mock.timers.tick(59_999);
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), true);
  t.mock.timers.tick(1);
  assert.deepEqual(f.calls.slice(-2), ['stop', 'not-granted']);
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), false);
  f.observability.reportError(new Error('not exported after expiry'));
  assert.equal(f.calls.includes('error'), false);
  assert.equal(authenticationEvents, 0, 'Observability must not log out the user or mutate auth');
});

test('only newly verified discovery extends the deadline and logout cancels it', async t => {
  const f = fixture(t);
  f.observability.setAuthenticated(true, now + 60_000);
  await flush();
  t.mock.timers.tick(30_000);
  f.observability.setAuthenticated(true, now + 120_000);
  t.mock.timers.tick(30_000);
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), true);
  assert.equal(f.calls.filter(value => value === 'start').length, 1);
  f.window.dispatchEvent(new Event('myos-session-ended'));
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), false);
  t.mock.timers.tick(120_000);
  assert.equal(f.calls.filter(value => value === 'start').length, 1, 'A former deadline cannot restore collection');
});

test('expiry while SDK loading cannot start stale Replay even before its timer runs', async t => {
  let ready;
  const f = fixture(t, sdk => new Promise(resolve => { ready = () => resolve(sdk); }));
  f.observability.setAuthenticated(true, now + 60_000);
  t.mock.timers.setTime(now + 60_001); // Simulate a delayed browser timer after suspension.
  ready();
  await flush();
  assert.equal(f.calls.includes('start'), false);
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), false);
});

test('resume rechecks the verified deadline and long expiries do not overflow timers', async t => {
  const f = fixture(t);
  const deadline = now + 2_147_483_647 + 60_000;
  f.observability.setAuthenticated(true, deadline);
  await flush();
  t.mock.timers.tick(2_147_483_647);
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), true);
  t.mock.timers.setTime(deadline + 1);
  f.window.dispatchEvent(new Event('focus'));
  assert.deepEqual(f.calls.slice(-2), ['stop', 'not-granted']);
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), false);
});

test('auth routes revoke manual views and synchronous resource tracing before route effects', async t => {
  const f = fixture(t);
  f.observability.setAuthenticated(true, now + 60_000);
  await flush();
  const views = f.calls.filter(value => value === 'view').length;
  globalThis.location.href = 'https://dev.beta.myos.blue/sign-in?email=private@example.test';
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), false);
  assert.equal(f.configuration().beforeSend({ type: 'error' }), false);
  f.observability.syncView();
  assert.deepEqual(f.calls.slice(-2), ['stop', 'not-granted']);
  assert.equal(f.calls.filter(value => value === 'view').length, views);
  globalThis.location.href = 'https://dev.beta.myos.blue/developers/app/#documents/opaque-id';
  f.observability.syncView();
  assert.equal(f.calls.filter(value => value === 'view').length, views + 1);
  assert.equal(f.configuration().allowedTracingUrls[0].match(api), true);
});

test('PROD public replay still stops on credential screens and does not grant authenticated tracing', async t => {
  const origin = 'https://myos.blue';
  const f = fixture(t, undefined, origin, 'prod');
  f.observability.setPublicPage(true);
  await flush();
  assert.ok(f.calls.includes('start'));
  assert.equal(f.configuration().allowedTracingUrls[0].match('https://core.api.beta.myos.blue/api/blue/v1/documents'), false);
  for (const path of ['/sign-in', '/developers/app/#login', '/developers/app/#signup', '/developers/app/#reset']) {
    const starts = f.calls.filter(value => value === 'start').length;
    globalThis.location.href = origin + path;
    f.observability.syncView();
    assert.deepEqual(f.calls.slice(-2), ['stop', 'not-granted']);
    assert.equal(f.calls.filter(value => value === 'start').length, starts);
    globalThis.location.href = origin + '/';
    f.observability.syncView();
    assert.equal(f.calls.at(-1), 'start');
  }
});

for (const origin of ['https://dev.myos.blue', 'https://dev.beta.myos.blue', 'https://myos.blue', 'https://foreign.invalid']) {
  test(`DEV recording accepts only the two reviewed frontend aliases: ${origin}`, async t => {
    const f = fixture(t, undefined, origin);
    f.observability.setAuthenticated(true, now + 60_000);
    await flush();
    const allowed = origin === 'https://dev.myos.blue' || origin === 'https://dev.beta.myos.blue';
    assert.equal(f.calls.includes('start'), allowed);
    if (allowed) assert.equal(f.configuration().allowedTracingUrls[0].match(api), true);
  });
}
