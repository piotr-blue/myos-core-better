import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import * as sdk from '../dist/index.js';
import { accept } from '../../../scripts/core-checkpoint-sdk-acceptance.mjs';

function fixture(change = {}) {
  const packet = {
    accounts: { seller: { accountId: 'seller-account' }, customer: { accountId: 'customer-account' } },
    documents: [{ account: 'seller', sessionId: 'session-1', documentRef: { blueId: 'document-1' },
      positionRef: { blueId: 'position-1' }, epoch: 3 }],
    replays: [{ account: 'seller', command: { sessionId: 'session-1', idempotencyKey: 'original-command',
      invokeOperation: { message: { amount: 100 } } }, admissionId: 'admission-1', entryBlueId: 'entry-1' }],
    denials: [{ account: 'customer', sessionId: 'session-1', status: 404 }],
  };
  let posts = 0;
  const requests = [];
  const fetchApi = async (url, options) => {
    requests.push({ url, options });
    assert(options.signal instanceof AbortSignal);
    const path = new URL(url).pathname;
    const seller = options.headers.Authorization === 'Bearer seller-key';
    assert(seller || options.headers.Authorization === 'Bearer customer-key');
    let body, status = 200;
    const receipt = { admissionId: 'admission-1', status: 'SUCCEEDED', kind: 'OPERATION',
      details: { entryRef: { blueId: 'entry-1' } } };
    if (path.endsWith('/info')) body = { accountId: seller ? 'seller-account' : 'customer-account' };
    else if (path.endsWith('/evidence')) body = {
      target: { positionRef: { blueId: change.position ?? 'position-1' }, epoch: 3 },
      items: [{ kind: 'CONTENT', ref: { blueId: change.content ?? 'document-1' } }],
      ...(change.repeatedCursor ? { nextPageToken: 'repeated' } : {}),
    };
    else if (path.endsWith('/admissions/admission-1')) body = { ...receipt, status: change.admissionStatus ?? 'SUCCEEDED' };
    else if (path.endsWith('/operations')) {
      assert.equal(options.method, 'POST');
      assert.equal(options.headers['Idempotency-Key'], 'original-command');
      assert.deepEqual(JSON.parse(options.body), packet.replays[0].command.invokeOperation);
      posts += 1;
      body = { ...receipt, admissionId: change.replayAdmission ?? 'admission-1' };
    } else if (path.endsWith('/documents/session-1')) {
      if (!seller) { status = change.denial ?? 404; body = {}; }
      else body = { processing: { state: 'READY' }, lastStable: {
        documentRef: { blueId: change.afterReplay && posts ? 'changed-document' : 'document-1' }, epoch: 3,
      } };
    } else throw Error('Unexpected SDK route');
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  };
  const clients = {};
  for (const role of ['seller', 'customer']) {
    const config = new sdk.Configuration({ basePath: 'https://core.invalid', accessToken: role + '-key', fetchApi });
    clients[role] = { accounts: new sdk.AccountApi(config), documents: new sdk.DocumentsApi(config), admissions: new sdk.AdmissionsApi(config) };
  }
  return { packet, clients, requests, posts: () => posts };
}

test('preflight checks retained accounts, evidence and admission without writing', async () => {
  const f = fixture();
  const result = await accept(f.packet, f.clients, sdk, 'before');
  assert.deepEqual(result, { status: 'PASS', mode: 'before', accounts: 2, documents: 1,
    replays: 0, retainedAdmissions: 1, denials: 1 });
  assert.equal(f.posts(), 0);
});

test('after restore checks exact idempotent replay and unchanged stable result', async () => {
  const f = fixture();
  const result = await accept(f.packet, f.clients, sdk, 'after');
  assert.equal(result.replays, 1);
  assert.equal(f.posts(), 1);
  assert.equal(f.requests.filter(r => r.options.method === 'GET' && r.url.endsWith('/documents/session-1')).length, 3);
});

test('missing content, wrong position, repeated pages and unfinished receipts cannot qualify', async () => {
  for (const change of [{ content: 'wrong' }, { position: 'wrong' }, { repeatedCursor: true }, { admissionStatus: 'FAILED' }]) {
    const f = fixture(change);
    await assert.rejects(accept(f.packet, f.clients, sdk, 'after'));
    assert.equal(f.posts(), 0);
  }
});

test('a different replay, changed document or lost exclusion fails acceptance', async () => {
  for (const change of [{ replayAdmission: 'new-admission' }, { afterReplay: true }, { denial: 200 }]) {
    const f = fixture(change);
    await assert.rejects(accept(f.packet, f.clients, sdk, 'after'));
  }
});

test('malformed input produces bounded failure without leaking credentials', () => {
  const script = fileURLToPath(new URL('../../../scripts/core-checkpoint-sdk-acceptance.mjs', import.meta.url));
  const result = spawnSync(process.execPath, [script], { encoding: 'utf8', timeout: 10_000,
    input: JSON.stringify({ packet: {}, tokens: { seller: 'private-key-must-not-leak' }, mode: 'after' }) });
  assert.equal(result.status, 1);
  assert.equal(result.stderr, '');
  assert.deepEqual(JSON.parse(result.stdout), { status: 'FAIL' });
});
