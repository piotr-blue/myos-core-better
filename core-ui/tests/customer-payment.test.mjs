import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { readFile } from 'node:fs/promises';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';
const profile = JSON.parse(await readFile('src/process-profile.json', 'utf8'));
const compiled = (await build({ stdin: { contents: `import React from 'react'; import {createRoot} from 'react-dom/client'; import {CustomerPayment} from './src/customer-payment'; createRoot(document.getElementById('root')).render(<CustomerPayment id="p" actionId="action" navigate={() => {}}/>);`, resolveDir: process.cwd(), loader: 'tsx' }, bundle: true, write: false, format: 'iife', platform: 'browser', jsx: 'automatic', define: { 'process.env.NODE_ENV': '"production"' } })).outputFiles[0].text;
const json = (body, status=200) => new Response(JSON.stringify(body), { status });
const typed = value => Array.isArray(value) ? value.map(typed) : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key,item]) => [key, key === 'type' ? item : typed(item)])) : { type: { blueId: typeof value }, value };
const initial = () => ({ name: 'Inspection payment', type: { blueId: profile.customerPaymentTypes[0] }, paymentTerms: { payerChannel: 'payer', payeeChannel: 'merchant', guarantorChannel: 'provider', amountMinor: 200, currency: 'USD' }, activation: { active: true }, payment: { secured: 0, completed: 0, reversed: 0, requests: {} }, customerSession: { id: 'action', expiresAt: (Date.now()+60000)*1000, requested: false, funded: false } });
async function until(predicate) { for(let i=0;i<200&&!predicate();i++)await delay(5); assert.ok(predicate()); }
function page(t, { state=initial(), operations=[], timelines=[{timelineId:'payer-timeline',owned:true}] }={}) {
  const dom=new JSDOM('<div id="root"></div>',{url:'http://localhost/',runScripts:'outside-only',pretendToBeVisual:true});
  t.after(()=>dom.window.close());const {window}=dom,calls=[];
  Object.assign(window,{Headers,Response,Request,AbortController,AbortSignal,TextDecoder});
  window.fetch=async (path,options={})=>{
    calls.push({path,options});
    if(path.startsWith('/api/blue/v1/timelines?'))return json({items:timelines,nextPageToken:null});
    if(path==='/api/blue/v1/documents/p')return json({sessionId:'p',lastStable:{documentRef:{blueId:'state'}}});
    if(path==='/api/blue/v1/contents/state')return json(typed(state));
    if(path.startsWith('/api/blue/v1/documents/p/operations')&&options.method==='GET')return json({items:operations,nextPageToken:null});
    if(path==='/api/blue/v1/documents/p/operations'&&options.method==='POST')return json({kind:'OPERATION',admissionId:'a',status:'SUCCEEDED',details:{entryRef:{blueId:'e'}}},202);
    if(path==='/api/blue/v1/documents/p/inputs/e')return json({sessionId:'p',entryBlueId:'e',assessment:null,disposition:null,localHistory:[{stable:true,document:'state',position:'pos',receipt:'receipt'}],processing:{state:'READY'}});
    throw new Error('Unexpected request '+path);
  };
  window.eval(compiled);return {document:window.document,calls};
}
const payer=[{name:'requestCustomerFunding',routes:[{channel:'payer',timeline:'payer-timeline',actor:'payer-actor'}]}];
test('inspection-only accounts get no payment action',async t=>{
  const app=page(t,{operations:payer,timelines:[{timelineId:'payer-timeline',owned:false}]});await until(()=>app.document.body.textContent.includes('no payer operation'));
  assert.equal([...app.document.querySelectorAll('button')].some(b=>b.textContent==='Continue to funding'),false);
});
test('wrong action identity cannot expose a funding button or stale details',async t=>{
  const state=initial();state.customerSession.id='other';const app=page(t,{state,operations:payer});
  await until(()=>app.document.querySelector('[role="alert"]'));
  assert.equal(app.document.body.textContent.includes('Inspection payment'),false);
  assert.equal(app.document.body.textContent.includes('Continue to funding'),false);
});
test('expired valid action explains replacement without sending an operation',async t=>{
  const state=initial();state.customerSession.expiresAt=1;const app=page(t,{state,operations:payer});
  await until(()=>app.document.body.textContent.includes('has expired'));
  assert.equal(app.document.body.textContent.includes('Continue to funding'),false);
  assert.equal(app.calls.some(c=>c.options.method==='POST'),false);
});
test('funding submits the reviewed payer request and cannot invent provider completion',async t=>{
  const app=page(t,{operations:payer});await until(()=>[...app.document.querySelectorAll('button')].some(b=>b.textContent==='Continue to funding'));
  [...app.document.querySelectorAll('button')].find(b=>b.textContent==='Continue to funding').click();
  await until(()=>app.document.body.textContent.includes('instruction processed your action'));
  const sent=app.calls.filter(c=>c.options.method==='POST');assert.equal(sent.length,1);
  const body=JSON.parse(sent[0].options.body);assert.equal(body.message.operation,'requestCustomerFunding');
  assert.deepEqual(body.message.request,{sessionId:'action'});assert.equal(body.message.requireExactDocumentVersion,true);
  assert.equal(body.message.document.blueId,'state');assert.ok(sent[0].options.headers.get('Idempotency-Key'));
  assert.equal(app.document.body.textContent.includes('No real money moves'),true);
  const completed=[...app.document.querySelectorAll('dt')].find(n=>n.textContent==='Completed (minor units)');
  assert.equal(completed.nextElementSibling.textContent,'0');
});

test('an owned host timeline with a different actor does not grant a payer route',async t=>{
  const app=page(t,{operations:payer,timelines:[{timelineId:'host-id',blueId:'payer-timeline',actors:['another-actor']}]});
  await until(()=>app.document.body.textContent.includes('no payer operation'));
  assert.equal(app.document.body.textContent.includes('Continue to funding'),false);
});
