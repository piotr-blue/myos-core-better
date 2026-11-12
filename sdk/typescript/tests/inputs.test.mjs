import assert from 'node:assert/strict';
import test from 'node:test';
import { waitForNativeInput } from '@blue-labs/myos-sdk';

const fast = { timeoutMs: 1000, pollIntervalMs: 1 };
const pending = () => ({ sessionId: 's', entryBlueId: 'e', assessment: null, disposition: null,
  localHistory: [], processing: { state: 'READY' } });
const row = { stable: true, document: 'd', position: 'p', receipt: 'r' };
function api(observations) {
  return { async getDocumentInputsEntryblueid(request, { signal }) {
    assert.deepEqual(request, { sessionId: 's', entryBlueId: 'e' });
    assert.ok(signal instanceof AbortSignal);
    assert.ok(observations.length, 'unexpected extra read');
    return observations.shift();
  } };
}

test('READY and nonstable history do not complete an input; its stable receipt does', async () => {
  const done = { ...pending(), localHistory: [row] };
  const states = [pending(), { ...pending(), localHistory: [{ ...row, stable: false }] }, done];
  assert.equal(await waitForNativeInput(api(states), 's', 'e', fast), done);
  assert.equal(states.length, 0);
});

test('retained assessment is terminal even after a newer input replaces latest disposition', async () => {
  for (const outcome of ['REJECTED', 'EXECUTION_REJECTED', 'NO_MATCH']) {
    const denied = { ...pending(), assessment: { outcome, reason: 'native-reason' } };
    assert.equal(await waitForNativeInput(api([denied]), 's', 'e', fast), denied);
  }
});

test('unrelated session/input and incomplete evidence cannot count as success', async () => {
  for (const change of [{ entryBlueId: 'other' }, { sessionId: 'other' }]) {
    await assert.rejects(waitForNativeInput(api([{ ...pending(), ...change, localHistory: [row] }]),
      's', 'e', fast), /does not match/);
  }
  const states = [{ ...pending(), localHistory: [{ ...row, receipt: null }] }, { ...pending(), localHistory: [row] }];
  await waitForNativeInput(api(states), 's', 'e', fast);
  assert.equal(states.length, 0);
});

test('blocked processing reports a blocker, never business completion', async () => {
  for (const state of ['ERROR', 'HOST_INCOMPLETE', 'PAUSED']) {
    await assert.rejects(waitForNativeInput(api([{ ...pending(), processing: { state } }]),
      's', 'e', fast), new RegExp(state));
  }
});
