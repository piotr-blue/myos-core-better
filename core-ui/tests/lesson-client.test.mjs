import {test} from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {readFileSync} from 'node:fs';
const runtimes=JSON.parse(readFileSync('src/data/process-runtimes.json','utf8'));
const examples=JSON.parse(readFileSync('src/data/clause-examples.json','utf8'));
const exactPackages=examples.find(row=>row.id==='00-hello').packages;
const accessPackages=JSON.parse(readFileSync('src/data/clause-examples-access-events.json','utf8')).find(row=>row.id==='00-hello').packages;
const info=(runtime=runtimes.finalGas,accountId='workspace')=>Response.json({accountId,runtime});
const output=await build({entryPoints:['src/lessons/client.ts'],bundle:true,write:false,format:'esm',plugins:[{name:'local-context',setup(b){
 b.onResolve({filter:/\/account-api$/},()=>({path:'api',namespace:'lesson-test'}));
 b.onResolve({filter:/\/account-context$/},()=>({path:'context',namespace:'lesson-test'}));
 b.onLoad({filter:/.*/,namespace:'lesson-test'},({path})=>({contents:path==='api'?`import {Configuration,AccountApi} from '@blue-labs/myos-sdk';export const coreConfiguration=(c)=>new Configuration({basePath:'https://core.example.test',headers:{'X-MyOS-Account':c?.accountId||'workspace'}});export const accountApi=c=>new AccountApi(coreConfiguration(c));export const accountResult=p=>p;`:`export const captureContext=()=>({accountId:'workspace',userId:'human'});export const accountContext=a=>({accountId:a.accountId,userId:a.userId,sessionContext:a.sessionContext});export const assertContext=()=>{};`,resolveDir:process.cwd()}));
}}]});
const {LessonClient}=await import('data:text/javascript;base64,'+Buffer.from(output.outputFiles[0].text).toString('base64'));
globalThis.sessionStorage={setItem(){}};
const ready={sessionId:'root',lastStable:{documentRef:{blueId:'exact-version'}},processing:{state:'READY'}};
const lesson={...examples.find(row=>row.id==='00-hello'),packages:exactPackages,steps:[{operation:'approve',channel:'owner',request:{revision:1}}]};
const original=examples.find(row=>row.id==='00-hello');
const makeRun=()=>({id:'run',userId:'human',exampleId:original.id,definition:JSON.stringify([original.source,original.packages,original.steps]),identities:{owner:'workspace'},representatives:{},step:0,source:'name: Example',results:[]});
const admission=details=>({admissionId:'admission',status:'SUCCEEDED',details});
test('a bootstrap retry observes its retained session without creating another document',async()=>{
 const run=makeRun();let posts=0,fail=true;
 globalThis.fetch=async(url,init)=>{if(url.endsWith('/info'))return info();if(init.method==='POST'){posts++;assert.deepEqual(JSON.parse(init.body).packages,exactPackages);return Response.json(admission({sessionId:'root'}));}return fail?Response.json({detail:'Unavailable'},{status:400}):Response.json(ready);};
 const client=new LessonClient(()=>{});
 await assert.rejects(client.start(run,lesson));assert.equal(run.root,'root');assert.equal(run.pending.kind,'bootstrap');
 fail=false;await client.start(run,lesson);assert.equal(posts,1);assert.equal(run.results.length,1);assert.equal(run.results[0].interrupted,true);assert.equal(run.pending,undefined);
});
test('an accepted operation resumes observation with the same input, without route rediscovery or resubmission',async()=>{
 const run={...makeRun(),root:'root'};let posts=0,reads=0,fail=true;
 globalThis.fetch=async(url,init)=>{
  if(init.method==='POST'){posts++;return Response.json(admission({entryRef:{blueId:'input'}}));}
  if(url.includes('/operations')){reads++;return Response.json({items:[{name:'approve',routes:[{channel:'owner',timeline:'timeline',actor:'human'}]}]});}
  if(url.includes('/inputs/'))return fail?Response.json({detail:'Read interrupted'},{status:400}):Response.json({localHistory:[{stable:true}]});
  return Response.json(ready);
 };
 const client=new LessonClient(()=>{});await assert.rejects(client.runStep(run,lesson,()=>{}));
 const key=run.pending.key;assert.equal(run.step,0);assert.equal(run.pending.entryId,'input');
 fail=false;await client.runStep(run,lesson,()=>{});assert.equal(posts,1);assert.equal(reads,1);assert.equal(run.step,1);assert.equal(run.results[0].entryId,'input');assert.equal(run.results[0].interrupted,true);assert.ok(key);
});
test('representative negative example retains the requested channel but submits through the representative route',async()=>{
 const run={...makeRun(),root:'root'},negative={steps:[{operation:'leadGreeting',channel:'buyer',source:'buyer__assistant',status:'FAILED',expectedRejectionReason:'INVALID_TARGET_CHANNEL'}]};let sent;
 globalThis.fetch=async(url,init)=>{
  if(init.method==='POST'){sent=JSON.parse(init.body);return Response.json(admission({entryRef:{blueId:'negative-input'}}));}
  if(url.includes('/operations'))return Response.json({items:[{name:'leadGreeting',routes:[{channel:'buyer',timeline:'lead',actor:'lead-actor'}]},{name:'agentGreeting',routes:[{channel:'buyer__assistant',timeline:'representative',actor:'representative-actor'}]}]});
  if(url.includes('/inputs/'))return Response.json({disposition:{outcome:'REJECTED',reason:'INVALID_TARGET_CHANNEL'}});
  return Response.json(ready);
 };
 await new LessonClient(()=>{}).runStep(run,negative,()=>{});
 assert.equal(sent.timeline.blueId,'representative');assert.equal(sent.actor,'representative-actor');assert.equal(sent.message.channel,'buyer');assert.equal(run.results[0].status,'EXPECTED_CHANNEL_REJECTION');
});
test('a connected action resolves its document once and includes discovery in its retained intent',async()=>{
 const run={...makeRun(),root:'root'},childLesson={steps:[{operation:'approve',channel:'owner',target:'/payment'}]};let relationships=0;
 globalThis.fetch=async(url,init)=>{
  if(url.includes('/relationships')){relationships++;assert.ok(run.pending.startedAt);return Response.json({items:[{path:'/payment',source:{sessionId:'payment-session'}}]});}
  if(init.method==='POST'){assert.ok(url.includes('/payment-session/'));return Response.json(admission({entryRef:{blueId:'child-input'}}));}
  if(url.includes('/operations'))return Response.json({items:[{name:'approve',routes:[{channel:'owner',timeline:'timeline',actor:'human'}]}]});
  if(url.includes('/inputs/'))return Response.json({localHistory:[{stable:true}]});
  return Response.json(ready);
 };
 await new LessonClient(()=>{}).runStep(run,childLesson,()=>{});
 assert.equal(relationships,1);assert.equal(run.results[0].sessionId,'payment-session');assert.equal(run.step,1);
});
test('a definitive stale-version rejection allows a new reviewed attempt, while keeping its evidence',async()=>{
 const run={...makeRun(),root:'root'};let posts=0;const keys=[];
 globalThis.fetch=async(url,init)=>{
  if(init.method==='POST'){posts++;keys.push(new Headers(init.headers).get('Idempotency-Key'));return Response.json(admission({entryRef:{blueId:`attempt-${posts}`}}));}
  if(url.includes('/operations'))return Response.json({items:[{name:'approve',routes:[{channel:'owner',timeline:'timeline',actor:'human'}]}]});
  if(url.includes('/inputs/'))return Response.json(posts===1?{disposition:{outcome:'REJECTED',reason:'STALE_DOCUMENT_PRECONDITION'}}:{localHistory:[{stable:true}]});
  return Response.json(ready);
 };
 const client=new LessonClient(()=>{});
 await assert.rejects(client.runStep(run,lesson,()=>{}),/No action was applied/);
 assert.equal(run.step,0);assert.equal(run.pending,undefined);assert.equal(run.results.length,0);
 assert.equal(run.attempts[0].entryId,'attempt-1');
 await client.runStep(run,lesson,()=>{});assert.equal(run.step,1);assert.equal(run.results.length,1);assert.notEqual(keys[0],keys[1]);
});
test('submission uses the current prepared snapshot without weakening its version precondition',async()=>{
 const run={...makeRun(),root:'root'};let sent;
 globalThis.fetch=async(url,init)=>{
  if(init.method==='POST'){sent=JSON.parse(init.body);return Response.json(admission({entryRef:{blueId:'input'}}));}
  if(url.includes('/inputs/'))return Response.json({localHistory:[{stable:true}]});
  throw Error('The guide already supplied its current version and authorized route');
 };
 await new LessonClient(()=>{}).runStep(run,lesson,()=>{},{sessionId:'root',document:{...ready,lastStable:{documentRef:{blueId:'displayed-version'}}},route:{channel:'owner',timeline:'timeline',actor:'human'},step:lesson.steps[0]});
 assert.equal(sent.message.document.blueId,'displayed-version');assert.equal(sent.message.requireExactDocumentVersion,true);
});

