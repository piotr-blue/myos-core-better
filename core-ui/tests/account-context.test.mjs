import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const compiled=(await build({stdin:{contents:'export * from "./src/account-context"; export * from "./src/api"; export * from "./src/account-api"; export * from "./src/explorer-request";',resolveDir:process.cwd(),loader:'ts'},bundle:true,write:false,format:'esm'})).outputFiles[0].text;
async function fresh(){return import('data:text/javascript;base64,'+Buffer.from(compiled+'\n//'+Math.random()).toString('base64'));}
const account=(userId='u1',generation=1,revision=1)=>({userId,accountId:'same-account',sessionContext:`guard:${userId}:${generation}`,membership:{generation,revision,role:'ADMIN',status:'ACTIVE'}});
const storage=()=>{const values=new Map();globalThis.sessionStorage={getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value),removeItem:key=>values.delete(key)};return values;};
globalThis.location={origin:'https://console.example.test'};

test('generated SDK and raw transport pin the same selected User/account/generation headers',async()=>{
 storage();const sdk=await fresh();sdk.setAccountContext(account());const calls=[];
 globalThis.fetch=async(url,options)=>{calls.push({url,headers:new Headers(options.headers)});return Response.json({items:[]});};
 await sdk.accountApi().listAccountMembers();await sdk.api('documents?limit=2');
 for(const call of calls){assert.equal(call.headers.get('X-MyOS-Account'),'same-account');assert.equal(call.headers.get('X-MyOS-Session-Context'),'guard:u1:1');}
 assert.equal(calls[1].url,'/api/blue/v1/documents?limit=2');
 const old=sdk.captureContext();sdk.setAccountContext(account('u2'));
 await assert.rejects(sdk.api('documents',{},old),/account session changed/);assert.equal(calls.length,2);
});
test('selection belongs to one User and membership generation',async()=>{
 const values=storage();const sdk=await fresh();
 const select=()=>values.set('myos-core-account',JSON.stringify({accountId:'same-account',userId:'u1',generation:1}));
 const current=(userId,generation)=>({userId,accounts:[{accountId:'same-account',membership:{status:'ACTIVE',generation}}]});
 select();assert.equal(sdk.selectedAccount(current('u1',1)),'same-account');
 assert.equal(sdk.selectedAccount(current('u2',1)),undefined);assert.equal(values.has("myos-core-account"),false);assert.equal(sdk.accountChoiceRequired(),true);
 select();assert.equal(sdk.selectedAccount(current('u1',2)),undefined);assert.equal(values.has("myos-core-account"),false);assert.equal(sdk.accountChoiceRequired(),true);
});
for(const next of ['replacement',undefined])test(`reload handoff to ${next ?? 'account choice'} blocks departing-page requests until the new document`,async()=>{
 const values=storage(),sdk=await fresh(),previousLocation=globalThis.location,previousWindow=globalThis.window,calls=[];
 let changed=0,reloads=0;
 globalThis.location={origin:'https://console.example.test',hash:'#documents',reload(){reloads++;}};
 globalThis.window=new EventTarget();
 globalThis.window.addEventListener('myos-session-changed',()=>{changed++;sdk.chooseAccount();});
 sdk.rememberAccount('same-account','u1',1);sdk.setAccountContext(account());
 globalThis.fetch=async(url,options)=>{
  const headers=new Headers(options.headers);calls.push({url,guard:headers.get('X-MyOS-Session-Context')});
  return headers.has('X-MyOS-Session-Context')?Response.json({items:[]}):Response.json({title:'session_changed'},{status:409});
 };
 try{
  await sdk.api('documents');assert.equal(calls.length,1);
  sdk.chooseAccount(next,next?'u1':undefined,next?1:undefined);
  // Components can render after reload() is requested but before navigation finishes.
  await assert.rejects(sdk.api('documents'),error=>error.name==='AbortError');
  await assert.rejects(sdk.accountResult(sdk.accountApi().listAccountMembers()),error=>error.name==='AbortError');
  await assert.rejects(sdk.discoverAccounts(),error=>error.name==='AbortError');
  assert.equal(calls.length,1);assert.equal(changed,0);assert.equal(reloads,1);
  assert.equal(sdk.selectedAccount(),next);assert.equal(sdk.accountChoiceRequired(),!next);
  const newDocument=await fresh();
  assert.equal(newDocument.accountSignal().aborted,false);
  assert.equal(newDocument.selectedAccount(),next);
  newDocument.setAccountContext(next?{...account(),accountId:next}:undefined,{userId:'u1',sessionContext:'base-guard'});
  await newDocument.api(next?'documents':'me');assert.equal(calls.length,2);
 }finally{globalThis.location=previousLocation;globalThis.window=previousWindow;}
});
test('remembering an account in place keeps the replacement scope usable',async()=>{
 storage();const sdk=await fresh(),calls=[];
 sdk.setAccountContext(account());const old=sdk.accountSignal();
 sdk.rememberAccount('replacement','u1',1);
 assert.equal(old.aborted,true);assert.equal(sdk.accountSignal().aborted,false);
 sdk.setAccountContext({...account(),accountId:'replacement'});
 globalThis.fetch=async(url,options)=>{calls.push(new Headers(options.headers));return Response.json({items:[]});};
 await sdk.api('documents');await sdk.accountApi().listAccountMembers();
 assert.equal(calls.length,2);
 for(const headers of calls)assert.equal(headers.get('X-MyOS-Account'),'replacement');
});
test('pending mutations isolate Users and generations, while same-generation reauthentication retains intent',async()=>{
 storage();const sdk=await fresh(),keys=[];globalThis.fetch=async(url,options)=>{keys.push(new Headers(options.headers).get('Idempotency-Key'));return Response.json({detail:'Authority changed'},{status:403});};
 for(const current of [account(),{...account(),sessionContext:'new-login-guard'},account('u2'),account('u1',2)]){
  sdk.setAccountContext(current);await assert.rejects(sdk.mutate('documents/bootstrap',{document:'name: Example'}),/Authority changed/);
 }
 assert.equal(keys[0],keys[1]);assert.equal(new Set([keys[0],keys[2],keys[3]]).size,3);
});
test('raw Explorer snapshots pin selection, prevent header overrides and omit cookie context for explicit Bearer',async()=>{
 storage();const sdk=await fresh();sdk.setAccountContext(account());
 const op={method:'GET',path:'/api/blue/v1/documents',parameters:[{in:'header',name:'X-MyOS-Account',required:true},{in:'header',name:'X-MyOS-Session-Context',required:true}],responses:{}};
 const draft={parameters:{},body:'',sendBody:false,mediaType:'application/json',accept:'application/json',auth:{mode:'session'}};
 const prepared=sdk.buildRequest(op,draft);sdk.setAccountContext(account('u2'));
 assert.equal(prepared.headers['X-MyOS-Session-Context'],'guard:u1:1');
 assert.throws(()=>sdk.buildRequest(op,{...draft,headers:{'X-MyOS-Session-Context':'injected'}}),/request controls/);
 const bearer=sdk.buildRequest(op,{...draft,auth:{mode:'bearer',token:'explicit'}});
 assert.equal(bearer.headers['X-MyOS-Account'],undefined);assert.equal(bearer.headers['X-MyOS-Session-Context'],undefined);assert.equal(bearer.credentials,'omit');
 assert.ok(!sdk.copyCurl(prepared).includes('guard:u1:1'));
});

