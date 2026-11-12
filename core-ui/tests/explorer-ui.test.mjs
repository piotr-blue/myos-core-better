import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const compiled = (await build({ entryPoints: ['src/explorer.tsx'], bundle: true, write: false, format: 'esm', platform: 'browser', loader: { '.css': 'empty' } })).outputFiles[0].text;
const { filterOperations, retainStreamFrame } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));

test('operation search combines method, path and summary terms and retains grouping', () => {
  const operations = [
    { key: 'one', method: 'GET', path: '/documents', tag: 'Documents', summary: 'List handles', operationId: 'listDocuments' },
    { key: 'two', method: 'POST', path: '/documents', tag: 'Documents', summary: 'Create document', operationId: 'createDocument' },
    { key: 'three', method: 'GET', path: '/admissions/{id}', tag: 'Admissions', summary: 'Read admission', operationId: 'getAdmission' },
  ];
  assert.deepEqual(filterOperations(operations, '  GET handles ').map(op => op.key), ['one']);
  assert.deepEqual(filterOperations(operations, 'createdocument').map(op => op.key), ['two']);
  assert.deepEqual(filterOperations(operations, '').map(op => op.key), ['three', 'one', 'two']);
  assert.deepEqual(filterOperations(operations, 'POST admission'), []);
  assert.deepEqual(operations.map(op => op.key), ['one', 'two', 'three']);
});

test('stream frame retention keeps the last 100 frames without altering raw payloads', () => {
  const frame = index => ({ event: 'change', id: String(index), data: `{"integer":9007199254740993123,"index":${index}}`, raw: `event: change\nid: ${index}\ndata: {"integer":9007199254740993123,"index":${index}}` });
  const original = Array.from({ length: 100 }, (_, index) => frame(index));
  const retained = retainStreamFrame(original, frame(100));
  assert.equal(retained.length, 100);
  assert.equal(retained[0].id, '1');
  assert.equal(retained.at(-1).raw, frame(100).raw);
  assert.equal(original[0].id, '0');
});
