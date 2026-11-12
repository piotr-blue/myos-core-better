import assert from 'node:assert/strict';
import test from 'node:test';
import { setTimeout as sleep } from 'node:timers/promises';
import { waitFor, waitForDocument, ResponseError } from '@blue-labs/myos-sdk';

const fast = { timeoutMs: 1000, pollIntervalMs: 1 };

test('polls values with one signal and returns the matching value', async () => {
  const signals = [];
  let count = 0;
  const result = await waitFor(async signal => {
    signals.push(signal);
    return { count: ++count };
  }, value => value.count === 3, fast);
  assert.deepEqual(result, { count: 3 });
  assert.equal(signals.length, 3);
  assert.ok(signals[0] instanceof AbortSignal);
  assert.ok(signals.every(signal => signal === signals[0]));
});

test('deadline aborts an in-flight read; caller cancellation interrupts backoff', async () => {
  let readSignal;
  await assert.rejects(waitFor(async signal => {
    readSignal = signal;
    await sleep(5000, undefined, { signal });
  }, () => false, { ...fast, timeoutMs: 30 }), /not canceled/);
  assert.equal(readSignal.aborted, true);

  const controller = new AbortController();
  let calls = 0;
  const waiting = waitFor(async () => {
    calls++;
    return false;
  }, Boolean, { ...fast, pollIntervalMs: 5000, signal: controller.signal });
  await sleep(5);
  controller.abort();
  await assert.rejects(waiting, /not canceled/);
  assert.equal(calls, 1);
});

test('pre-cancelled waits make no requests and invalid timers fail early', async () => {
  const read = async () => assert.fail('Unexpected read');
  await assert.rejects(waitFor(read, Boolean, { signal: AbortSignal.abort() }), /not canceled/);
  for (const options of [{ timeoutMs: 0 }, { timeoutMs: 1.5 }, { timeoutMs: Infinity }, { pollIntervalMs: 2 ** 31 }]) {
    await assert.rejects(waitFor(read, Boolean, options), /positive integers/);
  }
});

test('read and predicate errors are preserved without retries', async () => {
  const failure = new ResponseError(new Response('{}', { status: 503 }));
  await assert.rejects(waitFor(async () => { throw failure; }, Boolean, fast), error => error === failure);
  await assert.rejects(waitFor(async () => true, () => { throw failure; }, fast), error => error === failure);
});

test('document polling waits for initialization and forwards its signal', async () => {
  const pending = { sessionId: 'session', documentId: null, lastStable: null, processing: { state: 'PROCESSING' } };
  // A stable snapshot can exist while later work is still processing.
  const initialized = { ...pending, documentId: 'document', lastStable: { documentRef: { blueId: 'stable' } } };
  const states = [pending, initialized];
  const api = { async getDocument(request, { signal }) {
    assert.deepEqual(request, { sessionId: 'session' });
    assert.ok(signal instanceof AbortSignal);
    return states.shift();
  } };
  assert.equal(await waitForDocument(api, 'session', fast), initialized);
});

test('document polling reports initialization failures and aborts a stalled request', async () => {
  for (const state of ['ERROR', 'HOST_INCOMPLETE', 'PAUSED']) {
    const api = { async getDocument() { return { processing: { state, reason: { code: 'EXECUTION_FAILED', message: 'details' } } }; } };
    await assert.rejects(waitForDocument(api, 'session', fast), new RegExp(`session.*${state}.*details`));
  }
  let readSignal;
  const api = { async getDocument(_request, { signal }) {
    readSignal = signal;
    await sleep(5000, undefined, { signal });
  } };
  await assert.rejects(waitForDocument(api, 'session', { ...fast, timeoutMs: 30 }), /not canceled/);
  assert.equal(readSignal.aborted, true);
});
