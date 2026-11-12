import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const compile = async (entry) => (await build({ entryPoints: [entry], bundle: true, write: false, format: 'esm' })).outputFiles[0].text;
const compiled = await compile('src/api.ts');
const recovery = await compile('src/service-recovery.ts');
const fresh = async (source = compiled) => import('data:text/javascript;base64,' + Buffer.from(source + '\n//' + Math.random()).toString('base64'));
const response = (status, body = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

test('read waits through a deployment failure and resumes without surfacing a transient error', async () => {
  let calls = 0;
  globalThis.fetch = async () => ++calls === 1 ? response(503) : response(200, { value: 2 });
  const { api } = await fresh();
  assert.deepEqual(await api('documents'), { value: 2 });
  assert.equal(calls, 2);
});
test('a write with an uncertain network outcome retains its exact body and idempotency key', async () => {
  const attempts = [];
  globalThis.fetch = async (path, options) => {
    attempts.push({ path, body: options.body, key: new Headers(options.headers).get('Idempotency-Key') });
    if (attempts.length === 1) throw new TypeError('Connection closed after commit');
    return response(200, { status: 'succeeded' });
  };
  const { api } = await fresh();
  await api('documents/x/operations', { method: 'POST', body: '{"operation":"bump"}', headers: new Headers({ 'Idempotency-Key': 'one-intent' }) });
  assert.equal(attempts.length, 2);
  assert.deepEqual(attempts[0], attempts[1]);
  assert.equal(attempts[0].key, 'one-intent');
});
test('authentication rejection and business validation errors are not hidden by recovery', async () => {
  const { api } = await fresh();
  for (const status of [400, 403, 409, 422]) {
    let calls = 0;
    globalThis.fetch = async () => { calls++; return response(status); };
    await assert.rejects(api('documents'), (error) => error.status === status);
    assert.equal(calls, 1);
  }
});
test('mail actions and writes without a durable key are never automatically replayed', async () => {
  const { api } = await fresh();
  for (const path of ['/api/auth/resend', 'documents']) {
    let calls = 0;
    globalThis.fetch = async () => { calls++; throw new TypeError('Unknown outcome'); };
    await assert.rejects(api(path, { method: 'POST', body: '{}' }));
    assert.equal(calls, 1);
  }
});
test('a transient session-refresh failure does not log the user out', async () => {
  let refreshes = 0, authenticated = false;
  const paths = [];
  globalThis.fetch = async (path) => {
    paths.push(path);
    if (path === '/api/auth/refresh') {
      if (++refreshes === 1) return response(503);
      authenticated = true;
      return response(200);
    }
    return authenticated ? response(200, { accountId: 'same-account' }) : response(401);
  };
  const { api } = await fresh();
  assert.deepEqual(await api('info'), { accountId: 'same-account' });
  assert.equal(refreshes, 2);
  assert.equal(paths.filter((path) => path.endsWith('/info')).length, 2);
});
test('aborting recovery cancels the wait, clears its notice and never sends a second request', async () => {
  const { withServiceRecovery, serviceWaiting, subscribeServiceWaiting } = await fresh(recovery);
  const controller = new AbortController();
  const states = [];
  const unsubscribe = subscribeServiceWaiting(() => states.push(serviceWaiting()));
  let calls = 0;
  const promise = withServiceRecovery(async () => { calls++; throw Object.assign(new Error('recovering'), { status: 503 }); }, { signal: controller.signal });
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(serviceWaiting(), true);
  controller.abort();
  await assert.rejects(promise, { name: 'AbortError' });
  assert.equal(calls, 1);
  assert.equal(serviceWaiting(), false);
  assert.deepEqual(states, [true, false]);
  unsubscribe();
});
test('a prolonged outage has a finite deadline and leaves no waiting notice behind', async (t) => {
  const { withServiceRecovery, serviceWaiting } = await fresh(recovery);
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 0 });
  let calls = 0;
  const pending = withServiceRecovery(async () => {
    calls++;
    throw Object.assign(new Error('recovering'), { status: 503 });
  }, { timeoutMs: 15 });
  const rejected = assert.rejects(pending, /recovering/);
  await Promise.resolve();
  assert.equal(serviceWaiting(), true);
  t.mock.timers.tick(14);
  await Promise.resolve();
  assert.equal(calls, 1);
  assert.equal(serviceWaiting(), true);
  t.mock.timers.tick(1);
  await rejected;
  assert.equal(calls, 1);
  assert.equal(serviceWaiting(), false);
});
test('the default recovery window permits a retry after the measured 199-second DEV outage', async (t) => {
  const { withServiceRecovery, serviceWaiting } = await fresh(recovery);
  let now = Date.now(), calls = 0;
  t.mock.method(Date, 'now', () => now);
  const result = await withServiceRecovery(async () => {
    if (++calls === 1) {
      now += 199000;
      throw Object.assign(new Error('recovering'), { status: 503 });
    }
    return 'recovered';
  });
  assert.equal(result, 'recovered');
  assert.equal(calls, 2);
  assert.equal(serviceWaiting(), false);
});
test('a stalled response body is aborted at the request deadline', async () => {
  const { withServiceRecovery, serviceWaiting } = await fresh(recovery);
  await assert.rejects(withServiceRecovery((signal) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true });
  }), { timeoutMs: 15 }));
  assert.equal(serviceWaiting(), false);
});
