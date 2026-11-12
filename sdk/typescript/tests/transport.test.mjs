import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import test from 'node:test';
import {
  Configuration, AccountApi, DocumentsApi, AdmissionsApi, TimelinesApi, ContentsApi, PackagesAndTypesApi, ResponseError,
} from '../dist/index.js';

// A disposable HTTP boundary, not a substitute for the real-backend demo.
async function fixture(t, replies) {
  const requests = [];
  const server = createServer(async (request, response) => {
    let body = '';
    for await (const chunk of request) body += chunk;
    requests.push({ method: request.method, url: request.url, headers: request.headers,
      body: body && request.headers['content-type']?.startsWith('application/json') ? JSON.parse(body) : body || undefined });
    const reply = replies.shift();
    response.writeHead(reply?.status ?? 500, { 'Content-Type': 'application/json', ...reply?.headers });
    response.end(reply?.bytes ?? JSON.stringify(reply?.body ?? { error: 'Unexpected request' }));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise((resolve, reject) => {
    server.close(error => error ? reject(error) : resolve());
    server.closeAllConnections();
  }));
  return { requests, configuration: new Configuration({
    basePath: `http://127.0.0.1:${server.address().port}`,
    accessToken: 'disposable-test-token',
  }) };
}

const pending = {
  admissionId: 'receipt-1', kind: 'DOCUMENT', status: 'PENDING',
  completionScope: 'REGISTRATION_STORED', details: null,
  createdAt: '2026-10-07T10:00:00Z', completedAt: null,
};

test('creates source documents unchanged, exposes Location, and reads typed admissions', async t => {
  const succeeded = { ...pending, status: 'SUCCEEDED', details: { sessionId: 'session-1' },
    completedAt: '2026-10-07T10:00:01Z' };
  const { configuration, requests } = await fixture(t, [
    { status: 202, headers: { Location: '/api/blue/v1/admissions/receipt-1' }, body: pending },
    { status: 200, body: succeeded },
  ]);
  const documents = new DocumentsApi(configuration);
  const source = 'name: SDK example\namount: 42\n';
  const response = await documents.createDocumentRaw({
    idempotencyKey: 'same-logical-request', createDocument: { document: source, inputMode: 'source' },
  });
  assert.equal(response.raw.status, 202);
  assert.equal(response.raw.headers.get('location'), '/api/blue/v1/admissions/receipt-1');
  assert.deepEqual(await response.value(), pending);
  assert.deepEqual(await new AdmissionsApi(configuration).getAdmission({ admissionId: 'receipt-1' }), succeeded);
  assert.equal(requests[0].url, '/api/blue/v1/documents');
  assert.equal(requests[0].headers.authorization, 'Bearer disposable-test-token');
  assert.equal(requests[0].headers['idempotency-key'], 'same-logical-request');
  assert.equal(requests[0].headers['content-type'], 'application/json');
  assert.deepEqual(requests[0].body, { document: source, inputMode: 'source' });
});

test('preserves full operation messages, explicit null predecessors and encoded Blue IDs', async t => {
  const { configuration, requests } = await fixture(t, [{ status: 202, body: {
    ...pending, kind: 'TIMELINE_ENTRY', completionScope: 'TIMELINE_ENTRY_STORED',
  } }]);
  const body = {
    inputMode: 'source', documentId: 'original-lineage', actor: 'native-actor', prevEntry: null,
    message: { type: 'Contracts/Operation Request', document: { blueId: 'original-lineage' },
      operation: 'example', channel: 'owner', request: {
        amount: 12345, text: 'literal', mode: 'business-field', nested: { blueId: 'content#member', other: true },
      } },
  };
  await new TimelinesApi(configuration).appendEntry({
    timelineId: 'timeline#member', idempotencyKey: 'entry-1', appendEntry: body,
  });
  assert.equal(requests[0].url, '/api/blue/v1/timelines/timeline%23member/entries');
  assert.deepEqual(requests[0].body, body);
});

test('does not turn an omitted predecessor into null; retains page tokens and entry payloads', async t => {
  const entries = { items: [{ blueId: 'entry', entry: { signature: 'signed-value', custom: { x: 1 } } }],
    nextPageToken: 'next+/=', paginationMode: 'BOUNDED_PROVIDER_RANGE' };
  const { configuration, requests } = await fixture(t, [
    { status: 202, body: { ...pending, kind: 'TIMELINE_ENTRY', completionScope: 'TIMELINE_ENTRY_STORED' } },
    { status: 200, body: entries },
  ]);
  const api = new TimelinesApi(configuration);
  await api.appendEntry({ timelineId: 'timeline', idempotencyKey: 'entry-2', appendEntry: { message: 'literal text' } });
  assert.deepEqual(requests[0].body, { message: 'literal text' });
  assert.deepEqual(await api.listTimelineEntries({ timelineId: 'timeline', limit: 2, pageToken: 'token+/=' }), entries);
  const query = new URL(requests[1].url, 'http://localhost').searchParams;
  assert.equal(query.get('pageToken'), 'token+/=');
  assert.equal(query.get('limit'), '2');
});

test('exposes HTTP problem details without treating failed admissions as HTTP errors', async t => {
  const problem = { type: 'about:blank', title: 'Conflict', status: 409, detail: 'Different request',
    code: 'IDEMPOTENCY_CONFLICT', instance: '/api/blue/v1/documents', requestId: 'trace-1' };
  const failed = { ...pending, status: 'FAILED', completedAt: '2026-10-07T10:00:01Z',
    problem: { code: 'TYPE_NOT_INSTALLED', message: 'Install the required package' } };
  const { configuration } = await fixture(t, [
    { status: 409, headers: { 'Content-Type': 'application/problem+json' }, body: problem },
    { status: 200, body: failed },
  ]);
  const error = await new DocumentsApi(configuration).createDocument({
    idempotencyKey: 'conflicting-key', createDocument: { document: {} },
  }).then(() => assert.fail('Expected HTTP 409'), error => error);
  assert.ok(error instanceof ResponseError);
  assert.equal(error.response.status, 409);
  assert.deepEqual(await error.response.json(), problem);
  assert.deepEqual(await new AdmissionsApi(configuration).getAdmission({ admissionId: 'receipt-1' }), failed);
});

test('content values round trip without interpreting domain transport names', async t => {
  const values = [{ mode: 'exact', text: 'literal', proof: { retained: 'data' }, body: 'ordinary' },
    { next: { blueId: 'cycle#1' } }, [], {}, false, 42, 'literal'];
  const accepted = { ...pending, kind: 'CONTENT' };
  const replies = values.flatMap(body => [{ status: 200, body }, { status: 202, body: accepted }]);
  const { configuration, requests } = await fixture(t, replies);
  const api = new ContentsApi(configuration);
  for (const [index, value] of values.entries()) {
    const blueId = `content#${index}`;
    const received = await api.getContent({ blueId });
    assert.deepEqual(received, value);
    await api.retainContent({ blueId, idempotencyKey: `retain-${index}`,
      exactContentSubmission: { content: received } });
    assert.deepEqual(requests[index * 2 + 1].body, { content: value });
    assert.equal(requests[index * 2 + 1].method, 'PUT');
    assert.equal(requests[index * 2].url, `/api/blue/v1/contents/content%23${index}`);
  }
  assert.equal(typeof api.getContentView, 'undefined');
  assert.equal(typeof api.getContentCollection, 'undefined');
});

test('type inspection retains the package wire property without reserved-word renaming', async t => {
  const inspection = { blueId: 'type-id', name: 'Example', package: 'blue.example',
    packageId: 'blue.example@1.0.0', definition: { name: 'Example' } };
  const { configuration } = await fixture(t, [{ status: 200, body: inspection }]);
  const result = await new PackagesAndTypesApi(configuration).getTypes({ blueId: 'type-id' });
  assert.deepEqual(result, inspection);
  assert.equal(result.package, 'blue.example');
  assert.equal(Object.hasOwn(result, '_package'), false);
});


test('bootstrap preserves 202 receipts, Location and polling through success and failure', async t => {
  const accepted = { ...pending, kind: 'DOCUMENT_BOOTSTRAP' };
  const succeeded = { ...accepted, status: 'SUCCEEDED', details: { sessionId: 'shared-session' },
    completedAt: '2026-10-07T10:00:01Z' };
  const failed = { ...accepted, status: 'FAILED', details: null,
    completedAt: '2026-10-07T10:00:01Z', problem: { code: 'BOOTSTRAP_FAILED', message: 'Preparation failed' } };
  const { configuration, requests } = await fixture(t, [
    { status: 202, headers: { Location: '/api/blue/v1/admissions/receipt-1' }, body: accepted },
    { status: 200, body: accepted }, { status: 200, body: succeeded }, { status: 200, body: failed },
  ]);
  const body = { document: 'name: shared\n', inputMode: 'source', packages: ['exact-package'],
    participantMappings: [{ channelName: 'owner', accountId: 'existing-owner' },
      { channelName: 'peer', accountId: 'existing-peer', representativeId: 'peer-user' }] };
  const response = await new DocumentsApi(configuration).bootstrapDocumentRaw({
    idempotencyKey: 'shared-bootstrap', bootstrapDocument: body,
  });
  assert.equal(response.raw.status, 202);
  assert.equal(response.raw.headers.get('location'), '/api/blue/v1/admissions/receipt-1');
  assert.deepEqual(await response.value(), accepted);
  const admissions = new AdmissionsApi(configuration);
  for (const expected of [accepted, succeeded, failed]) {
    assert.deepEqual(await admissions.getAdmission({ admissionId: accepted.admissionId }), expected);
  }
  assert.equal(requests[0].url, '/api/blue/v1/documents/bootstrap');
  assert.equal(requests[0].headers['idempotency-key'], 'shared-bootstrap');
  assert.deepEqual(requests[0].body, body);
  assert.ok(requests.slice(1).every(request => request.url === '/api/blue/v1/admissions/receipt-1'));
});

test('searches native descriptions and exact IDs through the generated document API', async t => {
  const page = { items: [{ sessionId: 'session-one', instanceId: 'instance-one', documentId: 'document-one',
    name: 'Order', description: 'Payment for order' }], nextPageToken: null, paginationMode: 'OBSERVATION_BOUND', total: 1 };
  const { configuration, requests } = await fixture(t, [{ status: 200, body: page }]);
  const result = await new DocumentsApi(configuration).listDocuments({ q: 'PAYMENT %_', sessionId: 'session-one',
    documentId: 'document-one', limit: 1, pageToken: 'opaque+/=', xMyOSAccount: 'selected-account' });
  const query = new URL(requests[0].url, 'http://localhost').searchParams;
  assert.equal(query.get('q'), 'PAYMENT %_');
  assert.equal(query.get('sessionId'), 'session-one');
  assert.equal(query.get('documentId'), 'document-one');
  assert.equal(requests[0].headers['x-myos-account'], 'selected-account');
  assert.deepEqual(result, page);
});

test('serializes explicit document type unions as one CSV selector and retains singular calls', async t => {
  const page = { items: [], nextPageToken: null, paginationMode: 'OBSERVATION_BOUND', total: 0 };
  const { configuration, requests } = await fixture(t, [
    { status: 200, body: page }, { status: 200, body: page },
  ]);
  const api = new DocumentsApi(configuration);
  await api.listDocuments({ typeBlueIds: ['legacy-id', 'current-id'], limit: 2, pageToken: 'opaque+/=' });
  let query = new URL(requests[0].url, 'http://localhost').searchParams;
  assert.deepEqual(query.getAll('typeBlueIds'), ['legacy-id,current-id']);
  assert.equal(query.get('typeBlueId'), null);
  assert.equal(query.get('pageToken'), 'opaque+/=');
  await api.listDocuments({ typeBlueId: 'legacy-id', limit: 2 });
  query = new URL(requests[1].url, 'http://localhost').searchParams;
  assert.equal(query.get('typeBlueId'), 'legacy-id');
  assert.equal(query.get('typeBlueIds'), null);
});


test('pending account creation polls the same durable receipt without changing the command', async t => {
  const pendingAccount = { accountId: 'account', membershipGeneration: 1, membershipRevision: 1,
    agentId: 'agent', provisioning: 'PENDING', receiptId: 'receipt' };
  const readyAccount = { ...pendingAccount, provisioning: 'READY' };
  const { configuration, requests } = await fixture(t, [
    { status: 201, body: pendingAccount }, { status: 201, body: readyAccount },
  ]);
  const api = new AccountApi(configuration);
  const command = { idempotencyKey: 'same-creation-intent',
    createAccount: { kind: 'PERSONAL', profile: { displayName: 'Person' } } };
  assert.deepEqual(await api.createAccount(command), pendingAccount);
  assert.deepEqual(await api.createAccount(command), readyAccount);
  assert.equal(requests[0].method, 'POST');
  assert.equal(requests[0].url, '/api/blue/v1/accounts');
  assert.deepEqual(requests[0].body, requests[1].body);
  assert.equal(requests[0].headers['idempotency-key'], requests[1].headers['idempotency-key']);
});

test('account creation is explicit and current discovery carries selected context headers', async t => {
  const created = { accountId: 'account', membershipGeneration: 1, membershipRevision: 1,
    agentId: 'agent', provisioning: 'READY', receiptId: 'create-receipt' };
  const selected = { accountId: 'account', kind: 'BUSINESS', profile: { displayName: 'Company' },
    membership: { role: 'ADMIN', status: 'ACTIVE', generation: 1, revision: 1 },
    userId: 'human', email: 'private@example.test', accountModel: 'MEMBERSHIPS_V1',
    revision: 1, sessionContext: 'selected-guard', sessionExpiresAt: 2000000000000,
    capabilities: { canWrite: true, canAdminister: true, canManageApiKeys: true } };
  const { configuration, requests } = await fixture(t, [
    { status: 201, body: created }, { status: 200, body: selected },
  ]);
  const api = new AccountApi(configuration);
  assert.deepEqual(await api.createAccount({ idempotencyKey: 'creation-intent',
    createAccount: { kind: 'BUSINESS', profile: { displayName: 'Company' } },
    xMyOSSessionContext: 'login-guard' }), created);
  assert.deepEqual(await api.getAccount({ xMyOSAccount: 'account', xMyOSSessionContext: 'selected-guard' }), selected);
  assert.equal(requests[0].url, '/api/blue/v1/accounts');
  assert.equal(requests[0].headers['idempotency-key'], 'creation-intent');
  assert.equal(requests[0].headers['x-myos-session-context'], 'login-guard');
  assert.deepEqual(requests[0].body, { kind: 'BUSINESS', profile: { displayName: 'Company' } });
  assert.equal(requests[1].headers['x-myos-account'], 'account');
  assert.equal(requests[1].headers['x-myos-session-context'], 'selected-guard');
  assert.equal(typeof api.activateAccount, 'undefined');
  assert.equal(typeof api.getAccountActivation, 'undefined');
});

test('timeline reads preserve retained payloads and selected human context', async t => {
  const page = { timelineRef: { blueId: 'timeline' }, items: [{ blueId: 'entry', entry: { signature: 'original' } }],
    nextPageToken: null, hasMore: false, paginationMode: 'PROJECTED_RETAINED_ENTRIES' };
  const { configuration, requests } = await fixture(t, [{ status: 200, body: page }]);
  const api = new TimelinesApi(configuration);
  assert.deepEqual(await api.listTimelineEntries({ timelineId: 'timeline', xMyOSAccount: 'account',
    xMyOSSessionContext: 'guard' }), page);
  assert.equal(new URL(requests[0].url, 'http://localhost').searchParams.get('stale'), null);
  assert.equal(requests[0].headers['x-myos-account'], 'account');
  assert.equal(requests[0].headers['x-myos-session-context'], 'guard');
});

test('account lookup retains AND selectors, flat profiles and nullable matched users', async t => {
  const page = { items: [{ accountId: 'company', kind: 'BUSINESS', displayName: 'Company', website: null,
    matchedUsers: [{ userId: 'anna', email: 'anna@example.test', firstName: null, lastName: 'Example', imageUrl: null }],
    matchedUsersHasMore: true }], hasMore: true, nextPageToken: 'bound+/=' };
  const { configuration, requests } = await fixture(t, [{ status: 200, body: page }]);
  const api = new AccountApi(configuration);
  const selectors = { accountIds: ['company', 'personal'], emails: ['anna@example.test'], q: 'ann', limit: 2, pageToken: null };
  assert.deepEqual(await api.lookupAccounts({ lookupAccounts: selectors, xMyOSSessionContext: 'user-context' }), page);
  assert.equal(requests[0].method, 'POST');
  assert.equal(requests[0].url, '/api/blue/v1/accounts/lookup');
  assert.deepEqual(requests[0].body, selectors);
  assert.equal(requests[0].headers['idempotency-key'], undefined);
  assert.equal(typeof api.resolveAccount, 'undefined');
});

test('self profiles preserve null clearing and revision/idempotency headers', async t => {
  const profile = { userId: 'anna', email: 'anna@example.test', firstName: null, lastName: 'Example', imageUrl: null,
    profileRevision: 2 };
  const { configuration, requests } = await fixture(t, [{ status: 200, body: profile }, { status: 200, body: profile }]);
  const api = new AccountApi(configuration);
  assert.deepEqual(await api.updateMyProfile({ idempotencyKey: '00000000-0000-0000-0000-000000000001',
    updateMyProfile: { expectedRevision: 1, firstName: null }, xMyOSSessionContext: 'user-context' }), profile);
  assert.deepEqual(requests[0].body, { expectedRevision: 1, firstName: null });
  assert.equal(requests[0].headers['x-myos-session-context'], 'user-context');
  assert.equal(requests[0].headers['idempotency-key'], '00000000-0000-0000-0000-000000000001');
  await api.deleteMyAvatar({ idempotencyKey: '00000000-0000-0000-0000-000000000002', deleteMyAvatar: { expectedRevision: 2 } });
  assert.equal(requests[1].method, 'DELETE');
  assert.equal(requests[1].url, '/api/blue/v1/me/avatar');
  assert.deepEqual(requests[1].body, { expectedRevision: 2 });
});

test('generated avatar upload owns multipart framing and binary reads remain bytes', async t => {
  const profile = { userId: 'anna', email: 'anna@example.test', firstName: null, lastName: null,
    imageUrl: 'https://core.example.test/api/blue/v1/users/anna/avatar/version', profileRevision: 1 };
  const png = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const { configuration, requests } = await fixture(t, [
    { status: 200, body: profile }, { status: 200, headers: { 'Content-Type': 'image/png' }, bytes: png },
  ]);
  const api = new AccountApi(configuration);
  assert.deepEqual(await api.putMyAvatar({ idempotencyKey: '00000000-0000-0000-0000-000000000003',
    file: new Blob([png], { type: 'image/png' }), expectedRevision: 0 }), profile);
  const upload = requests[0];
  assert.equal(upload.method, 'PUT');
  assert.equal(upload.url, '/api/blue/v1/me/avatar');
  const boundary = upload.headers['content-type'].match(/^multipart\/form-data; boundary=(.+)$/)?.[1];
  assert.ok(boundary);
  assert.ok(upload.body.startsWith(`--${boundary}\r\n`));
  assert.match(upload.body, /name="expectedRevision"\r\n\r\n0\r\n/);
  assert.match(upload.body, /name="file"; filename="blob"\r\nContent-Type: image\/png/);
  const image = await api.getUserAvatar({ userId: 'anna', version: 'version' });
  assert.deepEqual(Buffer.from(await image.arrayBuffer()), png);
  assert.equal(requests[1].headers['x-myos-session-context'], undefined);
  assert.equal(requests[1].headers.authorization, 'Bearer disposable-test-token');
});


test('Account images retain selected guards, independent revisions and generated multipart framing', async t => {
  const imageUrl='https://core.example.test/api/blue/v1/accounts/company/image/version';
  const uploaded={accountId:'company',profile:{displayName:'Company',imageUrl},revision:2};
  const removed={accountId:'company',profile:{displayName:'Company'},revision:3};
  const png=Buffer.from([137,80,78,71,13,10,26,10]);
  const {configuration,requests}=await fixture(t,[{status:200,body:uploaded},{status:200,headers:{'Content-Type':'image/png'},bytes:png},{status:200,body:removed}]);
  const api=new AccountApi(configuration), key='00000000-0000-0000-0000-000000000004';
  assert.deepEqual(await api.putAccountImage({file:new Blob([png],{type:'image/png'}),expectedRevision:1,idempotencyKey:key,xMyOSAccount:'company',xMyOSSessionContext:'selected-guard'}),uploaded);
  assert.equal(requests[0].url,'/api/blue/v1/account/image');
  assert.equal(requests[0].headers['x-myos-account'],'company');
  assert.equal(requests[0].headers['x-myos-session-context'],'selected-guard');
  assert.equal(requests[0].headers['idempotency-key'],key);
  assert.match(requests[0].headers['content-type'],/^multipart\/form-data; boundary=/);
  assert.match(requests[0].body,/name="expectedRevision"\r\n\r\n1\r\n/);
  assert.deepEqual(Buffer.from(await (await api.getAccountImage({accountId:'company',version:'version'})).arrayBuffer()),png);
  assert.deepEqual(await api.deleteAccountImage({deleteAccountImage:{expectedRevision:2},idempotencyKey:key,xMyOSAccount:'company',xMyOSSessionContext:'selected-guard'}),removed);
  assert.equal(requests[2].method,'DELETE');assert.deepEqual(requests[2].body,{expectedRevision:2});
});

test('prepares operations read-only with exact native request JSON and selected caller context', async t => {
  const reviewedPublication = { documentRef: { blueId: 'exact-state' }, epoch: '9007199254740993' };
  const route = { channel: 'buyer', timeline: 'timeline-1', actor: 'representative-1' };
  const requestJson = '[123456789012345678901234567890,0.123456789012345678901234567890]';
  const prepared = {
    accountId: 'account-1', sessionId: 'session#1', documentId: 'lineage-1',
    operationName: 'fixture-only-operation', reviewedPublication, route,
    definition: { state: 'matcher', digest: 'definition', runtime: {},
      descriptor: { kind: 'list', wireFormat: 'array', definitionPath: '', definitionBlueId: 'root', item: { kind: 'scalar', wireFormat: 'scalar', primitive: 'number', constraintsJson: '{}', definitionPath: '/item', definitionBlueId: 'child' }, constraintsJson: '{}' } },
    validation: { status: 'valid', issues: [] }, requestJson,
    messageJson: '{"operation":"fixture-only-operation","request":' + requestJson + '}',
    preconditions: { requireExactDocumentVersion: true },
  };
  const { configuration, requests } = await fixture(t, [{ status: 200, body: prepared }]);
  const request = { operationName: prepared.operationName, reviewedPublication, route, requestJson, descriptorPath: '/item', descriptorOffset: 0, definitionView: { doNotExpandPaths: [''] } };
  const result = await new DocumentsApi(configuration).prepareDocumentOperation({
    sessionId: 'session#1', prepareDocumentOperation: request,
    xMyOSAccount: 'account-1', xMyOSSessionContext: 'generation-1',
  });
  assert.deepEqual(result, prepared);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].method, 'POST');
  assert.equal(requests[0].url, '/api/blue/v1/documents/session%231/operations/prepare');
  assert.equal(requests[0].headers['idempotency-key'], undefined);
  assert.equal(requests[0].headers['x-myos-account'], 'account-1');
  assert.equal(requests[0].headers['x-myos-session-context'], 'generation-1');
  assert.deepEqual(requests[0].body, request);
});