test('account creation retains its intent across an unknown response and same-User reauthentication',async()=>{
 const saved=storage(),sdk=await fresh(),keys=[];let attempt=0;
 globalThis.fetch=async(url,options)=>{keys.push(new Headers(options.headers).get('Idempotency-Key'));assert.equal(new Headers(options.headers).has('X-MyOS-Account'),false);if(++attempt===1)throw new TypeError('Response lost');return Response.json({accountId:'created'});};
 const input={kind:'PERSONAL',profile:{displayName:'New workspace'}};
 const invoke=(api,key)=>api.createAccount({idempotencyKey:key,createAccount:input});
 await assert.rejects(sdk.accountCommand({userId:'u1',sessionContext:'first-login'},'create-account',input,invoke));assert.equal(saved.size,1);
 assert.deepEqual(await sdk.accountCommand({userId:'u1',sessionContext:'new-login'},'create-account',input,invoke),{accountId:'created'});assert.equal(keys[0],keys[1]);assert.equal(saved.size,0);
 await sdk.accountCommand({userId:'u1',sessionContext:'new-login'},'create-account',input,invoke);assert.notEqual(keys[1],keys[2]);
});

test('a remove/rejoin between User discovery and selected discovery cannot revive the selected tab',async()=>{
 const sdk=await fresh();const current={...account(),accountModel:'MEMBERSHIPS_V1',kind:'PERSONAL',profile:{displayName:'Workspace'}};
 const discovered={userId:'u1',accounts:[current]};
 assert.equal(sdk.checkedAccount(current,discovered,'same-account'),current);
 assert.throws(()=>sdk.checkedAccount({...current,membership:{...current.membership,generation:2}},discovered,'same-account'),error=>error.status===409);
 assert.throws(()=>sdk.checkedAccount({...current,userId:'u2'},discovered,'same-account'),error=>error.status===409);
});

