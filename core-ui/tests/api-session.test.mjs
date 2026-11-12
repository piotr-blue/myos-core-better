import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const compiled = (await build({entryPoints:['src/api.ts'],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
const compiledTransport = (await build({entryPoints:['src/transport.ts'],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
globalThis.localStorage={getItem:()=>null};
async function fresh(source=compiled){return import('data:text/javascript;base64,'+Buffer.from(source+'\n//'+Math.random()).toString('base64'));}
const response=(status,body={})=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
test('current read-model membership and account denials invalidate selected UI state',async()=>{
 const previousWindow=globalThis.window;const events=[];
 globalThis.window={dispatchEvent:event=>events.push(event.type)};
 try{
  const {authenticatedFetch}=await fresh(compiledTransport);
  for(const code of ['membership_inactive','account_unavailable','membership_required','account_inactive']){
   globalThis.fetch=async()=>response(403,{code});
   assert.equal((await authenticatedFetch('documents')).status,403);
  }
  assert.deepEqual(events,Array(4).fill('myos-session-changed'));
  globalThis.fetch=async()=>response(403,{code:'insufficient_role'});
  assert.equal((await authenticatedFetch('documents')).status,403);
  assert.equal(events.length,4,'A role-limited operation does not invalidate the selected account');
 }finally{if(previousWindow===undefined)delete globalThis.window;else globalThis.window=previousWindow;}
});
test('parallel expired-cookie requests refresh once and preserve write payload and idempotency',async()=>{
 let refreshed=false,refreshes=0;const writes=[];
 globalThis.fetch=async(path,options)=>{if(path==='/api/auth/refresh'){refreshes++;await new Promise(r=>setTimeout(r,10));refreshed=true;return response(200);}
 if(options.method==='POST')writes.push({body:options.body,key:new Headers(options.headers).get('Idempotency-Key')});return refreshed?response(200,{ok:true}):response(401);};
 const {api}=await fresh();const results=await Promise.all([api('documents/x/operations',{method:'POST',body:'{"operation":"update"}',headers:{'Idempotency-Key':'fixed'}}),api('packages')]);
 assert.equal(refreshes,1);assert.deepEqual(results,[{ok:true},{ok:true}]);assert.deepEqual(writes,[{body:'{"operation":"update"}',key:'fixed'},{body:'{"operation":"update"}',key:'fixed'}]);
});
test('explicit API-key rejection never switches to cookie authentication',async()=>{
 const paths=[];globalThis.fetch=async path=>{paths.push(path);return response(401);};const {api}=await fresh();
 await assert.rejects(api('documents',{headers:{Authorization:'Bearer invalid'}}));assert.deepEqual(paths,['/api/blue/v1/documents']);
});
test('failed refresh returns the original rejection without retry loops',async()=>{
 const paths=[];globalThis.fetch=async path=>{paths.push(path);return response(401);};const {api}=await fresh();
 await assert.rejects(api('documents'));assert.deepEqual(paths,['/api/blue/v1/documents','/api/auth/refresh']);
});
test('uppercase admission returns stored details after polling and clears the pending key',async()=>{
 const saved=new Map();globalThis.sessionStorage={getItem:key=>saved.get(key),setItem:(key,value)=>saved.set(key,value),removeItem:key=>saved.delete(key)};
 const paths=[];globalThis.fetch=async path=>{paths.push(path);return response(200,path.endsWith('/admissions/a')?{status:'SUCCEEDED',admissionId:'a',details:{sessionId:'s'}}:{status:'PENDING',admissionId:'a'});};
 const {mutate}=await fresh();assert.deepEqual(await mutate('documents',{source:'name: Example'}),{sessionId:'s'});
 assert.deepEqual(paths,['/api/blue/v1/documents','/api/blue/v1/admissions/a']);assert.equal(saved.size,0);
});
test('uppercase failed admission reports its problem',async()=>{
 globalThis.sessionStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{}};
 globalThis.fetch=async()=>response(200,{status:'FAILED',admissionId:'failed',problem:{detail:'Invalid exact content'}});
 const {mutate}=await fresh();await assert.rejects(mutate('contents',{content:{}}),/Invalid exact content/);
});
test('accepted admission waits for its first projection without repeating the mutation',async()=>{
 const saved=new Map();globalThis.sessionStorage={getItem:key=>saved.get(key),setItem:(key,value)=>saved.set(key,value),removeItem:key=>saved.delete(key)};
 let writes=0,reads=0;
 globalThis.fetch=async(path,options)=>{
  if(options.method==='POST'){writes++;return response(202,{status:'PENDING',admissionId:'a'});}
  assert.equal(path,'/api/blue/v1/admissions/a');
  return ++reads===1?response(404,{code:'ADMISSION_NOT_FOUND'}):response(200,{status:'SUCCEEDED',admissionId:'a',details:{sessionId:'s'}});
 };
 const {mutate}=await fresh();assert.deepEqual(await mutate('documents/bootstrap',{}),{sessionId:'s'});
 assert.equal(writes,1);assert.equal(reads,2);assert.equal(saved.size,0);
});
test('admission wait propagates later absence, other not-found errors and authorization failures',async()=>{
 for(const [status,code,observed] of [[404,'ADMISSION_NOT_FOUND',true],[404,'ACCOUNT_NOT_FOUND',false],[403,'MEMBERSHIP_INACTIVE',false]]){
  const saved=new Map();globalThis.sessionStorage={getItem:key=>saved.get(key),setItem:(key,value)=>saved.set(key,value),removeItem:key=>saved.delete(key)};
  let writes=0,reads=0;
  globalThis.fetch=async(_path,options)=>{
   if(options.method==='POST'){writes++;return response(202,{status:'PENDING',admissionId:'a'});}
   if(++reads===1&&observed)return response(200,{status:'PENDING',admissionId:'a'});
   return response(status,{code,detail:'Cannot read receipt'});
  };
  const {mutate}=await fresh();await assert.rejects(mutate('documents/bootstrap',{}),error=>error.status===status&&error.code===code);
  assert.equal(writes,1);assert.equal(reads,observed?2:1);assert.equal(saved.size,1);
 }
});
test('raw reads and rejected mutations do not wait for admission projection',async()=>{
 globalThis.sessionStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{}};
 let calls=0;globalThis.fetch=async()=>{calls++;return response(404,{code:'ADMISSION_NOT_FOUND'});};
 const {api,mutate}=await fresh();
 await assert.rejects(api('admissions/unknown'),error=>error.status===404);
 await assert.rejects(mutate('documents/bootstrap',{}),error=>error.status===404);
 assert.equal(calls,2);
});
test('permanent initial absence remains bounded and retains the pending mutation',async()=>{
 const saved=new Map();globalThis.sessionStorage={getItem:key=>saved.get(key),setItem:(key,value)=>saved.set(key,value),removeItem:key=>saved.delete(key)};
 const now=Date.now;let elapsed=0,reads=0;
 Date.now=()=>now()+elapsed;
 try{
  globalThis.fetch=async(_path,options)=>{
   if(options.method==='POST')return response(202,{status:'PENDING',admissionId:'a'});
   if(++reads===2)elapsed=16*60*1000;
   return response(404,{code:'ADMISSION_NOT_FOUND'});
  };
  const {mutate}=await fresh();await assert.rejects(mutate('documents/bootstrap',{}),error=>error.status===404);
  assert.equal(reads,2);assert.equal(saved.size,1);
 }finally{Date.now=now;}
});
test('management delivery status is not mistaken for an admission',async()=>{
 globalThis.sessionStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{}};
 const delivery={id:'delivery',status:'PENDING'};
 globalThis.fetch=async()=>response(200,delivery);
 const {mutate}=await fresh();assert.deepEqual(await mutate('webhooks/h/events/event/retry',{}),delivery);
});
test('allItems follows opaque tokens without dropping later management rows',async()=>{
 const paths=[];globalThis.fetch=async path=>{paths.push(path);return response(200,path.includes('pageToken=')?{items:[{id:'second'}],nextPageToken:null}:{items:[{id:'first'}],nextPageToken:'opaque+/='});};
 const {allItems}=await fresh();assert.deepEqual(await allItems('webhooks'),[{id:'first'},{id:'second'}]);
 assert.deepEqual(paths,['/api/blue/v1/webhooks?limit=100','/api/blue/v1/webhooks?limit=100&pageToken=opaque%2B%2F%3D']);
});
test('allItems stops at a caught-up FOLLOW cursor even though it remains reusable',async()=>{
 let reads=0;globalThis.fetch=async()=>{assert.equal(++reads,1,'must not poll a caught-up feed indefinitely');return response(200,{items:[{id:'event'}],hasMore:false,nextPageToken:'follow'});};
 const {allItems}=await fresh();assert.deepEqual(await allItems('events'),[{id:'event'}]);
});

test('reads survive transient workspace handoff and transport keeps its short retry budget',async()=>{
 let calls=0;globalThis.fetch=async()=>{calls++;return calls===3?response(200,{ok:true}):new Response('{}',{status:503,headers:{'Retry-After':'0'}});};
 let {api}=await fresh();assert.deepEqual(await api('documents'),{ok:true});assert.equal(calls,3);
 calls=0;globalThis.fetch=async()=>{calls++;return new Response('{}',{status:503,headers:{'Retry-After':'0'}});};
 // The transport yields after four attempts; api() owns the longer, bounded
 // deployment-recovery window, whose deadline is covered in service-recovery tests.
 const {authenticatedFetch}=await fresh(compiledTransport);
 assert.equal((await authenticatedFetch('documents')).status,503);assert.equal(calls,4);
});
test('transport never replays an unkeyed mutation on overload',async()=>{
 let calls=0;globalThis.fetch=async()=>{calls++;return response(503);};const {api}=await fresh();
 await assert.rejects(api('api-keys',{method:'POST',body:'{}'}));assert.equal(calls,1);
});

test('key revocation retains one mutation key after a lost response and accepts empty 204',async()=>{
 const stored=new Map();globalThis.sessionStorage={getItem:key=>stored.get(key)??null,setItem:(key,value)=>stored.set(key,value),removeItem:key=>stored.delete(key)};
 const attempts=[];globalThis.fetch=async(path,options)=>{
  attempts.push({path,method:options.method,body:options.body,key:new Headers(options.headers).get('Idempotency-Key')});
  if(attempts.length===1)throw new TypeError('Connection closed after request');
  return new Response(null,{status:204});
 };
 const {mutate}=await fresh();assert.deepEqual(await mutate('api-keys/revocable',{},'DELETE'),{});
 assert.equal(attempts.length,2);assert.ok(attempts[0].key);assert.deepEqual(attempts[1],attempts[0]);
 assert.equal(attempts[0].method,'DELETE');assert.equal(attempts[0].path,'/api/blue/v1/api-keys/revocable');
 assert.equal(stored.size,0,'Only the acknowledged completion clears the persisted retry identity');
});

test('mutation identity survives session recovery and a lost write response until the full acknowledgement',async()=>{
 const stored=new Map();globalThis.sessionStorage={getItem:key=>stored.get(key)??null,setItem:(key,value)=>stored.set(key,value),removeItem:key=>stored.delete(key)};
 const writes=[];let refreshes=0,acknowledgement;
 let finalResponse;const reachedFinalResponse=new Promise(resolve=>{finalResponse=resolve;});
 globalThis.fetch=async(path,options)=>{
  if(path==='/api/auth/refresh')return response(++refreshes===1?503:200);
  writes.push({path,method:options.method,body:options.body,key:new Headers(options.headers).get('Idempotency-Key')});
  if(writes.length===1)return response(401);
  if(writes.length===2)throw new TypeError('Connection lost after mutation committed');
  const result=new Response(new ReadableStream({start(controller){acknowledgement=controller;}}),{status:200,headers:{'Content-Type':'application/json'}});
  finalResponse();return result;
 };
 const {mutate}=await fresh();
 const pending=mutate('documents/example/operations',{operation:'bump'});
 await reachedFinalResponse;
 try{
  assert.equal(refreshes,2,'A transient refresh failure must recover without rejecting the mutation');
  assert.equal(writes.length,3);
  assert.ok(writes[0].key);
  assert.deepEqual(writes,[writes[0],writes[0],writes[0]],'Authentication and network retries must preserve the exact command');
  assert.equal(writes[0].body,'{"operation":"bump"}');
  assert.equal(writes[0].method,'POST');
  assert.equal(writes[0].path,'/api/blue/v1/documents/example/operations');
  assert.deepEqual([...stored.values()].map(value=>JSON.parse(value).key),[writes[0].key],'Receiving success headers must not clear an unacknowledged command');
 }finally{
  acknowledgement.enqueue(new TextEncoder().encode('{"status":"SUCCEEDED","admissionId":"a","details":{"applied":true}}'));
  acknowledgement.close();
 }
 assert.deepEqual(await pending,{applied:true});
 assert.equal(stored.size,0,'The complete acknowledgement clears the persisted command identity');
});

test('bootstrap reports admission progress before completion and preserves the pending key across reload',async()=>{
 const saved=new Map();globalThis.sessionStorage={getItem:key=>saved.get(key),setItem:(key,value)=>saved.set(key,value),removeItem:key=>saved.delete(key)};
 const payload={document:'name: Counter',participantMappings:[]};
 const keys=[];let writes=0,reauthenticated=false,resumedReads=0;
 globalThis.fetch=async(path,options)=>{
  if(options.method==='POST'){
   keys.push(new Headers(options.headers).get('Idempotency-Key'));
   if(++writes===1)return response(202,{admissionId:'bootstrap-a',status:'PENDING',details:null});
   return response(202,{admissionId:'bootstrap-a',status:'SUCCEEDED',details:{sessionId:'target'}});
  }
  if(!reauthenticated)return response(403,{detail:'Session expired'});
  return ++resumedReads===1?response(404,{code:'ADMISSION_NOT_FOUND'}):response(200,{admissionId:'bootstrap-a',status:'SUCCEEDED',details:{sessionId:'target'}});
 };
 const changes=[];
 const first=await fresh();
 await assert.rejects(first.mutate('documents/bootstrap',payload,'POST',state=>changes.push(state)),/Session expired/);
 assert.equal(changes[0]?.status,'PENDING');
 assert.equal(changes[0]?.details,null,'Pending bootstrap has no result details');
 assert.equal(saved.size,1,'Unfinished mutation retains retry identity');
 assert.equal(JSON.parse([...saved.values()][0]).admissionId,'bootstrap-a');
 reauthenticated=true;
 const reloaded=await fresh();
 assert.deepEqual(await reloaded.mutate('documents/bootstrap',payload,'POST',state=>changes.push(state)),{sessionId:'target'});
 assert.equal(changes.at(-1).status,'SUCCEEDED');
 assert.equal(writes,1,'Reload resumes the accepted admission without another POST');assert.equal(resumedReads,2);assert.equal(keys.length,1);assert.equal(saved.size,0);
});


test('generated SDK calls retain portal cookies when credentials are unspecified',async()=>{
 const sdkSource=(await build({stdin:{contents:`
  import {AccountApi,Configuration} from '../sdk/typescript/generated';
  import {authenticatedFetch} from './src/transport';
  export const account=new AccountApi(new Configuration({basePath:'',fetchApi:(url,init)=>authenticatedFetch(String(url),init)}));
 `,resolveDir:process.cwd(),loader:'ts'},bundle:true,write:false,format:'esm'})).outputFiles[0].text;
 const calls=[];
 globalThis.fetch=async(path,options)=>{
  calls.push({path,credentials:options.credentials});
  return response(200,{automaticallyUpdatePartyAccess:false});
 };
 const {account}=await fresh(sdkSource);
 assert.equal((await account.getPartyAccessAutomation()).automaticallyUpdatePartyAccess,false);
 assert.deepEqual(calls,[{path:'/api/blue/v1/account/access-automation',credentials:'include'}]);
 const {authenticatedFetch}=await fresh(compiledTransport);
 await authenticatedFetch('info',{credentials:'omit'});
 assert.equal(calls.at(-1).credentials,'omit','An explicit credential choice remains respected');
});