test('an explicit unavailable-action check verifies absence and unchanged state without submitting',async()=>{
 const run={...makeRun(),root:'root'},check={steps:[{operation:'refund',channel:'hotel',accessCheck:'unavailable',expect:{'/payment/reversed':0}}]};
 let reads=0;
 globalThis.fetch=async(url,init)=>{
  assert.notEqual(init.method,'POST');
  if(url.endsWith('/content')){reads++;return Response.json({payment:{reversed:0}});}
  if(url.includes('/operations'))return Response.json({items:[{name:'refund',routes:[{channel:'restaurant',timeline:'restaurant'}]}]});
  return Response.json(ready);
 };
 await new LessonClient(()=>{}).runStep(run,check,()=>{});
 assert.equal(run.step,1);assert.equal(run.results[0].status,'EXPECTED_UNAVAILABLE');assert.equal(reads,1);
});
test('an unavailable-action check fails if Core offers that role a route',async()=>{
 const run={...makeRun(),root:'root'},check={steps:[{operation:'refund',channel:'hotel',accessCheck:'unavailable'}]};
 globalThis.fetch=async(url,init)=>{assert.notEqual(init.method,'POST');return Response.json(url.includes('/operations')?{items:[{name:'refund',routes:[{channel:'hotel',timeline:'hotel'}]}]}:ready);};
 await assert.rejects(new LessonClient(()=>{}).runStep(run,check,()=>{}),/offers an authorized route/);
 assert.equal(run.step,0);assert.equal(run.results.length,0);
});