test('logout and automatic refresh carry the same captured selected session guard',async()=>{
 storage();const sdk=await fresh();sdk.setAccountContext(account());const calls=[];let refreshed=false;
 globalThis.fetch=async(url,options)=>{
  calls.push({url,headers:new Headers(options.headers)});
  if(url==='/api/auth/refresh'){refreshed=true;return Response.json({refreshed:true});}
  return Response.json({}, {status:url.endsWith('/documents')&&!refreshed?401:200});
 };
 await sdk.api('documents');await sdk.api('/api/auth/logout',{method:'POST'});
 assert.deepEqual(calls.map(call=>call.url),['/api/blue/v1/documents','/api/auth/refresh','/api/blue/v1/documents','/api/auth/logout']);
 for(const call of calls){assert.equal(call.headers.get('X-MyOS-Account'),'same-account');assert.equal(call.headers.get('X-MyOS-Session-Context'),'guard:u1:1');}
 const old=sdk.captureContext();sdk.setAccountContext(account('u2'));
 await assert.rejects(sdk.api('/api/auth/logout',{method:'POST'},old),/account session changed/);
 assert.equal(calls.length,4,'A stale tab cannot log out the replacement session');
});
test('an authenticated User without a selected account can guard refresh and logout',async()=>{
 storage();const sdk=await fresh();sdk.setAccountContext(undefined,{userId:'u1',sessionContext:'login-guard'});const calls=[];let refreshed=false;
 globalThis.fetch=async(url,options)=>{calls.push({url,headers:new Headers(options.headers)});if(url==='/api/auth/refresh'){refreshed=true;return Response.json({});}return Response.json({}, {status:url.endsWith('/me')&&!refreshed?401:200});};
 await sdk.api('me');await sdk.api('/api/auth/logout',{method:'POST'});
 for(const call of calls){assert.equal(call.headers.has('X-MyOS-Account'),false);assert.equal(call.headers.get('X-MyOS-Session-Context'),'login-guard');}
});

const discoveredAccount=(userId='u1',generation=1)=>({...account(userId,generation),accountModel:'MEMBERSHIPS_V1',kind:'BUSINESS',profile:{displayName:'Workspace'}});
const discoveredUser=(selected=discoveredAccount(),sessionContext='base-login-guard')=>({userId:selected.userId,email:'user@example.test',accountModel:'MEMBERSHIPS_V1',sessionContext,sessionExpiresAt:Date.now()+60000,preferences:{defaultAccountId:selected.accountId,revision:1},accounts:[selected]});
const selectStored=(values,userId='u1',generation=1)=>values.set('myos-core-account',JSON.stringify({accountId:'same-account',userId,generation}));

test('initial discovery refreshes with the newly discovered base User guard before installing selected context',async()=>{
 const values=storage();selectStored(values);const sdk=await fresh(),calls=[];let refreshed=false;
 const selected=discoveredAccount(),user=discoveredUser(selected);
 globalThis.fetch=async(url,options)=>{
  calls.push({url,headers:new Headers(options.headers)});
  assert.equal(sdk.captureContext(),undefined,'Discovery must not install its provisional guard');
  if(url.endsWith('/me'))return Response.json(user);
  if(url==='/api/auth/refresh'){refreshed=true;return Response.json({});}
  return refreshed?Response.json(selected):Response.json({},{status:401});
 };
 const result=await sdk.discoverAccounts();
 assert.equal(result.user.userId,'u1');assert.equal(result.account.sessionContext,'guard:u1:1');
 assert.deepEqual(calls.map(call=>call.url),['/api/blue/v1/me','/api/blue/v1/account','/api/auth/refresh','/api/blue/v1/account']);
 for(const index of [1,3]){assert.equal(calls[index].headers.get('X-MyOS-Account'),'same-account');assert.equal(calls[index].headers.has('X-MyOS-Session-Context'),false);}
 assert.equal(calls[2].headers.get('X-MyOS-Session-Context'),'base-login-guard');assert.equal(calls[2].headers.has('X-MyOS-Account'),false);
 assert.equal(sdk.captureContext(),undefined);
});

