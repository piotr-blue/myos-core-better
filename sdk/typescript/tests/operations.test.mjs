import assert from 'node:assert/strict';
import test from 'node:test';
import { submitReviewedOperation } from '@blue-labs/myos-sdk';

test('submits the reviewed version, route and authority unchanged, then observes its own input', async () => {
  const review = { sessionId: 's', documentVersion: 'reviewed#2', operation: 'buy',
    route: { timeline: 't', actor: 'agent', channel: 'buyer' }, request: { amountMinor: 200 },
    authority: 'native-mandate-authority', idempotencyKey: 'key' };
  const denied = { sessionId: 's', entryBlueId: 'entry', assessment: { outcome: 'REJECTED', reason: 'STALE_DOCUMENT_PRECONDITION' },
    disposition: null, localHistory: [], processing: { state: 'READY' } };
  let calls = 0;
  const documents = {
    async invokeOperation(input) {
      calls++;
      assert.equal(input.invokeOperation.message.document.blueId, 'reviewed#2');
      assert.equal(input.invokeOperation.message.requireExactDocumentVersion, true);
      assert.equal(input.invokeOperation.authority, review.authority);
      assert.equal(input.invokeOperation.actor, 'agent');
      assert.equal(input.idempotencyKey, 'key');
      return { admissionId: 'a', status: 'PENDING', kind: 'OPERATION' };
    },
    async getDocumentInputsEntryblueid(input) { assert.deepEqual(input, { sessionId: 's', entryBlueId: 'entry' }); return denied; },
  };
  const admissions = { async getAdmission() { return { admissionId: 'a', status: 'SUCCEEDED', kind: 'OPERATION', details: { entryRef: { blueId: 'entry' } } }; } };
  assert.equal(await submitReviewedOperation(documents, admissions, review, { pollIntervalMs: 1 }), denied);
  assert.equal(calls, 1, 'a rejected exact review must never be retried against an unreviewed version');
});
