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

test('terminal owner dispositions complete polling after a prior accepted assessment', async () => {
  for (const outcome of ['FAILED', 'INAPPLICABLE', 'IGNORED', 'GAS_LIMIT_EXCEEDED', 'TERMINATED']) {
    const before = { ...pending(), assessment: { outcome: 'ACCEPTED' } };
    const done = { ...before, disposition: { outcome, reason: 'native-result', epoch: 3 } };
    const states = [before, done];
    assert.equal(await waitForNativeInput(api(states), 's', 'e', fast), done);
    assert.equal(states.length, 0);
  }
});

test('stable owner history requires the three exact references from the same position', async () => {
  const position = { stable: true, epoch: 8, kind: 'PROCESS',
    positionRef: { blueId: 'position' }, documentRef: { blueId: 'document' },
    receiptRef: { blueId: 'receipt' }, predecessorRef: { blueId: 'earlier' } };
  const observed = p => ({ ...pending(), localHistory: [p] });
  const done = observed(position);
  const states = [
    observed({ ...position, stable: false }),
    observed({ ...position, receiptRef: null }),
    observed({ ...position, documentRef: {} }),
    observed({ ...position, positionRef: { blueId: '' } }),
    done,
  ];
  assert.equal(await waitForNativeInput(api(states), 's', 'e', fast), done);
  assert.equal(states.length, 0);
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