test('existing discovery retains its captured guard when a replacement login would require refresh',async()=>{
 const values=storage();selectStored(values);const sdk=await fresh();sdk.setAccountContext(account());const prior=sdk.captureContext(),calls=[];
 globalThis.fetch=async(url,options)=>{
  calls.push({url,headers:new Headers(options.headers)});assert.equal(sdk.captureContext(),prior);
  if(url.endsWith('/me'))return Response.json(discoveredUser(discoveredAccount(),'replacement-login-guard'));
  return Response.json({}, {status:url==='/api/auth/refresh'?409:401});
 };
 await assert.rejects(sdk.discoverAccounts(),error=>error.status===401);
 assert.deepEqual(calls.map(call=>call.url),['/api/blue/v1/me','/api/blue/v1/account','/api/auth/refresh']);
 for(const call of calls.slice(1)){assert.equal(call.headers.get('X-MyOS-Account'),'same-account');assert.equal(call.headers.get('X-MyOS-Session-Context'),'guard:u1:1');}
 assert.equal(sdk.captureContext(),prior);
});

test('discovery rejects a changed User or membership generation before selected requests or refresh',async()=>{
 for(const replacement of [discoveredAccount('u2'),discoveredAccount('u1',2)]){
  const values=storage();selectStored(values);const sdk=await fresh();sdk.setAccountContext(account());const prior=sdk.captureContext(),calls=[];
  globalThis.fetch=async(url)=>{calls.push(url);return Response.json(discoveredUser(replacement));};
  await assert.rejects(sdk.discoverAccounts(),error=>error.status===409);
  assert.deepEqual(calls,['/api/blue/v1/me']);assert.equal(sdk.captureContext(),prior);
 }
});

test('initial discovery rejects a rejoin occurring between the two responses without installing either guard',async()=>{
 const values=storage();selectStored(values);const sdk=await fresh();
 globalThis.fetch=async(url)=>Response.json(url.endsWith('/me')?discoveredUser():discoveredAccount('u1',2));
 await assert.rejects(sdk.discoverAccounts(),error=>error.status===409);assert.equal(sdk.captureContext(),undefined);
});

test('own API-key list uses the generated SDK without pagination or truncation',async()=>{
 storage();const sdk=await fresh();sdk.setAccountContext(account());const calls=[];
 const items=Array.from({length:1001},(_,index)=>({id:`key-${index}`,name:`Key ${index}`,membershipGeneration:1,effective:true}));
 globalThis.fetch=async(url,options)=>{calls.push({url,headers:new Headers(options.headers)});return Response.json({items});};
 const result=await sdk.listOwnApiKeys();assert.equal(result.length,1001);assert.equal(result.at(-1).id,'key-1000');
 assert.equal(calls.length,1);assert.equal(calls[0].url,'/api/blue/v1/api-keys');
 assert.equal(calls[0].headers.get('X-MyOS-Session-Context'),'guard:u1:1');
});


test('removal recovers only the same User into a sole verified default, never a rejoin or masked default',async()=>{
 const values=storage(),sdk=await fresh();selectStored(values);
 const next={...discoveredAccount(),accountId:'remaining'};
 const user=discoveredUser(next);
 assert.equal(sdk.selectedAccount(user),undefined);
 assert.equal(sdk.accountChoiceRequired(user),false);
 assert.equal(sdk.accountChoiceRequired({...user,userId:'other-human'}),true);
 assert.equal(sdk.accountChoiceRequired({...user,preferences:{defaultAccountId:null,revision:2}}),true);
 assert.equal(sdk.accountChoiceRequired({...user,accounts:[next,discoveredAccount('u1',2)]}),true);
 assert.equal(sdk.accountChoiceRequired(discoveredUser(discoveredAccount('u1',2))),true);
 values.set('myos-core-account-choice-required','true');assert.equal(sdk.accountChoiceRequired(user),true);
});
test('active-tab removal must cross a fresh context barrier before automatically entering its sole default',async()=>{
 const values=storage(),sdk=await fresh();selectStored(values);sdk.setAccountContext(account());
 const next={...discoveredAccount(),accountId:'remaining'};
 const user=discoveredUser(next),requests=[];
 globalThis.fetch=async url=>{requests.push(url);return Response.json(url.endsWith('/me')?user:next);};
 await assert.rejects(sdk.discoverAccounts(),error=>error.code==='session_changed');
 assert.deepEqual(requests,['/api/blue/v1/me']);assert.equal(sdk.captureContext().accountId,'same-account');
 sdk.setAccountContext();location.hash='#documents/private-old-account';
 const recovered=await sdk.discoverAccounts();
 assert.equal(recovered.account.accountId,'remaining');assert.equal(location.hash,'documents');
 assert.equal(sdk.selectedAccount(user),'remaining');assert.equal(sdk.captureContext(),undefined);
});