test('a shared step waits for each distinct mapped workspace and resumes without another submission',async()=>{
 const run={...makeRun(),root:'root',identities:{buyer:'workspace',seller:'peer',bank:'peer'}};
 let posts=0,peerReads=0,unavailable=true;
 globalThis.fetch=async(url,init)=>{
  const account=new Headers(init.headers).get('X-MyOS-Account');
  if(init.method==='POST'){posts++;assert.equal(account,'workspace');return Response.json(admission({entryRef:{blueId:'shared-input'}}));}
  if(url.endsWith('/account'))return Response.json({accountId:account,userId:'human',membership:{status:'ACTIVE'},profile:{displayName:'Peer'},sessionContext:'peer-context'});
  if(url.includes('/operations'))return Response.json({items:[{name:'approve',routes:[{channel:'owner',timeline:'timeline',actor:'human'}]}]});
  if(url.includes('/inputs/')){
   if(account==='peer'){peerReads++;if(unavailable)throw Error('Peer not available');}
   return Response.json({localHistory:[{stable:true}]});
  }
  return Response.json(ready);
 };
 const client=new LessonClient(()=>{});
 await assert.rejects(client.runStep(run,lesson,()=>{}),error=>error.cause?.message==='Peer not available');
 assert.equal(run.step,0);assert.equal(run.results.length,0);assert.equal(run.pending.entryId,'shared-input');
 unavailable=false;await client.runStep(run,lesson,()=>{});
 assert.equal(posts,1);assert.equal(peerReads,2);assert.equal(run.step,1);assert.equal(run.results.length,1);
 assert.equal(typeof run.results[0].synchronizationMs,'number');
});

