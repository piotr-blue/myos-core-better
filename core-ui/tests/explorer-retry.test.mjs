import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const compiled=(await build({entryPoints:['src/explorer-request.ts'],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
const engine=await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
function request(method='POST',body='{"operation":"bump"}') {
 return engine.buildRequest({method,path:'/api/blue/v1/documents/example/operations',parameters:[],responses:{},requestBody:{required:false,content:{'application/json':{}}}},
  {parameters:{},body,sendBody:method!=='GET',mediaType:'application/json',accept:'application/json',auth:{mode:'session'}});
}
test('manual retry after a lost response sends the exact retained prepared request',async()=>{
 const prepared=request(),attempts=[];globalThis.fetch=async(url,options)=>{
  attempts.push({url,...options});if(attempts.length===1)throw new TypeError('Lost response');
  return new Response('{"admissionId":"accepted"}',{status:202});
 };
 await assert.rejects(engine.executeRequest(prepared));
 assert.equal((await engine.executeRequest(prepared)).status,202);assert.deepEqual(attempts[1],attempts[0]);
 assert.notEqual(request().headers['Idempotency-Key'],prepared.headers['Idempotency-Key'],'A new explicit command gets a new identity');
});
test('response-body failure leaves the prepared retry identity unchanged',async()=>{
 const prepared=request('DELETE','{}'),keys=[];globalThis.fetch=async(url,options)=>{
  keys.push(new Headers(options.headers).get('Idempotency-Key'));
  if(keys.length===1)return new Response(new ReadableStream({start(controller){controller.error(new Error('Truncated response'));}}),{status:200});
  return new Response(null,{status:204});
 };
 await assert.rejects(engine.executeRequest(prepared));assert.equal((await engine.executeRequest(prepared)).status,204);assert.equal(keys[0],keys[1]);
});
test('raw errors preserve status and body without hiding retries',async()=>{
 const prepared=request('PATCH','{}'),keys=[];globalThis.fetch=async(url,options)=>{
  keys.push(new Headers(options.headers).get('Idempotency-Key'));return new Response('upstream unavailable',{status:503,statusText:'Unavailable'});
 };
 const result=await engine.executeRequest(prepared);assert.equal(result.status,503);assert.equal(result.rawBody,'upstream unavailable');
 await engine.executeRequest(prepared);assert.equal(keys.length,2);assert.equal(keys[0],keys[1]);
});
test('separate prepared commands never share a mutation identity',()=>{
 const requests=[request(),request('POST','{"changed":true}'),request('PUT','{}')];
 assert.equal(new Set(requests.map(value=>new Headers(value.headers).get('Idempotency-Key'))).size,3);
});
test('raw reads omit body and mutation key and preserve non-JSON responses',async()=>{
 const prepared=request('GET');globalThis.fetch=async(url,options)=>{
  assert.equal(options.method,'GET');assert.equal(options.body,undefined);assert.equal(new Headers(options.headers).get('Idempotency-Key'),null);
  return new Response('raw response',{status:200});
 };
 assert.equal((await engine.executeRequest(prepared)).rawBody,'raw response');
});
