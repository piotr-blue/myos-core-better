import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

const compiled = (await build({
  stdin: { contents: `import React from 'react'; import {createRoot} from 'react-dom/client'; import {AccountCreationForm} from '@blue-labs/account-ui'; window.mount=()=>{window.root=createRoot(document.getElementById('root'));window.root.render(<AccountCreationForm {...window.props}/>)};window.mount();`, loader: 'tsx', resolveDir: process.cwd() },
  loader: { '.css': 'empty' }, bundle: true, write: false, format: 'iife', platform: 'browser', jsx: 'automatic', define: { 'process.env.NODE_ENV': '"production"' },
})).outputFiles[0].text;
async function until(condition) { for (let i=0;i<100;i++){if(condition())return;await delay(10);}assert.fail('UI did not reach the expected state'); }
async function setup(t, props) {
  const dom = new JSDOM('<div id="root"></div>', { url: 'https://core.example.test', runScripts: 'dangerously' });
  t.after(()=>dom.window.close());
  const w=dom.window;Object.defineProperty(w,'crypto',{value:webcrypto});
  w.URL.createObjectURL=()=> 'blob:fixture';w.URL.revokeObjectURL=()=>{};
  w.props={scope:'user:session',...props};w.eval(compiled);
  await until(()=>w.document.querySelector('form'));
  const input=(selector,value)=>{const el=w.document.querySelector(selector);Object.getOwnPropertyDescriptor(w.HTMLInputElement.prototype,'value').set.call(el,value);el.dispatchEvent(new w.Event('input',{bubbles:true}));};
  const file=()=>{const el=w.document.querySelector('input[type=file]');const value=new w.File(['fixture'],'logo.png',{type:'image/png'});value.arrayBuffer=async()=>new TextEncoder().encode('fixture').buffer;Object.defineProperty(el,'files',{configurable:true,value:[value]});el.dispatchEvent(new w.Event('change',{bubbles:true}));};
  const submit=()=>w.document.querySelector('form').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
  return {w,input,file,submit};
}
test('creation includes website and retries only the logo with its original request, including after remount',async t=>{
  const creates=[],uploads=[],completed=[];let fail=true;
  const app=await setup(t,{create:async(input,key)=>{creates.push({input,key});return {accountId:'company',membershipGeneration:1};},upload:async(account,file,key)=>{uploads.push({account,key});if(fail)throw new Error('Lost upload response');},complete:result=>completed.push(result)});
  app.input('input[required]','Studio');app.input('input[type=url]','https://studio.example.test');app.file();await delay(20);app.submit();
  await until(()=>app.w.document.querySelector('[role=alert]')?.textContent.includes('Account created'));
  assert.equal(creates.length,1);assert.equal(creates[0].input.profile.website,'https://studio.example.test');
  app.w.root.unmount();app.w.mount();await until(()=>app.w.document.querySelector('input[type=file]'));
  app.file();await delay(20);fail=false;app.submit();await until(()=>completed.length===1);
  assert.equal(creates.length,1);assert.equal(uploads.length,2);assert.equal(uploads[0].key,uploads[1].key);
  assert.equal(completed[0].accountId,'company');assert.equal(app.w.sessionStorage.length,0);
});
test('an uncertain account creation freezes its input and reuses the same idempotency key',async t=>{
  const calls=[],completed=[];
  const app=await setup(t,{create:async(input,key)=>{calls.push({input,key});if(calls.length===1)throw new Error('Lost create response');return {accountId:'once',membershipGeneration:1};},upload:async()=>assert.fail('No file selected'),complete:r=>completed.push(r)});
  app.input('input[required]','One account');await delay(20);app.submit();await until(()=>app.w.document.querySelector('[role=alert]'));
  assert.equal(app.w.document.querySelector('input[required]').disabled,true);
  app.submit();await until(()=>completed.length===1);assert.equal(calls[0].key,calls[1].key);assert.deepEqual(calls[0].input,calls[1].input);
});
test('continuing after failed logo upload opens the existing account without another creation',async t=>{
  let created=0;const completed=[];
  const app=await setup(t,{create:async()=>{created++;return {accountId:'retained',membershipGeneration:1};},upload:async()=>{throw new Error('Storage unavailable');},complete:r=>completed.push(r)});
  app.input('input[required]','Retained');app.file();await delay(20);app.submit();
  await until(()=>app.w.document.querySelector('[role=alert]'));
  [...app.w.document.querySelectorAll('button')].find(x=>x.textContent==='Continue to account').click();
  assert.equal(created,1);assert.equal(completed[0].accountId,'retained');
});

test('a definite validation rejection unlocks fields and creates a new corrected intent', async t => {
  const calls=[],completed=[];
  const app=await setup(t,{create:async(input,key)=>{calls.push({input,key});if(calls.length===1)throw Object.assign(new Error('Invalid website'),{status:422});return {accountId:'corrected',membershipGeneration:1};},upload:async()=>assert.fail('No file'),complete:r=>completed.push(r)});
  app.input('input[required]','Invalid');await delay(20);app.submit();await until(()=>app.w.document.querySelector('[role=alert]'));
  assert.equal(app.w.document.querySelector('input[required]').disabled,false);
  app.input('input[required]','Corrected');await delay(20);app.submit();await until(()=>completed.length===1);
  assert.notEqual(calls[0].key,calls[1].key);assert.equal(calls[1].input.profile.displayName,'Corrected');
});