test('shared bootstrap checks the peer identity before reading its document',async()=>{
 const run={...makeRun(),identities:{buyer:'workspace',seller:'peer'}};let peerDocumentReads=0,posts=0;
 globalThis.fetch=async(url,init)=>{
  const account=new Headers(init.headers).get('X-MyOS-Account');
  if(url.endsWith('/info'))return info();
  if(init.method==='POST'){posts++;return Response.json(admission({sessionId:'root'}));}
  if(url.endsWith('/account'))return Response.json({accountId:'different-peer',userId:'human',membership:{status:'ACTIVE'},profile:{displayName:'Peer'}});
  if(account==='peer')peerDocumentReads++;
  return Response.json(ready);
 };
 await assert.rejects(new LessonClient(()=>{}).start(run,lesson),/no longer available/);
 assert.equal(run.results.length,0);assert.equal(peerDocumentReads,0);assert.equal(run.pending,undefined);assert.equal(posts,0);
});


test('fresh runtime selection is persisted before submission and retained through reload',async()=>{
 const run=makeRun(); let saved,posts=0;
 globalThis.sessionStorage={setItem(_key,value){saved=JSON.parse(value);}};
 globalThis.fetch=async(url,init)=>{
  if(url.endsWith('/info'))return info(runtimes.accessEvents);
  if(init.method==='POST'){
   posts++;
   assert.deepEqual(saved.selection.runtime,runtimes.accessEvents);
   assert.deepEqual(JSON.parse(init.body).packages,saved.selection.packages);
   assert.deepEqual(saved.selection.packages,accessPackages);
   assert.ok(!saved.selection.packages.includes(exactPackages[0]));
   return Response.json(admission({sessionId:'root'}));
  }
  throw Error('Interrupted observation');
 };
 await assert.rejects(new LessonClient(()=>{}).start(run,lesson));
 const resumed=JSON.parse(JSON.stringify(run));
 globalThis.fetch=async(url,init)=>{assert.notEqual(init.method,'POST');assert.ok(!url.endsWith('/info'));return Response.json(ready);};
 await new LessonClient(()=>{}).start(resumed,lesson);
 assert.deepEqual(resumed.selection,run.selection);assert.equal(posts,1);
});

test('mixed or unknown mapped runtimes fail before any bootstrap intent or submission',async()=>{
 for(const peerRuntime of [runtimes.accessEvents,{...runtimes.finalGas,provider:'unknown'}]){
  const run={...makeRun(),identities:{buyer:'workspace',seller:'peer'}};let posts=0;
  globalThis.fetch=async(url,init)=>{
   const account=new Headers(init.headers).get('X-MyOS-Account');
   if(init.method==='POST'){posts++;throw Error('Unexpected submission');}
   if(url.endsWith('/account'))return Response.json({accountId:account,userId:'human',membership:{status:'ACTIVE'},profile:{displayName:'Peer'},sessionContext:'peer-context'});
   return info(account==='workspace'?runtimes.finalGas:peerRuntime,account);
  };
  await assert.rejects(new LessonClient(()=>{}).start(run,lesson),/different process package editions|runtime is not supported/);
  assert.equal(posts,0);assert.equal(run.pending,undefined);assert.equal(run.selection,undefined);
 }
});