test('preserves explicit typed Integer value encoding and decimal-string payload', async t => {
  const descriptor = { kind: 'scalar', wireFormat: 'typed_scalar', primitive: 'integer',
    typeBlueId: 'custom-integer', valueEncoding: 'safe_number_or_decimal_string', constraintsJson: '{}', definitionPath: '', definitionBlueId: 'custom-definition' };
  const requestJson = '{"type":{"blueId":"custom-integer"},"value":"123456789012345678901234567890"}';
  const reviewedPublication = { documentRef: { blueId: 'state' }, epoch: 7 };
  const route = { channel: 'buyer', timeline: 'timeline', actor: 'actor' };
  const prepared = { accountId: 'account', sessionId: 'session', documentId: 'lineage',
    operationName: 'typed-integer', reviewedPublication, route,
    definition: { state: 'matcher', digest: 'definition', runtime: {}, descriptor },
    validation: { status: 'valid', issues: [] }, requestJson,
    preconditions: { requireExactDocumentVersion: true } };
  const { configuration, requests } = await fixture(t, [{ status: 200, body: prepared }]);
  const result = await new DocumentsApi(configuration).prepareDocumentOperation({ sessionId: 'session',
    prepareDocumentOperation: { operationName: 'typed-integer', reviewedPublication, route, requestJson } });
  assert.deepEqual(result, prepared);
  assert.equal(requests[0].body.requestJson, requestJson);
  assert.equal(requests[0].headers['idempotency-key'], undefined);
});

