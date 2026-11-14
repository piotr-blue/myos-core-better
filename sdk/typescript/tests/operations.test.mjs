import assert from 'node:assert/strict';
import test from 'node:test';
import { setTimeout as sleep } from 'node:timers/promises';
import { submitReviewedOperation } from '@blue-labs/myos-sdk';

const exactReview = { sessionId: 's', documentVersion: 'reviewed#2', operation: 'buy',
  route: { timeline: 't', actor: 'agent', channel: 'buyer' }, request: { amountMinor: 200 },
  idempotencyKey: 'recover-this-command' };

test('later edits to the caller review cannot redirect observation to another session', async () => {
  const review = structuredClone(exactReview);
  const observed = { sessionId: 's', entryBlueId: 'entry', assessment: { outcome: 'REJECTED' },
    disposition: null, localHistory: [], processing: { state: 'READY' } };
  const documents = {
    async invokeOperation(input) {
      assert.equal(input.sessionId, 's');
      review.sessionId = 'different-session';
      return { admissionId: 'a', status: 'SUCCEEDED', kind: 'OPERATION',
        details: { entryRef: { blueId: 'entry' } } };
    },
    async getDocumentInputsEntryblueid(input) {
      assert.deepEqual(input, { sessionId: 's', entryBlueId: 'entry' });
      return observed;
    },
  };
  assert.equal(await submitReviewedOperation(documents, {}, review), observed);
});

test('invalid wait options and pre-cancellation fail before submitting a write', async () => {
  let submissions = 0;
  const documents = { async invokeOperation() {
    submissions++;
    return { admissionId: 'a', status: 'PENDING', kind: 'OPERATION' };
  } };
  for (const options of [{ timeoutMs: 0 }, { pollIntervalMs: -1 }, { signal: AbortSignal.abort() }]) {
    await assert.rejects(submitReviewedOperation(documents, {}, exactReview, options));
  }
  assert.equal(submissions, 0, 'invalid or cancelled calls must not publish a command');
});

test('one deadline also bounds the initial submission without retrying the write', async () => {
  let submissions = 0;
  let submittedSignal;
  const documents = { async invokeOperation(input, { signal }) {
    submissions++;
    submittedSignal = signal;
    assert.equal(input.idempotencyKey, exactReview.idempotencyKey);
    await sleep(5000, undefined, { signal });
    assert.fail('A deadline must reach the initial request');
  } };
  await assert.rejects(submitReviewedOperation(documents, {}, exactReview, { timeoutMs: 30 }), /not canceled/);
  assert.equal(submittedSignal.aborted, true);
  assert.equal(submissions, 1);
});

test('the original deadline still aborts native observation after admission has succeeded', async t => {
  const deadlines = [];
  t.mock.method(AbortSignal, 'timeout', () => {
    const controller = new AbortController();
    deadlines.push(controller);
    return controller.signal;
  });
  const signals = [];
  const documents = {
    async invokeOperation(_input, { signal }) {
      assert.ok(signal instanceof AbortSignal);
      signals.push(signal);
      return { admissionId: 'a', status: 'PENDING', kind: 'OPERATION' };
    },
    async getDocumentInputsEntryblueid(_input, { signal }) {
      signals.push(signal);
      deadlines[0].abort(new DOMException('Original operation deadline', 'TimeoutError'));
      signal.throwIfAborted();
      assert.fail('The first deadline must survive both stage changes');
    },
  };
  const admissions = { async getAdmission(_input, { signal }) {
    signals.push(signal);
    return { admissionId: 'a', status: 'SUCCEEDED', kind: 'OPERATION', details: { entryRef: { blueId: 'entry' } } };
  } };
  await assert.rejects(submitReviewedOperation(documents, admissions, exactReview, { pollIntervalMs: 1 }), /identical review and idempotency key/);
  assert.equal(signals.length, 3);
  assert.ok(signals.every(signal => signal.aborted));
});

test('an incomplete review never submits, and an ordinary transport error is preserved', async () => {
  const failure = new Error('Transport response lost');
  let submissions = 0;
  const documents = { async invokeOperation() { submissions++; throw failure; } };
  for (const field of ['sessionId', 'documentVersion', 'operation', 'idempotencyKey']) {
    await assert.rejects(submitReviewedOperation(documents, {}, { ...exactReview, [field]: '' }), /required/);
  }
  assert.equal(submissions, 0);
  await assert.rejects(submitReviewedOperation(documents, {}, exactReview), error => error === failure);
  assert.equal(submissions, 1, 'an ambiguous write must not be repeated automatically');
});

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