test('a retained unknown bootstrap submission is never reinterpreted as a new edition',async()=>{
 const run={...makeRun(),pending:{kind:'bootstrap',key:'old-key',accountId:'workspace',startedAt:new Date().toISOString()}};
 globalThis.fetch=async(url,init)=>{assert.notEqual(init.method,'POST');return info(runtimes.accessEvents);};
 await assert.rejects(new LessonClient(()=>{}).start(run,lesson),/earlier package edition/);
 assert.equal(run.pending.key,'old-key');assert.equal(run.selection,undefined);
});

test('a source-binding walkthrough displays and durably retries the exact prepared root reference',async()=>{
 const run={...makeRun(),root:'root'},sourceLesson={steps:[{operation:'bindSource_owner',channel:'owner',target:'/payment',requestDocument:'root',request:{alias:'agreement'}}]};
 let rootVersion='root-before',rootReads=0,posts=0,fail=true;const payloads=[],keys=[];
 globalThis.fetch=async(url,init)=>{
  if(url.includes('/relationships'))return Response.json({items:[{path:'/payment',source:{sessionId:'payment-session'}}]});
  if(init.method==='POST'){
   posts++;payloads.push(JSON.parse(init.body));keys.push(new Headers(init.headers).get('Idempotency-Key'));
   if(fail)throw new Error('Response interrupted after submission');
   return Response.json(admission({entryRef:{blueId:'source-bound'}}));
  }
  if(url.includes('/operations'))return Response.json({items:[{name:'bindSource_owner',routes:[{channel:'owner',timeline:'timeline',actor:'human'}]}]});
  if(url.includes('/inputs/'))return Response.json({localHistory:[{stable:true}]});
  if(url.endsWith('/root')){rootReads++;return Response.json({...ready,lastStable:{documentRef:{blueId:rootVersion}}});}
  return Response.json({...ready,sessionId:'payment-session'});
 };
 const client=new LessonClient(()=>{}),prepared=await client.prepare(run,sourceLesson);
 assert.deepEqual(prepared.step.request,{alias:'agreement',document:{blueId:'root-before'}});
 assert.deepEqual(sourceLesson.steps[0].request,{alias:'agreement'},'the versioned lesson remains immutable');
 await assert.rejects(client.runStep(run,sourceLesson,()=>{},prepared),error=>error.cause?.message==='Response interrupted after submission');
 assert.deepEqual(run.pending.payload.message.request,prepared.step.request);
 rootVersion='root-after';fail=false;
 await client.runStep(run,sourceLesson,()=>{});
 assert.equal(rootReads,1);assert.equal(posts,2);assert.equal(keys[0],keys[1]);
 assert.deepEqual(payloads[0],payloads[1]);assert.equal(run.step,1);
});

test('a source-binding walkthrough rejects unsupported source reference metadata before intent',async()=>{
 const run={...makeRun(),root:'root'},sourceLesson={steps:[{operation:'approve',channel:'owner',requestDocument:'unknown',request:{}}]};
 globalThis.fetch=async(url,init)=>{
  assert.notEqual(init.method,'POST');
  if(url.includes('/operations'))return Response.json({items:[{name:'approve',routes:[{channel:'owner',timeline:'timeline',actor:'human'}]}]});
  return Response.json(ready);
 };
 const client=new LessonClient(()=>{});
 await assert.rejects(client.prepare(run,sourceLesson),/unsupported document-reference/);
 assert.equal(run.pending,undefined);
});

