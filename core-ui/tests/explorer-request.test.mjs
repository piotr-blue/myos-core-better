import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { build } from 'esbuild';
const compiled=(await build({entryPoints:['src/explorer-request.ts'],bundle:true,write:false,format:'esm',platform:'browser'})).outputFiles[0].text;
const engine=await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
const liveSpec=JSON.parse(fs.readFileSync(new URL('../public/openapi.json',import.meta.url),'utf8'));
const operation=(method='GET',path='/api/blue/v1/contents/{blueId}')=>({method,path,key:method+' '+path,parameters:[{in:'path',name:'blueId',required:true,schema:{type:'string'}}],responses:{}});
const liveOperation=(method,path)=>({method,path,responses:liveSpec.paths[path][method.toLowerCase()].responses});
const admissionSchema={oneOf:[{$ref:'#/components/schemas/DocumentAdmission'},{$ref:'#/components/schemas/ContentAdmission'}]};
const admissionOperation=(method='POST')=>({method,path:'/api/blue/v1/documents',key:method+' /api/blue/v1/documents',parameters:[],responses:{
 '200':{content:{'application/json':{schema:{$ref:'#/components/schemas/Admission'}},'application/yaml':{schema:admissionSchema}}},
 '202':{content:{'application/json':{schema:{$ref:'#/components/schemas/DocumentAdmission'}},'application/yaml':{schema:{$ref:'#/components/schemas/Admission'}}}}
}});
const draft=(overrides={})=>({parameters:{'path:blueId':'owner#0'},body:'',sendBody:false,mediaType:'application/json',accept:'application/json',auth:{mode:'session'},...overrides});
test('builds only relative contract paths and preserves opaque IDs',()=>{
 const op=operation();op.parameters.push({in:'query',name:'pageToken',schema:{type:'string'}});
 const req=engine.buildRequest(op,draft({parameters:{'path:blueId':'owner#0','query:pageToken':'a+/='}}));
 assert.equal(req.url,'/api/blue/v1/contents/owner%230?pageToken=a%2B%2F%3D');assert.equal(req.body,undefined);
 assert.throws(()=>engine.buildRequest(operation('GET','https://other.test/api/blue/v1/contents'),draft()));
 assert.throws(()=>engine.buildRequest(op,draft({parameters:{'path:blueId':'..'}})));
});
test('path IDs reject decoded slashes before building or sending',async()=>{
 const op=operation();op.parameters.push({in:'query',name:'path',schema:{type:'string'}});
 assert.throws(()=>engine.buildRequest(op,draft({parameters:{'path:blueId':'id/view','query:path':'/nested'}})),/Relative path/);
 const req=engine.buildRequest(op,draft({parameters:{'path:blueId':'owner#0','query:path':'/nested'}}));
 assert.equal(req.url,'/api/blue/v1/contents/owner%230?path=%2Fnested');
 globalThis.fetch=async()=>assert.fail('unsafe prepared URL must not be fetched');
 await assert.rejects(engine.executeRequest({...req,url:'/api/blue/v1/contents/id%2Fview?path=%2Fnested'}),/Relative path/);
});
test('validates required fields and JSON syntax without rewriting numeric text',()=>{
 const op=operation('POST','/api/blue/v1/contents');op.parameters=[];op.requestBody={required:true,content:{'application/json':{}}};
 assert.throws(()=>engine.buildRequest(op,draft()));assert.throws(()=>engine.buildRequest(op,draft({sendBody:true,body:'{"value":'})));
 const text='{"value":900719925474099312345,"decimal":0.1234567890123456789}';
 const req=engine.buildRequest(op,draft({sendBody:true,body:text}));assert.equal(req.body,text);assert.ok(req.headers['Idempotency-Key']);
 assert.throws(()=>engine.buildRequest(op,draft({sendBody:true,body:'a: 1'})));
});
test('optional bodies stay absent and protected headers cannot escape auth mode',()=>{
 const op=operation('DELETE');const req=engine.buildRequest(op,draft({auth:{mode:'bearer',token:'private-key'}}));
 assert.equal(req.credentials,'omit');assert.equal(req.headers.Authorization,'Bearer private-key');assert.equal(req.body,undefined);assert.equal(req.headers['Content-Type'],undefined);
 assert.throws(()=>engine.buildRequest(op,draft({headers:{Cookie:'secret'}})));assert.throws(()=>engine.buildRequest(op,draft({auth:{mode:'bearer',token:''}})));
});
test('authorization-like header names are blocked and excluded from cURL',()=>{
 const op=operation('DELETE');
 op.parameters.push({in:'header',name:'Proxy-Authorization',schema:{type:'string'}});
 assert.throws(()=>engine.buildRequest(op,draft({parameters:{'path:blueId':'owner#0','header:Proxy-Authorization':'secret'}})),/request controls/);
 assert.throws(()=>engine.buildRequest(op,draft({headers:{'X-Authorization':'secret'}})),/request controls/);
 assert.throws(()=>engine.buildRequest(op,draft({headers:{'pRoXy-AuThOrIzAtIoN':'secret'}})),/request controls/);
 assert.throws(()=>engine.buildRequest(op,draft({headers:{'x-authORIZATION':'secret'}})),/request controls/);
 const curl=engine.copyCurl({method:'DELETE',url:'/api/blue/v1/contents/owner%230',credentials:'omit',headers:{Authorization:'Bearer live','Proxy-Authorization':'Basic hidden','pRoXy-AuThOrIzAtIoN':'hidden','X-Authorization':'hidden','Idempotency-Key':'keep','X-Trace':'visible'}});
 assert.ok(!curl.includes('live'));assert.ok(!curl.includes('Basic hidden'));assert.ok(!curl.includes('X-Authorization'));
 assert.ok(!curl.includes('pRoXy-AuThOrIzAtIoN'));
 assert.ok(curl.includes('Idempotency-Key: keep'));assert.ok(curl.includes('X-Trace: visible'));
});
test('HTTP problems remain responses and a network failure never repeats mutation',async()=>{
 const req=engine.buildRequest(operation('DELETE'),draft());let calls=0;
 globalThis.fetch=async(url,options)=>{calls++;assert.equal(options.redirect,'error');assert.equal(url,req.url);return new Response('{"status":404,"detail":"Missing"}',{status:404,headers:{'Content-Type':'application/problem+json'}});};
 const result=await engine.executeRequest(req);assert.equal(result.status,404);assert.equal(result.rawBody,'{"status":404,"detail":"Missing"}');assert.equal(calls,1);
 globalThis.fetch=async()=>{calls++;throw new TypeError('Network failed');};await assert.rejects(engine.executeRequest(req));assert.equal(calls,2);
});
test('retry snapshot preserves key/body; cURL hides actual credentials',()=>{
 const req=engine.buildRequest(operation('DELETE'),draft({auth:{mode:'bearer',token:'private-key'}}));const curl=engine.copyCurl(req,'https://core.example.test');
 assert.ok(!curl.includes('private-key'));assert.ok(curl.includes('${MYOS_TOKEN}'));assert.ok(curl.includes(req.headers['Idempotency-Key']));
 const next=engine.admissionRequest(req,'receipt#0');assert.equal(next.method,'GET');assert.equal(next.url,'/api/blue/v1/admissions/receipt%230');assert.equal(next.body,undefined);assert.equal(next.headers['Idempotency-Key'],undefined);assert.equal(next.headers.Authorization,req.headers.Authorization);
});
test('formatting does not round numeric tokens or alter string whitespace',()=>{
 const raw='{"number":900719925474099312345,"decimal":0.1234567890123456789,"text":"a  b\\n\\\"c"}';const pretty=engine.formatResponse(raw,'application/json');
 assert.ok(pretty.includes('900719925474099312345'));assert.ok(pretty.includes('0.1234567890123456789'));assert.deepEqual(JSON.parse(pretty),JSON.parse(raw));assert.equal(engine.formatResponse('not JSON','text/plain'),'not JSON');
});
test('admission tracking requires operation response status and media schema',()=>{
 const op=admissionOperation();
 const json={headers:{'content-type':'application/json'},rawBody:'{"admissionId":"a","status":"PENDING"}'};
 const yaml={headers:{'content-type':'application/yaml; charset=utf-8'},rawBody:'admissionId: a\nstatus: PENDING\n'};
 const spec={components:{schemas:{Admission:{oneOf:[{$ref:'#/components/schemas/DocumentAdmission'},{$ref:'#/components/schemas/ContentAdmission'}]},DocumentAdmission:{},ContentAdmission:{},NotAdmission:{}}}};
 assert.deepEqual(engine.admissionFrom(spec,op,{status:202,...json}),{admissionId:'a',status:'PENDING'});
 assert.deepEqual(engine.admissionFrom(spec,op,{status:202,...yaml}),{admissionId:'a',status:'PENDING'});
 assert.deepEqual(engine.admissionFrom(spec,op,{status:200,...json}),{admissionId:'a',status:'PENDING'});
 assert.equal(engine.admissionFrom(spec,op,{status:400,...json}),undefined);
 assert.equal(engine.admissionFrom(spec,{responses:{'202':{content:{'application/json':{schema:{$ref:'#/components/schemas/NotAdmission'}}}}}},{status:202,...json}),undefined);
 assert.equal(engine.admissionFrom(liveSpec,liveOperation('GET','/api/blue/v1/contents/{blueId}'),{status:200,...json}),undefined);
 assert.equal(engine.admissionFrom(liveSpec,liveOperation('POST','/api/blue/v1/webhooks/{webhookId}/events/{eventId}/retry'),{status:202,...json}),undefined);
 assert.equal(engine.admissionFrom(spec,op,{status:202,headers:{'content-type':'text/plain'},rawBody:'{"admissionId":"a","status":"PENDING"}'}),undefined);
});
test('live OpenAPI admission responses are recognized for producers and admission reads',()=>{
 const producers=[
  ['POST','/api/blue/v1/documents'],['DELETE','/api/blue/v1/documents/{sessionId}'],['POST','/api/blue/v1/documents/{sessionId}/operations'],
  ['POST','/api/blue/v1/documents/{sessionId}/pause'],['POST','/api/blue/v1/documents/{sessionId}/resume'],['POST','/api/blue/v1/documents/{sessionId}/retry'],
  ['POST','/api/blue/v1/timelines'],['POST','/api/blue/v1/timelines/{timelineId}/entries'],['POST','/api/blue/v1/contents'],
  ['PUT','/api/blue/v1/contents/{blueId}'],['DELETE','/api/blue/v1/contents/{blueId}'],['POST','/api/blue/v1/packages/{packageId}'],['DELETE','/api/blue/v1/packages/{packageId}']
 ];
 assert.equal(producers.length,13);
 const bodies={'application/json':'{"admissionId":"a","status":"PENDING"}','application/yaml':'admissionId: a\nstatus: PENDING\n'};
 for(const [method,path] of producers) for(const [media,rawBody] of Object.entries(bodies))
  assert.deepEqual(engine.admissionFrom(liveSpec,liveOperation(method,path),{status:202,headers:{'content-type':media},rawBody}),{admissionId:'a',status:'PENDING'},`${method} ${path} ${media}`);
 for(const [media,rawBody] of Object.entries(bodies))
  assert.deepEqual(engine.admissionFrom(liveSpec,liveOperation('GET','/api/blue/v1/admissions/{admissionId}'),{status:200,headers:{'content-type':media},rawBody}),{admissionId:'a',status:'PENDING'},`GET admission ${media}`);
});
test('stream parser handles chunk splits, CRLF, multiline data and HTTP errors',async()=>{
 const encoder=new TextEncoder();globalThis.fetch=async()=>new Response(new ReadableStream({start(c){for(const s of ['event: cha','nge\r\ndata: one\r','\ndata: two\r\n\r\n',': keepalive\n\n','id: 2\ndata: final\n\n'])c.enqueue(encoder.encode(s));c.close();}}),{headers:{'Content-Type':'text/event-stream'}});
 const frames=[];let meta;await engine.executeStream(engine.buildRequest(operation(),draft()),undefined,r=>meta=r,f=>{assert.ok(meta.bytes>0);frames.push(f);});
 assert.equal(meta.status,200);assert.equal(frames.length,2);assert.equal(frames[0].data,'one\ntwo');assert.equal(frames[0].event,'change');assert.equal(frames[1].id,'2');
 globalThis.fetch=async()=>new Response('{"status":401}',{status:401,headers:{'Content-Type':'application/problem+json'}});
 await engine.executeStream(engine.buildRequest(operation(),draft()),undefined,r=>meta=r,()=>assert.fail('No frame expected'));assert.equal(meta.status,401);assert.equal(meta.rawBody,'{"status":401}');
});

