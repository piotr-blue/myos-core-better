import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const compiled = (await build({entryPoints:['src/resource-reads.ts'],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
const reads = await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
globalThis.localStorage={getItem:()=>null};
test('document presentation follows the projected stable reference even when native view is newer',async()=>{
 const metadata={sessionId:'s',control:"PAUSED",canUnlink:true,documentId:'source#0',lastStable:{epoch:2,documentRef:{blueId:'selected'}}};
 const paths=[];globalThis.fetch=async path=>{paths.push(path);return Response.json(path.includes('/timelines')?{items:[{channel:'owner'}]}:{name:path.includes('source%230')?'Source':'Selected'});};
 const document=await reads.readDocument('s',metadata);
 assert.equal(document.lastStable.epoch,2);assert.deepEqual(document.lastStable.document,{name:'Selected'});assert.equal(document.control,"PAUSED");assert.equal(document.canUnlink,true);
 assert.deepEqual(document.source,{name:'Source'});assert.deepEqual(document.channels,[{channel:'owner'}]);assert.deepEqual(paths.sort(),['/api/blue/v1/documents/s/timelines?limit=100','/api/blue/v1/contents/source%230','/api/blue/v1/contents/selected'].sort());
});
test('full retained timeline entries preserve exact values and continuation without detail requests',async()=>{
 const items=[{blueId:'a',entry:{message:{blueId:'message#1'},timestamp:42}},{blueId:'b',entry:{message:{counter:2},timestamp:43}}];
 const response={items,nextPageToken:'next',beforeUs:44};
 const paths=[];globalThis.fetch=async path=>{paths.push(path);return Response.json(response);};
 const page=await reads.readTimelineEntries('t','opaque+/=');
 assert.deepEqual(page,response);
 assert.deepEqual(paths,['/api/blue/v1/timelines/t/entries?limit=25&pageToken=opaque%2B%2F%3D']);
});
test('imported entries preserve their canonical identities and retained page semantics',async()=>{
 const item={blueId:'entry#1',entry:{message:{blueId:'message#2'},timestamp:1700000000123456}};
 const response={items:[item],timelineRef:{blueId:'provider#0'},hasMore:false,nextPageToken:null,paginationMode:'RETAINED_IMPORTED_ENTRIES'};
 const paths=[];globalThis.fetch=async path=>{paths.push(path);return Response.json(response);};
 assert.deepEqual(await reads.readTimelineEntries('provider#0'),response);
 assert.deepEqual(paths,['/api/blue/v1/timelines/provider%230/entries?limit=25']);
});

test('historical positions resolve only their selected immutable BlueId and preserve literal content fields', async()=>{
 const value={mode:'exact',text:'{"hidden":true}',value:900719925474099312345n,body:{items:[1,2,3]},proof:'ordinary data'};
 const position={epoch:1,documentRef:{blueId:'historical#2'}};
 const paths=[];globalThis.fetch=async path=>{paths.push(path);return new Response('{"mode":"exact","text":"{\\"hidden\\":true}","value":900719925474099312345,"body":{"items":[1,2,3]},"proof":"ordinary data"}');};
 const result=await reads.readPosition(position);
 assert.deepEqual(result,{...position,document:value});
 assert.deepEqual(paths,['/api/blue/v1/contents/historical%232']);
});