test('switching to Personal clears a selected business logo and never uploads it', async t => {
  const created=[],completed=[];
  const app=await setup(t,{create:async input=>{created.push(input);return {accountId:'personal',membershipGeneration:1};},upload:async()=>assert.fail('Personal accounts have no logo'),complete:r=>completed.push(r)});
  app.input('input[required]','Personal account');app.file();await delay(20);
  [...app.w.document.querySelectorAll('input[type=radio]')][1].click();
  await until(()=>!app.w.document.querySelector('input[type=file]'));
  app.submit();await until(()=>completed.length===1);
  assert.equal(created[0].kind,'PERSONAL');
});

test('pending creation waits for readiness and preserves its request through a transient poll failure', async t => {
  const calls=[],uploads=[],completed=[];let release=false;
  const app=await setup(t,{create:async(input,key)=>{
    calls.push({input,key});
    if(calls.length===2)throw Object.assign(new Error('Temporary storage error'),{status:503});
    return {accountId:'pending',membershipGeneration:1,provisioning:release?'READY':'PENDING'};
  },upload:async()=>uploads.push('image'),complete:r=>completed.push(r)});
  app.input('input[required]','Pending company');app.file();await delay(20);app.submit();
  await until(()=>calls.length>=2);
  assert.equal(completed.length,0);assert.equal(uploads.length,0);
  assert.match(app.w.document.body.textContent,/saved|preparing/i);
  assert.equal([...app.w.document.querySelectorAll('button')].some(x=>x.textContent==='Continue to account'),false);
  release=true;await until(()=>completed.length===1);
  assert.equal(uploads.length,1);assert.ok(calls.length>=3);
  assert.equal(new Set(calls.map(x=>x.key)).size,1);
  assert.ok(calls.every(x=>JSON.stringify(x.input)===JSON.stringify(calls[0].input)));
  assert.equal(app.w.sessionStorage.length,0);
});

test('reload resumes a saved pending intent without its image and waits before allowing continuation',async t=>{
  const calls=[],uploads=[],completed=[];let ready=false;
  const app=await setup(t,{create:async(input,key)=>{calls.push({input,key});return {accountId:'reload',membershipGeneration:1,provisioning:ready?'READY':'PENDING'};},upload:async()=>uploads.push('image'),complete:r=>completed.push(r)});
  app.input('input[required]','Reload pending');app.file();await delay(20);app.submit();await until(()=>calls.length===1);
  app.w.root.unmount();app.w.mount();await until(()=>calls.length>=2);
  assert.equal(completed.length,0);assert.equal(uploads.length,0);
  ready=true;await until(()=>app.w.document.querySelector('[role=alert]')?.textContent.includes('Reselect'));
  assert.equal(new Set(calls.map(x=>x.key)).size,1);
  [...app.w.document.querySelectorAll('button')].find(x=>x.textContent==='Continue to account').click();
  assert.equal(completed.length,1);assert.equal(uploads.length,0);
});

test('reload recovers an uncertain creation automatically with the retained key',async t=>{
  const calls=[],completed=[];
  const app=await setup(t,{create:async(input,key)=>{calls.push({input,key});if(calls.length===1)throw new Error('Lost create response');return {accountId:'recovered',membershipGeneration:1,provisioning:'READY'};},upload:async()=>assert.fail('No image'),complete:r=>completed.push(r)});
  app.input('input[required]','Uncertain');await delay(20);app.submit();await until(()=>app.w.document.querySelector('[role=alert]'));
  app.w.root.unmount();app.w.mount();await until(()=>completed.length===1);
  assert.equal(calls.length,2);assert.equal(calls[0].key,calls[1].key);
});

test('terminal polling errors retain saved state and stop automatic retries',async t=>{
  let calls=0;const completed=[];
  const app=await setup(t,{create:async()=>{calls++;if(calls===1)return {accountId:'pending-auth',membershipGeneration:1,provisioning:'PENDING'};throw Object.assign(new app.w.Error('Sign in again'),{status:401});},upload:async()=>assert.fail('No image'),complete:r=>completed.push(r)});
  app.input('input[required]','Session ended');await delay(20);app.submit();
  await until(()=>app.w.document.querySelector('[role=alert]')?.textContent.includes('Sign in again'));
  await delay(600);assert.equal(calls,2);assert.equal(completed.length,0);
  assert.match(app.w.document.body.textContent,/saved|preparing/i);
});

test('an unmounted pending form stops checks and cannot complete a late response',async t=>{
  let release;let calls=0;const completed=[];
  const app=await setup(t,{create:async()=>{calls++;if(calls===1)return {accountId:'unmounted',membershipGeneration:1,provisioning:'PENDING'};return new Promise(resolve=>{release=resolve;});},upload:async()=>assert.fail('No image'),complete:r=>completed.push(r)});
  app.input('input[required]','Leaving');await delay(20);app.submit();app.submit();await until(()=>calls===2);
  app.w.root.unmount();release({accountId:'unmounted',membershipGeneration:1,provisioning:'READY'});
  await delay(600);assert.equal(calls,2);assert.equal(completed.length,0);
  assert.equal(app.w.sessionStorage.length,1);
});