test('a late account rejection cannot invalidate the newly chosen context',async()=>{
 storage();const sdk=await fresh(),events=[];let finish;
 globalThis.window={dispatchEvent:event=>events.push(event.type)};
 globalThis.fetch=()=>new Promise(resolve=>{finish=resolve;});
 sdk.setAccountContext(account());const pending=sdk.api('documents');
 sdk.rememberAccount('replacement','u1',1);
 finish(Response.json({title:'session_changed',detail:'Old workspace removed'},{status:409}));
 await assert.rejects(pending,error=>error.name==='AbortError');assert.deepEqual(events,[]);
 delete globalThis.window;
});

test('a late successful command cannot override a newer selection and retains its intent',async()=>{
 const values=storage(),sdk=await fresh();let finish,started;
 const ready=new Promise(resolve=>{started=resolve;});let succeeded=false;
 const pending=sdk.accountCommand({userId:'u1',sessionContext:'base'},'create-account',{},()=>{
  started();return new Promise(resolve=>{finish=resolve;});
 }).then(()=>{succeeded=true;});
 await ready;sdk.rememberAccount('explicit','u1',1);finish({accountId:'created'});
 await assert.rejects(pending,error=>error.name==='AbortError');assert.equal(succeeded,false);
 assert.equal([...values.keys()].filter(key=>key.startsWith('myos-account-intent-')).length,1);
 assert.equal(sdk.selectedAccount(),'explicit');
});

test('sole-default recovery preserves an invitation route after membership removal',async()=>{
 const values=storage(),sdk=await fresh();selectStored(values);
 const next={...discoveredAccount(),accountId:'remaining'},user=discoveredUser(next);
 location.hash='#invitation';
 values.set('myos-pending-invitation','retained-secret');
 globalThis.fetch=async url=>Response.json(url.endsWith('/me')?user:next);
 const recovered=await sdk.discoverAccounts();
 assert.equal(recovered.account.accountId,'remaining');
 assert.equal(location.hash,'#invitation','Recovery must not replace the explicit invitation with Documents');
 assert.equal(values.get('myos-pending-invitation'),'retained-secret');
 assert.equal(sdk.selectedAccount(user),'remaining');
});


test('switching workspace cancels in-flight reads and rejects successful stale responses',async()=>{
 storage();const sdk=await fresh();sdk.setAccountContext(account());let finish,signal;
 globalThis.fetch=async(url,options)=>{signal=options.signal;return new Promise(resolve=>{finish=resolve;});};
 const pending=sdk.api('documents');assert.equal(signal.aborted,false);
 sdk.rememberAccount('replacement','u1',1);assert.equal(signal.aborted,true);
 finish(Response.json({items:[{id:'old-workspace-document'}]}));
 await assert.rejects(pending,error=>error.name==='AbortError');
});

for (const status of [200, 400]) test(`switching account while consuming a ${status} response body rejects stale data and errors`,async()=>{
 storage();const sdk=await fresh();sdk.setAccountContext(account());let body;
 globalThis.fetch=async()=>new Response(new ReadableStream({start(controller){body=controller;}}),{status,headers:{'X-MyOS-Read-State':'initial'}});
 const pending=sdk.api('documents');
 await new Promise(resolve=>setImmediate(resolve));
 sdk.rememberAccount('replacement','u1',1);sdk.setAccountContext({...account(),accountId:'replacement'});
 body.enqueue(new TextEncoder().encode(JSON.stringify(status===200?{items:[]}:{detail:'Old account failure'})));body.close();
 await assert.rejects(pending,error=>error.name==='AbortError');
});

test('read-state metadata stays separate from canonical JSON and unknown headers are not inferred',async()=>{
 storage();const sdk=await fresh();sdk.setAccountContext(account());
 for(const state of ['initial','projected','unexpected',null]){
   const data={items:[],total:0,nextPageToken:null,paginationMode:'OBSERVATION_BOUND'};
   globalThis.fetch=async()=>Response.json(data,{headers:state?{'X-MyOS-Read-State':state}:{}});
   const result=await sdk.apiResponse('documents');
   assert.deepEqual(result.data,data);
   assert.equal(result.readState,['initial','projected'].includes(state)?state:undefined);
   assert.deepEqual(await sdk.api('documents'),data);
 }
});