test('stream parser dispatches trailing CR blank line at EOF but drops incomplete events',async()=>{
 const encoder=new TextEncoder();const req=engine.buildRequest(operation(),draft());
 const stream=(chunks)=>new Response(new ReadableStream({start(c){for(const s of chunks)c.enqueue(encoder.encode(s));c.close();}}),{headers:{'Content-Type':'text/event-stream'}});
 let frames=[];globalThis.fetch=async()=>stream(['data: complete\r\r']);
 await engine.executeStream(req,undefined,()=>{},frame=>frames.push(frame));
 assert.equal(frames.length,1);assert.equal(frames[0].data,'complete');
 frames=[];globalThis.fetch=async()=>stream(['data: incomplete']);
 await engine.executeStream(req,undefined,()=>{},frame=>frames.push(frame));
 assert.equal(frames.length,0);
 frames=[];globalThis.fetch=async()=>stream(['data: split\r','\n\r','\n']);
 await engine.executeStream(req,undefined,()=>{},frame=>frames.push(frame));
 assert.equal(frames.length,1);assert.equal(frames[0].data,'split');
});

test('explicit retry sends the same prepared mutation and idempotency key unchanged', async () => {
 const op=operation('POST','/api/blue/v1/contents'); op.parameters=[];
 op.requestBody={required:true,content:{'application/json':{}}};
 const req=engine.buildRequest(op,draft({sendBody:true,body:'{"content":{"blueId":"test-reference"}}'}));
 const captured=[];
 globalThis.fetch=async(url,init)=>{captured.push({url,body:init.body,headers:{...init.headers}}); if(captured.length===1) throw new TypeError('Connection lost'); return new Response('{"admissionId":"a","status":"PENDING"}',{status:202});};
 await assert.rejects(engine.executeRequest(req));
 assert.equal(captured.length,1);
 assert.equal((await engine.executeRequest(req)).status,202);
 assert.deepEqual(captured[1],captured[0]);
});

test('HEAD and 204 preserve headers and an empty body', async () => {
 for(const [method,status] of [['HEAD',200],['DELETE',204]]) {
  globalThis.fetch=async()=>new Response(null,{status,headers:{'X-Request-ID':'empty-response'}});
  const result=await engine.executeRequest(engine.buildRequest(operation(method),draft()));
  assert.equal(result.status,status); assert.equal(result.rawBody,''); assert.equal(result.bytes,0);
  assert.equal(result.headers['x-request-id'],'empty-response');
 }
});

test('account-only requests send no implicit profile in either authentication mode',()=>{
  for (const auth of [{mode:'session'},{mode:'bearer',token:'test-token'}]) {
    const req=engine.buildRequest(operation(),draft({auth}));
    assert.equal(new Headers(req.headers).has('X-MyOS-Profile'),false);
    assert.equal(req.url,'/api/blue/v1/contents/owner%230');
  }
});
