import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { readFileSync } from 'node:fs';

// Keep the real account adapter, transport and SDK together: mocking accountResult
// previously hid the loss of ResponseError before the SDK's projection retry.
const bundle = await build({ stdin: { contents: `
  export { LessonClient } from './src/lessons/client';
  export { setAccountContext } from './src/account-context';
`, resolveDir: process.cwd(), loader: 'ts' }, bundle: true, write: false, format: 'esm' });
const { LessonClient, setAccountContext } = await import('data:text/javascript;base64,' + Buffer.from(bundle.outputFiles[0].text + '\n//# sourceURL=lesson-admission-client.js').toString('base64'));
const lesson = JSON.parse(readFileSync('src/data/clause-examples.json')).find(row => row.id === '00-hello');
const runtime = JSON.parse(readFileSync('src/data/process-runtimes.json')).finalGas;
const account = { accountId: 'workspace', userId: 'human', sessionContext: 'context', membership: { generation: 1, revision: 1 } };
const pending = { admissionId: 'accepted', kind: 'DOCUMENT_BOOTSTRAP', status: 'PENDING', completionScope: 'REGISTRATION_STORED', details: null, problem: null };
const ready = { sessionId: 'root', lastStable: { documentRef: { blueId: 'version' } }, processing: { state: 'READY' } };
const makeRun = () => ({ id: 'run', userId: 'human', exampleId: lesson.id, definition: JSON.stringify([lesson.source, lesson.packages, lesson.steps]), identities: { owner: 'workspace' }, representatives: {}, step: 0, source: lesson.source, results: [] });
const absent = (code = 'ADMISSION_NOT_FOUND', status = 404) => Response.json({ code, detail: 'Unavailable receipt' }, { status });
function fixture(read, receipt = pending, observe = () => undefined) {
  const writes = [], reads = [];
  globalThis.fetch = async (url, init) => {
    if (url.endsWith('/info')) return Response.json({ accountId: 'workspace', runtime });
    if (init.method === 'POST') {
      writes.push({ at: performance.now(), body: init.body, key: new Headers(init.headers).get('Idempotency-Key') });
      return Response.json(receipt, { status: 202 });
    }
    if (url.endsWith('/admissions/accepted')) {
      reads.push(performance.now());
      return read(reads.length);
    }
    const observation = observe(url);
    if (observation) return observation;
    if (url.includes('/inputs/')) return Response.json({ localHistory: [{ stable: true }] });
    assert.ok(url.endsWith('/documents/root'), url);
    return Response.json(ready);
  };
  return { writes, reads };
}
beforeEach(() => {
  globalThis.location = new URL('https://core.example.test/developers/');
  globalThis.sessionStorage = { setItem() {}, getItem() { return null; } };
  setAccountContext(account);
});

test('examples wait through initial admission projection lag at two-second intervals without resubmission', async () => {
  const run = makeRun(), updates = [];
  const { writes, reads } = fixture(n => n === 1 ? absent() : Response.json(n === 2 ? pending : { ...pending, status: 'SUCCEEDED', details: { sessionId: 'root' } }));
  await new LessonClient(text => updates.push(text)).start(run, lesson);
  assert.equal(writes.length, 1);
  assert.equal(reads.length, 3);
  for (const [i, time] of reads.entries()) assert.ok(time - (i ? reads[i - 1] : writes[0].at) >= 1950, 'No rapid polling, including after 404');
  assert.equal(run.root, 'root');
  assert.equal(run.results.length, 1);
  assert.equal(run.pending, undefined);
  assert.ok(updates.includes('Core admission: pending'));
  assert.ok(!updates.some(text => text.includes('404')));
});

test('Stop during projection lag retains the accepted receipt and resume only reads', async () => {
  const run = makeRun(), controller = new AbortController();
  const { writes, reads } = fixture(() => { setTimeout(() => controller.abort(), 50); return absent(); });
  await assert.rejects(new LessonClient(() => {}, controller.signal).start(run, lesson), /not canceled/);
  assert.equal(writes.length, 1); assert.equal(reads.length, 1);
  assert.equal(run.pending.admission.admissionId, 'accepted');
  const resumed = fixture(() => Response.json({ ...pending, status: 'SUCCEEDED', details: { sessionId: 'root' } }));
  await new LessonClient(() => {}).start(run, lesson);
  assert.equal(resumed.writes.length, 0); assert.equal(resumed.reads.length, 1);
  assert.equal(run.results[0].interrupted, true);
});

