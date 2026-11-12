// One bounded Agreement -> Order -> Payment journey using the original generated Core SDK.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {dirname} from 'node:path';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {Configuration,DocumentsApi,TimelinesApi,ResponseError} from '../sdk/typescript/dist/index.js';

const [origin,agreement,output='reports/core-timeline-journey.json']=process.argv.slice(2);
const destination=new URL(origin);
assert.equal(destination.protocol,'https:');assert.equal(destination.origin,origin);
assert.match(agreement,/^graph-[a-f0-9]+$/);
const headers={Origin:origin};
if(process.env.MYOS_ACCOUNT)headers['X-MyOS-Account']=process.env.MYOS_ACCOUNT;
if(process.env.MYOS_API_KEY)headers.Authorization='Bearer '+process.env.MYOS_API_KEY;
else {
 assert.ok(process.env.MYOS_AUTH_STATE,'Set MYOS_API_KEY, or an operator-owned MYOS_AUTH_STATE file.');
 const state=JSON.parse(await readFile(process.env.MYOS_AUTH_STATE,'utf8'));
 const cookies=state.cookies.filter(c=>c.domain.replace(/^\./,'')===destination.hostname&&c.secure&&c.expires>Date.now()/1000);
 assert.ok(cookies.length,'No unexpired session for this exact origin.');
 headers.Cookie=cookies.map(c=>c.name+'='+c.value).join('; ');
}
const configuration=new Configuration({basePath:origin,headers,fetchApi:(url,init)=>fetch(url,{...init,redirect:'error',signal:AbortSignal.timeout(30000)})});
const documents=new DocumentsApi(configuration),timelines=new TimelinesApi(configuration);
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function legacy(path){
 const response=await fetch(origin+'/api/'+path,{headers,redirect:'error',signal:AbortSignal.timeout(30000)});
 assert.equal(response.status,200,'Legacy telemetry read failed: '+path);
 return response.json();
}
const value=async(id,path)=>(await legacy('documents/'+id+'/value?path='+encodeURIComponent(path))).value;
const child=async(id,path)=>(await legacy('documents/'+id+'/embedding?path='+encodeURIComponent(path))).document;
async function children(id){
 const items=[];let cursor,version;
 do {
  const page=await legacy('documents/'+id+'/children'+(cursor?'?cursor='+encodeURIComponent(cursor):''));
  if(version!==undefined)assert.equal(page.version,version,'Graph changed while counting children');
  version=page.version;items.push(...page.items);
  if(page.nextCursor!=null)assert.notEqual(page.nextCursor,cursor,'Child pagination did not advance');cursor=page.nextCursor;
 } while(cursor);
 return items;
}
const report={origin,agreement,agreementUrl:origin+'/#documents/'+agreement,calls:[]};
await mkdir(dirname(output),{recursive:true});
const save=()=>writeFile(output,JSON.stringify(report,null,2)+'\n');
async function invoke(id,channel,name,request){
 const lookup=performance.now(),doc=await documents.getDocument({sessionId:id});
 const declared=(await documents.getDocumentOperations({sessionId:id})).items.find(x=>x.name===name);
 assert.ok(declared,'Missing operation '+name);
 const route=declared.routes.find(x=>x.channel===channel);assert.ok(route,'Missing operation route');
 const preparation=performance.now();
 const proposed=await documents.prepareDocumentOperationRaw({sessionId:id,prepareDocumentOperation:{
  operationName:name,reviewedPublication:{documentRef:doc.lastStable.documentRef,epoch:doc.lastStable.epoch},route,requestJson:JSON.stringify(request)}});
 const prepared=await proposed.value();assert.equal(prepared.validation.status,'valid');
 const preparationMillis=performance.now()-preparation;
 let before=(await legacy('documents/'+agreement+'/head')).version;
 const started=performance.now(),command=randomUUID();
 report.pending={document:id,operation:name,command,beforeVersion:before};await save();
 const submitted=await timelines.appendEntryRaw({timelineId:route.timeline,idempotencyKey:command,appendEntry:{
  documentId:doc.documentId,actor:route.actor,inputMode:'source',message:prepared.messageJson}});
 const accepted=await submitted.value();assert.equal(accepted.status,'SUCCEEDED');
 const submitMillis=performance.now()-started,entry=accepted.details.entryRef.blueId;
 report.pending.entry=entry;await save();
 const deadline=performance.now()+45000;
 while(performance.now()<deadline){
  const head=await legacy('documents/'+agreement+'/head');
  for(let version=before+1;version<=head.version;version++){
   const history=await legacy('documents/'+agreement+'/history/'+version);
   if(history.input?.entry!==entry)continue;
   assert.equal(history.outcome.status,'APPLIED');
   const row={operation:name,document:id,entry,version,engineMillis:history.outcome.executionNanos/1e6,
    preparationMillis,prepareServerTiming:proposed.raw.headers.get('Server-Timing'),submitMillis,
    submitServerTiming:submitted.raw.headers.get('Server-Timing'),serverPerformance:history.performance||{},
    observedTotalMillis:performance.now()-started,observedWithLookupMillis:performance.now()-lookup};
   report.calls.push(row);delete report.pending;await save();console.log(JSON.stringify(row));return;
  }
  before=head.version;await sleep(25);
 }
 throw new Error('Accepted entry did not commit within 45 seconds; inspect the saved pending entry before retrying.');
}
try {
 report.ordersBefore=(await children(agreement)).filter(x=>x.path.startsWith('/orders/')).length;
 report.paidBefore=await value(agreement,'/paidTotal');
 const key='core-timeline-'+randomUUID();report.orderKey=key;await save();
 await invoke(agreement,'customer','placeOrder',{orderId:key});
 const order=await child(agreement,'/orders/'+key);report.order=order;await save();
 await invoke(order,'buyer','preparePayment',{});
 const payment=await child(order,'/payment');report.payment=payment;await save();
 await invoke(payment,'guarantor','acceptPaymentInstruction',{});
 const receipt={requestId:randomUUID(),reference:'core-timeline-reproduction'};
 await invoke(payment,'payer','reportTransfer',receipt);
 await invoke(payment,'guarantor','acknowledgeTransfer',receipt);
 assert.equal(await value(payment,'/payment/completed'),200);
 assert.equal(await value(order,'/paidTotal'),200);
 report.paidAfter=await value(agreement,'/paidTotal');assert.equal(report.paidAfter,report.paidBefore+200);
 report.ordersAfter=(await children(agreement)).filter(x=>x.path.startsWith('/orders/')).length;
 assert.equal(report.ordersAfter,report.ordersBefore+1);report.passed=true;await save();
 console.log(JSON.stringify({passed:true,output,orders:report.ordersAfter,paid:report.paidAfter}));
} catch(failure){
 report.passed=false;await save();
 if(failure instanceof ResponseError)throw new Error('Core API returned '+failure.response.status+'; inspect the saved checkpoint.');
 throw failure;
}
