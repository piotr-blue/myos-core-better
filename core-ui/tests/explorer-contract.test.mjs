import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { build } from 'esbuild';
import { parse } from 'yaml';

const compiled = (await build({ entryPoints: ['src/explorer-contract.ts'], bundle: true, write: false, format: 'esm' })).outputFiles[0].text;
const { getOperations, resolveLocal, getExamples, loadContract, formatValue, swaggerHref } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);
const contract = parse(await readFile('../docs/api/openapi-adapted.yaml', 'utf8'));

test('adapted contract exposes all current operations without losing endpoint identities', () => {
  const operations = getOperations(contract);
  const methods = ['get', 'head', 'post', 'put', 'patch', 'delete', 'options', 'trace'];
  const expected = Object.entries(contract.paths).flatMap(([path, item]) => methods.filter(method => item[method]).map(method => `${method.toUpperCase()} ${path}`));
  assert.equal(operations.length, expected.length);
  for(const route of ["GET /api/blue/v1/me","GET /api/blue/v1/account/users","POST /api/blue/v1/accounts"]) assert.ok(operations.some(operation=>operation.key===route),route);
  assert.equal(new Set(operations.map(operation => operation.key)).size, expected.length);
  assert.deepEqual(operations.map(operation => operation.key), expected);
  assert.ok(operations.some(operation => operation.key === 'GET /api/blue/v1/contents/{blueId}'));
  for (const operationId of ['getPartyAccessAutomation', 'updatePartyAccessAutomation', 'listPartyAccessRequests', 'approvePartyAccessRequest']) {
    assert.ok(operations.some(operation => operation.operationId === operationId));
  }
  assert.ok(!operations.some(operation => /\/contents\/\{blueId\}\/(view|collection)$/.test(operation.path)));
  for (const operation of operations) {
    const original = contract.paths[operation.path][operation.method.toLowerCase()];
    assert.equal(operation.operationId, original.operationId);
    assert.equal(operation.tag, original.tags[0]);
    assert.deepEqual(Object.keys(operation.responses), Object.keys(original.responses));
  }
});

test('local references decode JSON pointer escapes and preserve sibling overrides', () => {
  const spec = { components: { schemas: { 'Name/~escaped': { type: 'string', description: 'base', enum: ['a', 'b'] }, Alias: { $ref: '#/components/schemas/Name~1~0escaped' } } } };
  assert.deepEqual(resolveLocal(spec, { $ref: '#/components/schemas/Alias', description: 'override' }), { type: 'string', description: 'override', enum: ['a', 'b'] });
  assert.throws(() => resolveLocal(spec, { $ref: 'https://example.org/schema.json' }), /Only local/);
  assert.throws(() => resolveLocal(spec, { $ref: '#/missing' }), /Missing contract reference/);
});

test('operation parameter overrides use both location and name while preserving inherited parameters', () => {
  const inherited = { name: 'id', in: 'path', required: true, schema: { type: 'string' }, description: 'base' };
  const spec = { components: { parameters: { Id: inherited }, schemas: { Numeric: { type: 'integer' } } }, paths: { '/items/{id}': { parameters: [{ $ref: '#/components/parameters/Id' }, { name: 'limit', in: 'query', schema: { type: 'integer' } }], get: { operationId: 'readItem', parameters: [{ ...inherited, description: 'override' }, { name: 'id', in: 'query', schema: { $ref: '#/components/schemas/Numeric' } }], responses: { '200': { description: 'ok' } } } } } };
  const [operation] = getOperations(spec);
  assert.equal(operation.parameters.length, 3);
  assert.equal(operation.parameters.find(parameter => parameter.in === 'path').description, 'override');
  assert.deepEqual(operation.parameters.find(parameter => parameter.in === 'query' && parameter.name === 'id').schema, { type: 'integer' });
  assert.ok(operation.parameters.some(parameter => parameter.name === 'limit'));
});

test('recursive schema properties stay lazy and direct reference cycles terminate', () => {
  const spec = { components: { schemas: { Node: { type: 'object', properties: { next: { $ref: '#/components/schemas/Node' } } }, A: { $ref: '#/components/schemas/B' }, B: { $ref: '#/components/schemas/A' } } } };
  const node = resolveLocal(spec, { $ref: '#/components/schemas/Node' });
  assert.equal(node.type, 'object');
  assert.deepEqual(node.properties.next, { $ref: '#/components/schemas/Node' });
  assert.equal(typeof resolveLocal(spec, { $ref: '#/components/schemas/A' }).$ref, 'string');
});

test('named examples retain explicit null, false and zero while excluding absent values', () => {
  const spec = { components: { examples: { Empty: { summary: 'Explicit null', description: 'An intentional empty value.', value: null } } } };
  assert.deepEqual(getExamples(spec, { example: false, examples: { empty: { $ref: '#/components/examples/Empty' }, zero: { value: 0 }, absent: { description: 'No value' } } }), [
    { key: 'default', name: 'Example', description: '', value: false },
    { key: 'empty', name: 'Explicit null', description: 'An intentional empty value.', value: null },
    { key: 'zero', name: 'zero', description: '', value: 0 },
  ]);
  assert.deepEqual(getExamples({}, { schema: { type: 'string', default: 'not-an-example' } }), []);
});

test('loading and JSON/YAML display preserve exact integers in contract examples', async () => {
  const originalFetch = globalThis.fetch;
  const controller = new AbortController();
  const calls = [];
  globalThis.fetch = async (...args) => { calls.push(args); return new Response('{"openapi":"3.1.0","paths":{},"components":{"examples":{"Exact":{"value":{"amount":900719925474099312345,"empty":null}}}}}'); };
  try {
    const spec = await loadContract(controller.signal);
    const [example] = getExamples(spec, { examples: { precise: { $ref: '#/components/examples/Exact' } } });
    assert.deepEqual(example.value, { amount: 900719925474099312345n, empty: null });
    for (const media of ['application/json', 'application/yaml']) {
      const formatted = formatValue(example.value, media);
      assert.match(formatted, /900719925474099312345/);
      assert.deepEqual(parse(formatted, { intAsBigInt: true }), example.value);
    }
    assert.equal(calls[0][0], '/api/blue/v1/openapi.json');
    assert.equal(calls[0][1].credentials, 'include');
    assert.equal(calls[0][1].signal, controller.signal);
  } finally { globalThis.fetch = originalFetch; }
});

test('contract loading reports failed responses and unexpected payloads', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response('denied', { status: 401 });
    await assert.rejects(loadContract(), /401/);
    globalThis.fetch = async () => Response.json({ message: 'not an OpenAPI contract' });
    await assert.rejects(loadContract(), /OpenAPI 3 contract/);
  } finally { globalThis.fetch = originalFetch; }
});

test('Swagger links target the selected operation tag and identifier', () => {
  assert.equal(swaggerHref(), '/swagger.html');
  assert.equal(swaggerHref({ tag: 'Documents / API', operationId: 'read#item' }), '/swagger.html#/Documents%20%2F%20API/read%23item');
  const operation = getOperations(contract)[0];
  assert.equal(swaggerHref(operation), `/swagger.html#/${encodeURIComponent(operation.tag)}/${encodeURIComponent(operation.operationId)}`);
});