test('operation discovery retains snapshot publication and continuation without metadata reads', async t => {
  const reviewedPublication = { documentRef: { blueId: 'stable-publication' }, epoch: '9007199254740993' };
  const page = { items: [], total: 0, nextPageToken: 'next-bound-page', paginationMode: 'SNAPSHOT',
    documentId: 'lineage', reviewedPublication };
  const { configuration, requests } = await fixture(t, [{ status: 200, body: page }]);
  const result = await new DocumentsApi(configuration).getDocumentOperations({ sessionId: 'session',
    limit: 2, pageToken: 'original-bound-page', xMyOSAccount: 'account', xMyOSSessionContext: 'guard' });
  assert.deepEqual(result, page);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].method, 'GET');
  assert.equal(requests[0].url, '/api/blue/v1/documents/session/operations?limit=2&pageToken=original-bound-page');
  assert.equal(requests[0].headers['x-myos-account'], 'account');
  assert.equal(requests[0].headers['x-myos-session-context'], 'guard');
});


test('operation context uses exact publication and explicit structural pagination', async t => {
  const response = { accountId: 'account', sessionId: 'session', documentId: 'lineage',
    reviewedPublication: { documentRef: { blueId: 'state' }, epoch: '9007199254740993' }, path: '',
    children: [{ path: '/field~1name' }], nextChildrenOffset: 100 };
  const { configuration, requests } = await fixture(t, [{ status: 200, body: response }]);
  const result = await new DocumentsApi(configuration).getDocumentContent({ sessionId: 'session',
    view: 'operation_context', documentBlueId: 'state', epoch: '9007199254740993', childrenOffset: 0 });
  assert.deepEqual(result, response);
  const query = new URL(requests[0].url, 'http://fixture').searchParams;
  assert.equal(query.get('epoch'), '9007199254740993');
  assert.equal(query.get('childrenOffset'), '0');
});
