import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { parse } from 'yaml';
const compiled = (await build({entryPoints:['src/bootstrap-payload.ts'],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
const { parseBootstrapPayload, bootstrapTemplate } = await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
for (const [format, source] of [
 ['YAML', 'participantMappings:\n  - channelName: merchant\n    accountId: account\ndocument:\n  name: Counter\n  count: 9007199254740993\n  rate: 0.12345678901234567890123456789\n'],
 ['JSON', '{"participantMappings":[{"channelName":"merchant","accountId":"account"}],"document":{"name":"Counter","count":9007199254740993,"rate":0.12345678901234567890123456789}}'],
]) test(`bootstrap ${format} retains native document tokens as server-side source`,()=>{
 const result=parseBootstrapPayload(source);
 assert.deepEqual(result.participantMappings,[{channelName:'merchant',accountId:'account'}]);
 assert.equal(typeof result.document,'string');
 assert.match(result.document,/9007199254740993/);
 assert.match(result.document,/0\.12345678901234567890123456789/);
 assert.equal(parse(result.document,{intAsBigInt:true}).count,9007199254740993n);
 assert.deepEqual(parseBootstrapPayload(JSON.stringify(result)).document,result.document,'format changes keep the opaque source unchanged');
});
test('bootstrap accepts an existing source string and rejects malformed envelopes',()=>{
 assert.deepEqual(parseBootstrapPayload('document: "name: Example"').document,'name: Example');
 assert.throws(()=>parseBootstrapPayload('[]'),/bootstrap object/);
 assert.throws(()=>parseBootstrapPayload('document: [unterminated'));
});

for (const document of [
 {mode:'source',format:'yaml',text:'ordinary document fields'},
 {counter:0,description:'document with counter field'},
]) test('bootstrap keeps document fields without interpreting them as transport',()=>{
 assert.deepEqual(parse(parseBootstrapPayload(JSON.stringify({document})).document),document);
});

test('bootstrap template maps declared unbound channels using the new contract',()=>{
 const payload=bootstrapTemplate('alice-account');
 assert.equal(payload.inputMode,'source');
 assert.equal(payload.participants,undefined);
 assert.deepEqual(payload.participantMappings,[{channelName:'alice',accountId:'alice-account'},{channelName:'bob',accountId:'REPLACE_BOB_ACCOUNT_ID'}]);
 for(const mapping of payload.participantMappings){
  const channel=payload.document.contracts[mapping.channelName];
  assert.ok(channel.type.endsWith('/Timeline Channel'));
  assert.equal(channel.timeline,undefined);assert.equal(channel.actor,undefined);
 }
});

test('singleton Blue reference remains an object for bootstrap reference resolution',()=>{
 const result=parseBootstrapPayload('document:\n  blueId: immutable-reference\nparticipantMappings: []\n');
 assert.deepEqual(result.document,{blueId:'immutable-reference'});
 assert.deepEqual(parseBootstrapPayload(JSON.stringify(result)).document,result.document);
});

test('shared counter exposes repeated increment and decrement on the current value',()=>{
 const {document}=bootstrapTemplate('alice-account');
 assert.equal(document.count,0);
 for(const [name,channel,delta] of [['increment','alice',1],['decrement','bob',-1]]){
  const operation=document.contracts[name];
  assert.equal(operation.channel,channel);
  const change=operation.steps[0].do[0].$appendChange;
  assert.equal(change.path,'/count');
  assert.deepEqual(change.val,{$add:[{$document:'/count'},delta]});
 }
});