test('unsupported new defaults use the common advertised canonical selection and preserve its request on retry',async()=>{
 const canonical={...runtimes.accessEvents,canonicalWireMeasurementV1:runtimes.finalGasCanonical.canonicalWireMeasurementV1};
 const run={...makeRun(),identities:{owner:'workspace',peer:'peer'}};
 const bodies=[],keys=[];let fail=true;
 globalThis.fetch=async(url,init)=>{
  const account=new Headers(init.headers).get('X-MyOS-Account');
  if(url.endsWith('/account'))return Response.json({accountId:account,userId:'human',membership:{status:'ACTIVE'},profile:{displayName:'Peer'}});
  if(url.endsWith('/info'))return Response.json({accountId:account,runtime:{newDefault:true},registrationExecutionSelections:[{unknown:true},canonical]});
  if(init.method==='POST'){
   bodies.push(JSON.parse(init.body));keys.push(new Headers(init.headers).get('Idempotency-Key'));
   if(fail)throw Error('Response lost');
   return Response.json(admission({sessionId:'root'}));
  }
  return Response.json(ready);
 };
 await assert.rejects(new LessonClient(()=>{}).start(run,lesson));
 assert.equal(bodies.length,1);assert.deepEqual(bodies[0].executionSelection,canonical);
 assert.deepEqual(bodies[0].packages,accessPackages);
 fail=false;
 await new LessonClient(()=>{}).start(JSON.parse(JSON.stringify(run)),lesson);
 assert.deepEqual(bodies[1],bodies[0]);assert.equal(keys[1],keys[0]);
});

test('a choice unavailable to a mapped participant is rejected before submission',async()=>{
 const canonical={...runtimes.accessEvents,canonicalWireMeasurementV1:runtimes.finalGasCanonical.canonicalWireMeasurementV1};
 const run={...makeRun(),identities:{owner:'workspace',peer:'peer'}};let posts=0;
 globalThis.fetch=async(url,init)=>{
  const account=new Headers(init.headers).get('X-MyOS-Account');
  if(init.method==='POST'){posts++;throw Error('Unexpected submit');}
  if(url.endsWith('/account'))return Response.json({accountId:account,userId:'human',membership:{status:'ACTIVE'},profile:{displayName:'Peer'}});
  return Response.json({accountId:account,runtime:{newDefault:true},registrationExecutionSelections:account==='workspace'?[canonical]:[]});
 };
 await assert.rejects(new LessonClient(()=>{}).start(run,lesson),/supported|different process package editions/);
 assert.equal(posts,0);assert.equal(run.pending,undefined);
});

test('an uncertain legacy request cannot acquire an explicit selection even in the same package family',async()=>{
 const run={...makeRun(),pending:{kind:'bootstrap',key:'legacy-key',accountId:'workspace',startedAt:new Date().toISOString()}};
 globalThis.fetch=async(url,init)=>{
  assert.notEqual(init.method,'POST');
  return Response.json({accountId:'workspace',runtime:{newDefault:true},registrationExecutionSelections:[runtimes.finalGasCanonical]});
 };
 await assert.rejects(new LessonClient(()=>{}).start(run,lesson),/earlier package edition/);
 assert.equal(run.pending.key,'legacy-key');assert.equal(run.selection,undefined);
});

test('losing an advertised selection on retry never silently switches back to a matching default',async()=>{
 const canonical={...runtimes.accessEvents,canonicalWireMeasurementV1:runtimes.finalGasCanonical.canonicalWireMeasurementV1};
 const run=makeRun();let posts=0;
 globalThis.fetch=async(url,init)=>{
  if(url.endsWith('/info'))return Response.json({accountId:'workspace',runtime:{newDefault:true},registrationExecutionSelections:[canonical]});
  posts++;throw Error('Lost response');
 };
 await assert.rejects(new LessonClient(()=>{}).start(run,lesson));
 assert.equal(posts,1);const key=run.pending.key;
 globalThis.fetch=async(url,init)=>{
  assert.notEqual(init.method,'POST');
  return Response.json({accountId:'workspace',runtime:canonical,registrationExecutionSelections:[]});
 };
 await assert.rejects(new LessonClient(()=>{}).start(run,lesson),/saved execution selection/);
 assert.equal(run.pending.key,key);assert.deepEqual(run.selection.executionSelection,canonical);
});
