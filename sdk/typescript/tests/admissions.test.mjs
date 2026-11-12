import assert from 'node:assert/strict';
import test from 'node:test';
import { setTimeout as sleep } from 'node:timers/promises';
import { AdmissionFailed, AdmissionsApi, Configuration, ResponseError, waitForAdmission } from '@blue-labs/myos-sdk';

const pending = { admissionId: 'receipt-1', kind: 'DOCUMENT', status: 'PENDING', completionScope: 'REGISTERED', details: null, problem: null };
const succeeded = { ...pending, status: 'SUCCEEDED', details: { sessionId: 'session-1' } };
const fast = { timeoutMs: 1000, pollIntervalMs: 1 };
const absent = () => new ResponseError(new Response(JSON.stringify({ code: 'ADMISSION_NOT_FOUND' }), { status: 404 }));

test('returns an already completed typed receipt without fetching', async () => {
  const api = { getAdmission() { assert.fail('Unexpected request'); } };
  assert.equal(await waitForAdmission(api, succeeded, fast), succeeded);
});

test('polls the same admission and reports only status transitions', async () => {
  const responses = [pending, succeeded];
  const updates = [];
  const api = { async getAdmission(request, init) {
    assert.equal(request.admissionId, pending.admissionId);
    assert.ok(init.signal instanceof AbortSignal);
    return responses.shift();
  } };
  assert.equal(await waitForAdmission(api, pending, { ...fast, onUpdate: value => updates.push(value.status) }), succeeded);
  assert.deepEqual(updates, ['PENDING', 'SUCCEEDED']);
});

test('waits through initial projection absence for the already accepted receipt', async () => {
  const responses = [absent(), absent(), pending, succeeded];
  const updates = [];
  let reads = 0;
  const api = { async getAdmission(request, init) {
    assert.equal(request.admissionId, pending.admissionId);
    assert.ok(init.signal instanceof AbortSignal);
    reads++;
    const response = responses.shift();
    if (response instanceof Error) throw response;
    return response;
  } };
  assert.equal(await waitForAdmission(api, pending, { ...fast, onUpdate: value => updates.push(value.status) }), succeeded);
  assert.equal(reads, 4);
  assert.deepEqual(updates, ['PENDING', 'SUCCEEDED']);
});

test('propagates absence after the first projected receipt without another read', async () => {
  const missing = absent();
  let reads = 0;
  const api = { async getAdmission() {
    if (++reads === 1) return pending;
    throw missing;
  } };
  await assert.rejects(waitForAdmission(api, pending, fast), error => error === missing);
  assert.equal(reads, 2);
  assert.equal(missing.response.bodyUsed, false);
});

test('propagates other errors and malformed absence problems unchanged', async () => {
  const cases = [
    ...[401, 403, 409, 503].map(status => new ResponseError(new Response(JSON.stringify({ code: 'ADMISSION_NOT_FOUND' }), { status }))),
    ...['{"code":"ROUTE_NOT_FOUND"}', '{"code":"admission_not_found"}', '{}', 'null', '[]', '{', '{"code":"ADMISSION_NOT_FOUND"} trailing'].map(body =>
      new ResponseError(new Response(body, { status: 404 }))),
    new Error('transport failed'),
  ];
  for (const failure of cases) {
    let reads = 0;
    await assert.rejects(waitForAdmission({ async getAdmission() { reads++; throw failure; } }, pending, fast), error => error === failure);
    assert.equal(reads, 1);
    if (failure instanceof ResponseError) assert.equal(failure.response.bodyUsed, false);
  }
});

test('persistent initial absence remains bounded and raw getAdmission still rejects', async () => {
  let reads = 0;
  const api = new AdmissionsApi(new Configuration({ basePath: 'https://example.test', fetchApi: async () => {
    reads++;
    return new Response(JSON.stringify({ code: 'ADMISSION_NOT_FOUND' }), { status: 404 });
  } }));
  await assert.rejects(api.getAdmission({ admissionId: pending.admissionId }), error =>
    error instanceof ResponseError && error.response.status === 404);
  await assert.rejects(waitForAdmission(api, pending, { ...fast, timeoutMs: 100 }), /receipt-1; last status PENDING.*not canceled/);
  assert.ok(reads > 1);
});

test('caller cancellation interrupts initial projection absence', async () => {
  const controller = new AbortController();
  let reads = 0;
  const api = { async getAdmission() { reads++; controller.abort(); throw absent(); } };
  await assert.rejects(waitForAdmission(api, pending, { ...fast, signal: controller.signal }), /not canceled/);
  assert.equal(reads, 1);
});

test('preserves the server failure receipt and problem', async () => {
  const failed = { ...pending, status: 'FAILED', problem: { code: 'INVALID_DOCUMENT', detail: 'Invalid source' } };
  await assert.rejects(waitForAdmission({ getAdmission: async () => failed }, pending, fast), error => {
    assert.ok(error instanceof AdmissionFailed);
    assert.equal(error.admission, failed);
    assert.match(error.message, /INVALID_DOCUMENT/);
    return true;
  });
});

test('rejects mismatched identity or command kind', async () => {
  for (const receipt of [{ ...succeeded, admissionId: 'other' }, { ...succeeded, kind: 'TIMELINE' }]) {
    await assert.rejects(waitForAdmission({ getAdmission: async () => receipt }, pending, fast), /does not match/);
  }
});

test('deadline aborts an in-flight read without suggesting server cancellation', async () => {
  let signal;
  const api = { async getAdmission(_request, init) {
    signal = init.signal;
    await sleep(5000, undefined, { signal });
    assert.fail('Read should have been aborted');
  } };
  await assert.rejects(waitForAdmission(api, pending, { ...fast, timeoutMs: 30 }), /receipt-1; last status PENDING.*not canceled/);
  assert.equal(signal.aborted, true);
});

test('caller cancellation interrupts polling', async () => {
  const controller = new AbortController();
  const api = { async getAdmission() { controller.abort(); return pending; } };
  await assert.rejects(waitForAdmission(api, pending, { ...fast, signal: controller.signal }), /not canceled/);
});

test('does not read demo environment settings', async () => {
  const previous = process.env.MYOS_DEMO_TIMEOUT_MS;
  process.env.MYOS_DEMO_TIMEOUT_MS = 'invalid';
  try {
    assert.equal(await waitForAdmission({}, succeeded), succeeded);
  } finally {
    if (previous === undefined) delete process.env.MYOS_DEMO_TIMEOUT_MS;
    else process.env.MYOS_DEMO_TIMEOUT_MS = previous;
  }
});

test('rejects unknown statuses and invalid timing', async () => {
  await assert.rejects(waitForAdmission({}, { ...pending, status: 'UNKNOWN' }, fast), /Unknown admission status/);
  for (const options of [{ timeoutMs: 0 }, { timeoutMs: NaN }, { pollIntervalMs: -1 }]) {
    await assert.rejects(waitForAdmission({}, pending, options), /must be positive/);
  }
});