test('other 404s, access denials and FAILED admissions remain errors', async () => {
  for (const [response, matches] of [
    [absent('ACCOUNT_NOT_FOUND'), e => e.status === 404 && e.code === 'ACCOUNT_NOT_FOUND'],
    [absent('MEMBERSHIP_INACTIVE', 403), e => e.status === 403],
    [Response.json({ ...pending, status: 'FAILED', problem: { detail: 'Rejected command' } }), e => /FAILED/.test(e.message)],
  ]) {
    const run = makeRun(), { writes, reads } = fixture(() => response);
    await assert.rejects(new LessonClient(() => {}).start(run, lesson), matches);
    assert.equal(writes.length, 1); assert.equal(reads.length, 1);
    assert.equal(run.results.length, 0); assert.equal(run.pending.admission.admissionId, 'accepted');
  }
});

test('a receipt disappearing after its first observation is not hidden as projection lag', async () => {
  const run = makeRun(), { reads } = fixture(n => n === 1 ? Response.json(pending) : absent());
  await assert.rejects(new LessonClient(() => {}).start(run, lesson), e => e.status === 404 && e.code === 'ADMISSION_NOT_FOUND');
  assert.equal(reads.length, 2); assert.equal(run.results.length, 0);
});

test('changing workspace during a missing receipt stops the wait', async () => {
  const run = makeRun(), { reads } = fixture(() => {
    setAccountContext({ ...account, accountId: 'different' });
    return absent();
  });
  await assert.rejects(new LessonClient(() => {}).start(run, lesson), /account session changed/);
  assert.equal(reads.length, 1); assert.equal(run.results.length, 0);
});

test('operation examples also retain one submission while admission projection catches up', async () => {
  const run = { ...makeRun(), root: 'root' }, operation = { ...pending, kind: 'OPERATION', completionScope: 'TIMELINE_ENTRY_STORED' };
  const step = { operation: 'approve', channel: 'owner', request: {} };
  const { writes, reads } = fixture(n => n === 1 ? absent() : Response.json({ ...operation, status: 'SUCCEEDED', details: { entryRef: { blueId: 'input' } } }), operation);
  await new LessonClient(() => {}).runStep(run, { steps: [step] }, () => {}, {
    sessionId: 'root', document: ready, route: { channel: 'owner', timeline: 'timeline', actor: 'human' }, step,
  });
  assert.equal(writes.length, 1); assert.equal(reads.length, 2);
  assert.equal(run.step, 1); assert.equal(run.results[0].entryId, 'input');
});

test('after acceptance, bootstrap and input outcome checks also wait two seconds without another write', async () => {
  for (const operation of [false, true]) {
    const run = { ...makeRun(), ...(operation ? { root: 'root' } : {}) }, observations = [];
    const completed = { ...pending, status: 'SUCCEEDED', details: operation ? { entryRef: { blueId: 'input' } } : { sessionId: 'root' } };
    const { writes, reads } = fixture(() => assert.fail('The returned admission already succeeded'), completed, url => {
      assert.ok(operation ? url.endsWith('/inputs/input') : url.endsWith('/documents/root'), url);
      observations.push(performance.now());
      return Response.json(operation
        ? { localHistory: observations.length === 1 ? [] : [{ stable: true }] }
        : observations.length === 1 ? { sessionId: 'root', documentId: null, lastStable: null, processing: { state: 'WAITING_COMPLETENESS' } } : ready);
    });
    const client = new LessonClient(() => {});
    const step = { operation: 'approve', channel: 'owner', request: {} };
    if (operation) await client.runStep(run, { steps: [step] }, () => {}, {
      sessionId: 'root', document: ready, route: { channel: 'owner', timeline: 'timeline', actor: 'human' }, step,
    });
    else await client.start(run, lesson);
    assert.equal(writes.length, 1);
    assert.equal(reads.length, 0);
    assert.equal(observations.length, 2);
    assert.ok(observations[1] - observations[0] >= 1950, 'Outcome polling must not resume at 250 ms after admission');
    assert.equal(run.results.length, 1);
    assert.equal(run.pending, undefined);
  }
});
