import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { prepareSchemas } from '../scripts/prepare-schemas.mjs';

test('prepares schema spellings without editing paths, examples or original Swagger', async () => {
  const original = JSON.parse(await readFile(new URL('../../../core-ui/public/openapi.json', import.meta.url)));
  const before = structuredClone(original);
  const prepared = prepareSchemas(original);
  assert.deepEqual(original, before);
  assert.deepEqual(prepared.components.examples, original.components.examples);
  assert.deepEqual(Object.keys(prepared.paths), Object.keys(original.paths));
  assert.deepEqual(prepared.components.schemas.DocumentInput.oneOf, [{ type: 'object' }, { type: 'string' }]);
  assert.deepEqual(prepared.components.schemas.AdmissionCommon.properties.completedAt.type, ['string', 'null']);
  assert.equal(prepared.components.schemas.AdmissionCommon.properties.completedAt.format, undefined);
  assert.deepEqual(prepared.components.schemas.DocumentAdmission.allOf[1].properties.kind.enum, ['DOCUMENT']);
});

test('business values named type/const/schema/format inside examples are untouched', () => {
  const example = { schema: { const: 'payload', type: ['object', 'string'], format: 'date-time' } };
  const original = { components: { schemas: {
    Value: { type: 'object', example, default: example, properties: { kind: { const: 'VALUE' } } },
  }, examples: { sample: { value: example } } }, paths: { '/x': { get: {
    responses: { 200: { content: { 'application/json': { schema: { type: ['object', 'string'] }, example } } } },
  } } } };
  const prepared = prepareSchemas(original);
  assert.deepEqual(prepared.components.schemas.Value.example, example);
  assert.deepEqual(prepared.components.schemas.Value.default, example);
  assert.deepEqual(prepared.paths['/x'].get.responses[200].content['application/json'].example, example);
  assert.deepEqual(prepared.components.schemas.Value.properties.kind, { type: 'string', enum: ['VALUE'] });
});


test('bootstrap generation uses only the typed current admission response', async () => {
  const original = JSON.parse(await readFile(new URL('../../../core-ui/public/openapi.json', import.meta.url)));
  const before = structuredClone(original);
  const prepared = prepareSchemas(original);
  const path = '/api/blue/v1/documents/bootstrap';
  assert.equal(prepared.paths[path].post.responses['201'], undefined);
  for (const mediaType of ['application/json', 'application/yaml']) {
    assert.deepEqual(prepared.paths[path].post.responses['202'].content[mediaType].schema,
      { $ref: '#/components/schemas/BootstrapAdmission' });
  }
  assert.deepEqual(prepared.paths[path].post.responses['202'].headers, original.paths[path].post.responses['202'].headers);
  assert.deepEqual(prepared.paths[path].post.responses['400'], original.paths[path].post.responses['400']);
  assert.deepEqual(original, before);
});
