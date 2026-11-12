import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
const compiled = (await build({ entryPoints: ['src/explorer-docs.tsx'], bundle: true, write: false, format: 'esm', packages: 'external', loader: { '.css': 'empty' } })).outputFiles[0].text;
// A file URL lets bundled module imports resolve this workspace's React installation.
import { writeFile, unlink } from 'node:fs/promises';
const target = new URL('../.explorer-docs-test.mjs', import.meta.url);
await writeFile(target, compiled);
let module;
try { module = await import(target.href); } finally { await unlink(target); }
const { resolveDocReference, SchemaNode, ContentView } = module;
const html = (component, props) => renderToStaticMarkup(React.createElement(component, props));

test('documentation resolves local references and reports external and circular chains', () => {
  const spec = { components: { schemas: { 'a/b': { type: 'string' }, loop: { $ref: '#/components/schemas/loop' } } } };
  assert.equal(resolveDocReference(spec, { $ref: '#/components/schemas/a~1b' }).value.type, 'string');
  assert.match(resolveDocReference(spec, { $ref: 'https://example.org/schema' }).error, /local contract references/);
  assert.match(resolveDocReference(spec, { $ref: '#/components/schemas/loop' }).error, /Circular/);
  assert.match(resolveDocReference(spec, { $ref: '#/missing' }).error, /Missing/);
});

test('recursive schema renders one level lazily while preserving property types and required flags', () => {
  const node = { type: 'object', required: ['name'], properties: { name: { type: 'string', nullable: true }, child: { $ref: '#/components/schemas/Node' } } };
  const result = html(SchemaNode, { spec: { components: { schemas: { Node: node } } }, schema: node, initialOpen: true });
  assert.match(result, /name<\/strong>/);
  assert.match(result, /required/);
  assert.match(result, /nullable/);
  assert.match(result, /child<\/strong>/);
  assert.equal((result.match(/<details/g) || []).length, 3);
});

test('schema displays enums and all variant kinds and treats descriptions as text', () => {
  const result = html(SchemaNode, { spec: {}, initialOpen: true, schema: { description: '<img src=x onerror=alert(1)>', enum: ['draft', 'live'], oneOf: [{ type: 'string' }], anyOf: [{ type: 'number' }], allOf: [{ type: 'object' }] } });
  assert.match(result, /Allowed values/);
  assert.match(result, /draft/);
  for (const kind of ['oneOf', 'anyOf', 'allOf']) assert.match(result, new RegExp(kind));
  assert.ok(!result.includes('<img'));
  assert.match(result, /&lt;img/);
});

test('named response examples render their actual values and media choices', () => {
  const result = html(ContentView, { spec: {}, content: { 'application/json': { schema: { type: 'object' }, examples: { waiting: { summary: 'Waiting response', value: { status: 'PENDING', total: 0 } }, done: { value: { status: 'DONE' } } } }, 'application/yaml': {} } });
  assert.match(result, /<option value="waiting" selected="">Waiting response<\/option>/);
  assert.match(result, /PENDING/);
  assert.match(result, /waiting/);
  assert.match(result, /done/);
  assert.match(result, /application\/yaml/);
});

test('missing examples stay explicit rather than generating a schema sample', () => {
  const result = html(ContentView, { spec: {}, content: { 'application/json': { schema: { type: 'string', default: 'invented-example' } } } });
  assert.match(result, /No example is documented/);
  assert.ok(!result.includes('invented-example'));
});

test('overview presents authentication, parameters and possible response statuses', () => {
  const spec = { security: [{ bearerAuth: [] }, { session: [] }], components: { securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer' }, session: { type: 'apiKey', in: 'cookie', name: 'session' } } } };
  const result = html(module.OperationOverview, { spec, operation: { operation: { summary: 'Read a document', description: '<!-- executed-requests:start -->\nLong operation details', operationId: 'readDocument' }, parameters: [{ name: 'documentId', in: 'path', required: true, schema: { type: 'string' } }], responses: { '200': { description: 'Document found' }, '404': { description: 'Document missing' } } } });
  for (const text of ['Authentication', 'bearerAuth', 'bearer', 'cookie', 'documentId', 'required', '200', '404', 'Document found', 'Document missing', 'Details']) assert.ok(result.includes(text), text);
  assert.ok(!result.includes('executed-requests:start'));
});

test('operation security overrides inherited authentication requirements', () => {
  const result = html(module.OperationOverview, { spec: { security: [{ bearerAuth: [] }] }, operation: { operation: { security: [] }, parameters: [], responses: {} } });
  assert.match(result, /No authentication is required/);
  assert.ok(!result.includes('bearerAuth'));
});

test('response headers are documented independently of response bodies', () => {
  const spec = { components: { headers: { Length: { description: 'Payload size', schema: { type: 'integer' } } } } };
  const result = html(module.ResponseHeaders, { spec, headers: { 'Content-Length': { $ref: '#/components/headers/Length' }, ETag: { description: 'Current version', schema: { type: 'string' } } } });
  for (const text of ['Response headers', 'Content-Length', 'Payload size', 'integer', 'ETag', 'Current version']) assert.ok(result.includes(text), text);
});

test('documentation renders safe basic Markdown without exposing marker comments', () => {
  const result = html(module.DocDescription, { text: '<!-- internal-marker -->\n### Usage\n\nSend **exact** values with `documentId`.\n\n- First step\n- Second step\n\n```json\n{"name":"<script>"}\n```' });
  assert.match(result, /<h4>Usage<\/h4>/);
  assert.match(result, /<strong>exact<\/strong>/);
  assert.match(result, /<code>documentId<\/code>/);
  assert.match(result, /<li>First step<\/li>/);
  assert.match(result, /<pre><code>/);
  assert.ok(!result.includes('internal-marker'));
  assert.ok(!result.includes('<script>'));
});
