/** Generated SDK owns API transport. The packet selects data, never executable code. */
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

export async function accept(packet, clients, sdk, mode) {
  assert(['before', 'after'].includes(mode));
  const options = { timeoutMs: 90_000, pollIntervalMs: 250 };
  const request = () => ({ signal: AbortSignal.timeout(20_000) });
  for (const [name, expected] of Object.entries(packet.accounts)) {
    const account = await clients[name].accounts.getInfo({}, request());
    assert.equal(account.accountId, expected.accountId);
  }
  const document = async expected => {
    const api = clients[expected.account].documents;
    const value = await sdk.waitFor(signal => api.getDocument({ sessionId: expected.sessionId }, { signal }),
      current => current.processing?.state === 'READY', options);
    assert.deepEqual(value.lastStable.documentRef, expected.documentRef);
    assert(value.lastStable.epoch >= expected.epoch);
  };
  for (const expected of packet.documents) {
    await document(expected);
    const api = clients[expected.account].documents;
    let token, content = false;
    const seen = new Set();
    do {
      assert(seen.size < 100, 'Evidence pagination exceeded its bound');
      const page = await api.getDocumentEvidence({ sessionId: expected.sessionId, limit: 100,
        ...(token ? { pageToken: token } : { epoch: expected.epoch }) }, request());
      assert.deepEqual(page.target.positionRef, expected.positionRef);
      assert.equal(page.target.epoch, expected.epoch);
      assert(Array.isArray(page.items) && page.items.length <= 100);
      content ||= page.items.some(item => item.kind === 'CONTENT' && item.ref?.blueId === expected.documentRef.blueId);
      token = page.nextPageToken;
      if (token) { assert(!seen.has(token), 'Repeated evidence cursor'); seen.add(token); }
    } while (token);
    assert(content, 'Missing retained document content evidence');
  }
  for (const expected of packet.replays) {
    const api = clients[expected.account];
    const prior = await api.admissions.getAdmission({ admissionId: expected.admissionId }, request());
    assert.equal(prior.admissionId, expected.admissionId);
    assert.equal(prior.status, 'SUCCEEDED');
    assert.equal(prior.details.entryRef.blueId, expected.entryBlueId);
    if (mode === 'after') {
      const replay = await sdk.waitForAdmission(api.admissions, await api.documents.invokeOperation(expected.command, request()), options);
      assert.equal(replay.admissionId, expected.admissionId);
      assert.equal(replay.details.entryRef.blueId, expected.entryBlueId);
    }
  }
  if (mode === 'after') for (const expected of packet.documents) await document(expected);
  for (const expected of packet.denials) {
    let denied = false;
    try { await clients[expected.account].documents.getDocument({ sessionId: expected.sessionId }, request()); }
    catch (error) {
      if (!(error instanceof sdk.ResponseError)) throw error;
      denied = error.response.status === expected.status;
    }
    assert(denied, 'Retained access exclusion changed');
  }
  return { status: 'PASS', mode, accounts: Object.keys(packet.accounts).length,
    documents: packet.documents.length, replays: mode === 'after' ? packet.replays.length : 0,
    retainedAdmissions: packet.replays.length, denials: packet.denials.length };
}

async function main() {
  try {
    let raw = '';
    for await (const chunk of process.stdin) {
      raw += chunk;
      if (Buffer.byteLength(raw) > 16 * 1024 * 1024) throw new Error('Input bound exceeded');
    }
    const { packet, tokens, mode } = JSON.parse(raw);
    const sdk = await import('../sdk/typescript/dist/index.js');
    const clients = {};
    for (const name of Object.keys(packet.accounts)) {
      const config = new sdk.Configuration({ basePath: packet.origin, accessToken: tokens[name] });
      clients[name] = { accounts: new sdk.AccountApi(config), documents: new sdk.DocumentsApi(config),
        admissions: new sdk.AdmissionsApi(config) };
    }
    process.stdout.write(JSON.stringify(await accept(packet, clients, sdk, mode)));
  } catch {
    // API keys, returned bodies and command contents never enter process logs.
    process.stdout.write(JSON.stringify({ status: 'FAIL' }));
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
