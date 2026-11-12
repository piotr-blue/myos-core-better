import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { build } from 'esbuild';

const compiled = (await build({entryPoints:['src/create-document.ts'],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
const { createDocumentRequest } = await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
const runtimes = JSON.parse(readFileSync('src/data/process-runtimes.json', 'utf8'));
const accessEvents = JSON.parse(readFileSync('../runtime/blue-core-service/src/main/resources/package-catalog/access-events-defaults.json', 'utf8'));
const defaults = JSON.parse(readFileSync('../packages/current.json', 'utf8'));
const source = `name: Pasted example
amount: 9007199254740993
rate: 0.12345678901234567890123456789
clauses:
  members:
    type: Activation / Activation Clause
    parameters:
      parties:
        customer: {myosAccountId: alice}
`;

test('pasted clause source bootstraps with every exact current package and unchanged numbers', () => {
  const request = createDocumentRequest(source, 'source', runtimes.finalGas);
  assert.equal(request.path, 'documents/bootstrap');
  assert.equal(request.body.document, source);
  assert.deepEqual(request.body.packages, defaults.packages.map(row=>row.blueId));
  assert.equal(request.body.packages.length, 30);
  assert.deepEqual(defaults.packages.filter(row=>row.namespace==='Tasks').map(row=>row.blueId),
    ['AcWWxbYbwVesQyQicU6wLt3ArqpUinrzMwL1WysZQCuh']);
  assert.ok(!defaults.packages.some(row=>row.namespace==='Representation'));
  assert.ok(defaults.packages.some(row=>row.namespace==='Mandates'));
});

test('exact documents and references retain ordinary creation semantics', () => {
  assert.deepEqual(createDocumentRequest(source, 'exact'), {
    path:'documents', body:{document:source,inputMode:'exact'},
  });
  assert.deepEqual(createDocumentRequest('  existing-id  ', 'reference'), {
    path:'documents', body:{document:{blueId:'existing-id'},inputMode:'source'},
  });
  const plain='name: Plain document\ncount: 0\n';
  assert.deepEqual(createDocumentRequest(plain, 'source'), {
    path:'documents', body:{document:plain,inputMode:'source'},
  });
});

test('every public example takes the authenticated bootstrap route when pasted', () => {
  const examples=JSON.parse(readFileSync('src/data/clause-examples.json','utf8'));
  assert.equal(examples.length,29);
  assert.ok(!examples.some(example=>example.id==='09-evidence-mandate'));
  for(const example of examples)
    assert.equal(createDocumentRequest(example.source,'source',runtimes.finalGas).path,'documents/bootstrap',example.id);
});

test('fresh account pasted source uses the published access-events package roots', () => {
  const request = createDocumentRequest(source, 'source', runtimes.accessEvents);
  assert.deepEqual(request.body.packages, accessEvents.packages.map(row=>row.blueId));
  assert.equal(request.body.document, source);
  assert.throws(() => createDocumentRequest(source, 'source'), /runtime is not supported/);
  assert.throws(() => createDocumentRequest(source, 'source', {...runtimes.accessEvents, provider:'other'}), /runtime is not supported/);
});

test('arbitrary pasted authoring remains literal, including the new .8 Payment Clause contract', () => {
  const authored=source+'  payment:\n    type: Payment/Payment Clause\n    parameters:\n      obtain:\n        template: /payment\n';
  assert.equal(createDocumentRequest(authored,'source',runtimes.accessEvents).body.document,authored);
  const original=JSON.parse(readFileSync('src/data/clause-examples.json','utf8')).find(row=>row.id==='01-order-payment').source;
  assert.equal(createDocumentRequest(original,'source',runtimes.accessEvents).body.document,original);
});


test('explicit bootstrap selection is transmitted while default requests stay implicit',()=>{
 const canonical={...runtimes.accessEvents,canonicalWireMeasurementV1:runtimes.finalGasCanonical.canonicalWireMeasurementV1};
 const request=createDocumentRequest(source,'source',canonical,canonical);
 assert.deepEqual(request.body.executionSelection,canonical);
 assert.deepEqual(request.body.packages,accessEvents.packages.map(row=>row.blueId));
 assert.equal(Object.hasOwn(createDocumentRequest(source,'source',runtimes.finalGas).body,'executionSelection'),false);
});
