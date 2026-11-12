import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { parse } from 'yaml';
const compiled=(await build({entryPoints:['src/operation-message.ts'],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
const {operationMessage}=await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
test('approvals target the reviewed snapshot and preserve exact request numbers',()=>{
  const source=operationMessage('initial','reviewed','customer','approve_customer','revision: 1\namountMinor: 9007199254740993',true);
  const msg=parse(source,{intAsBigInt:true});
  assert.deepEqual(msg.document,{blueId:'reviewed'});
  assert.equal(msg.requireExactDocumentVersion,true);
  assert.equal(msg.request.amountMinor,9007199254740993n);
});
test('non-exact operations explicitly target the original document',()=>{
  const msg=parse(operationMessage('initial','current','owner','run','{}',false));
  assert.equal(msg.document.blueId,'initial');
  assert.equal(msg.requireExactDocumentVersion,undefined);
  assert.throws(()=>operationMessage('initial',undefined,'owner','approve','{}',true),/initialized document/);
});
