import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';

const compiled = (await build({
  stdin: {
    contents: 'export * from "./src/account-api"; export * from "./src/account-context";',
    resolveDir: process.cwd(), loader: 'ts',
  }, bundle: true, write: false, format: 'esm',
})).outputFiles[0].text;
const membership = { generation: 1, revision: 1, role: 'ADMIN', status: 'ACTIVE' };
const account = id => ({
  accountId: id, userId: 'user-1', accountModel: 'MEMBERSHIPS_V1', kind: 'BUSINESS',
  profile: { displayName: id }, membership, sessionContext: `selected-${id}`,
});
const defaultAccount = account('default-workspace');
const linkedAccount = account('document-workspace');
const user = {
  userId: 'user-1', accountModel: 'MEMBERSHIPS_V1', sessionContext: 'user-guard',
  sessionExpiresAt: Date.now() + 600000, accounts: [defaultAccount, linkedAccount],
  preferences: { defaultAccountId: defaultAccount.accountId, revision: 1 },
};

async function openLink() {
  const values = new Map([['myos-core-account', JSON.stringify({
    accountId: defaultAccount.accountId, userId: user.userId, generation: 1,
  })]]);
  globalThis.sessionStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  };
  globalThis.location = new URL('https://console.example.test/developers/app/?accountId=document-workspace#documents/document-1');
  globalThis.history = {
    state: null,
    replaceState: (_state, _title, url) => { globalThis.location = new URL(url, globalThis.location); },
  };
  const sdk = await import('data:text/javascript;base64,' + Buffer.from(compiled + '\n//' + Math.random()).toString('base64'));
  const calls = [];
  globalThis.fetch = async (url, options) => {
    const headers = new Headers(options.headers);
    calls.push({ url, headers, method: options.method });
    return Response.json(url.endsWith('/me') ? user
      : headers.get('X-MyOS-Account') === linkedAccount.accountId ? linkedAccount : defaultAccount);
  };
  return { sdk, calls, values };
}

test('a document deep link selects its verified account instead of the tab or global default', async () => {
  const { sdk, calls, values } = await openLink();
  const result = await sdk.discoverAccounts();
  assert.equal(result.account.accountId, linkedAccount.accountId);
  assert.equal(result.user.preferences.defaultAccountId, defaultAccount.accountId);
  assert.equal(sdk.captureContext(), undefined, 'Discovery must not install a provisional guard');
  assert.equal(JSON.parse(values.get('myos-core-account')).accountId, linkedAccount.accountId);
  assert.equal(location.hash, '#documents/document-1');
  assert.equal(location.search, '', 'Consume the incoming account hint after verified selection');
  assert.deepEqual(calls.map(call => call.url), ['/api/blue/v1/me', '/api/blue/v1/account']);
  assert.ok(calls.every(call => !call.method || call.method === 'GET'), 'No preferences mutation');
  assert.equal(calls[1].headers.get('X-MyOS-Account'), linkedAccount.accountId);
  assert.equal(calls[1].headers.has('X-MyOS-Session-Context'), false);
});

test('an inactive link membership never sends a selected-account request or changes tab selection', async () => {
  const { sdk, calls, values } = await openLink();
  const before = values.get('myos-core-account');
  globalThis.fetch = async url => {
    calls.push({ url });
    return Response.json({ ...user, accounts: [defaultAccount,
      { ...linkedAccount, membership: { ...membership, status: 'REMOVED' } }] });
  };
  await assert.rejects(sdk.discoverAccounts(), error => error.code === 'document_account_unavailable');
  assert.deepEqual(calls.map(call => call.url), ['/api/blue/v1/me']);
  assert.equal(values.get('myos-core-account'), before);
  assert.equal(location.search, '?accountId=document-workspace');
});

test('a remove and rejoin during linked discovery cannot install the replacement generation', async () => {
  const { sdk, values } = await openLink();
  const before = values.get('myos-core-account');
  globalThis.fetch = async url => Response.json(url.endsWith('/me') ? user
    : { ...linkedAccount, membership: { ...membership, generation: 2 } });
  await assert.rejects(sdk.discoverAccounts(), error => error.code === 'document_account_unavailable');
  assert.equal(values.get('myos-core-account'), before);
  assert.equal(sdk.captureContext(), undefined);
  assert.equal(location.search, '?accountId=document-workspace');
});

test('malformed or ambiguous account links fail before account discovery', async () => {
  for (const search of ['?accountId=', '?accountId=%20document-workspace', '?accountId=one&accountId=two']) {
    const { sdk, calls } = await openLink();
    location.search = search;
    await assert.rejects(sdk.discoverAccounts(), /invalid workspace or document address/);
    assert.equal(calls.length, 0);
  }
});

test('a cancelled linked discovery cannot change the retained workspace or consume the link', async () => {
  const { sdk, values } = await openLink();
  const before = values.get('myos-core-account');
  const controller = new AbortController();
  globalThis.fetch = async url => {
    if (url.endsWith('/account')) controller.abort();
    return Response.json(url.endsWith('/me') ? user : linkedAccount);
  };
  await assert.rejects(sdk.discoverAccounts(controller.signal), error => error.name === 'AbortError');
  assert.equal(values.get('myos-core-account'), before);
  assert.equal(location.search, '?accountId=document-workspace');
});
