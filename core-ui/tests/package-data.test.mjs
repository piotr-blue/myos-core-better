import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

const compiled = (await build({
  stdin: { contents: `import React from 'react'; import {createRoot} from 'react-dom/client';
    import {PackageProvider,usePackages} from './src/package-data';
    import {startLive,liveRevision} from './src/live';
    import {setAccountContext} from './src/account-context';
    function Probe(){const state=usePackages();window.reloadPackages=state.reload;return <><output>{JSON.stringify({catalog:state.catalog,error:state.error,readState:state.readState})}</output><button onClick={()=>state.reload()}>Reload</button></>}
    window.mount=(id='one')=>{setAccountContext({userId:'user',accountId:id,sessionContext:'guard-'+id,membership:{generation:1,revision:1}});window.reactRoot??=createRoot(document.getElementById('root'));window.reactRoot.render(<PackageProvider key={id}><Probe/></PackageProvider>)};
    window.start=()=>startLive(()=>{}); window.liveRevision=liveRevision; window.mount();`, loader: 'tsx', resolveDir: process.cwd() },
  bundle: true, write: false, format: 'iife', platform: 'browser', jsx: 'automatic', define: {'process.env.NODE_ENV':'"production"'},
})).outputFiles[0].text;
const until = async (condition, message = 'expected UI state') => {
  for(let n=0;n<150;n++){if(condition())return;await delay(5);} assert.fail(message);
};
const catalogue = name => ({items:[{id:name,name,types:[]}],available:[],supportedPackageBlueIds:[]});
const response = (name, readState='projected') => Response.json(catalogue(name), {headers:{'X-MyOS-Read-State':readState}});
async function setup(t) {
  const dom = new JSDOM('<div id="root"></div>', {url:'https://core.example.test',runScripts:'outside-only'});
  const w=dom.window, calls=[]; let stream;
  w.Headers=Headers;w.Response=Response;w.AbortController=AbortController;w.AbortSignal=AbortSignal;w.TextDecoder=TextDecoder;
  w.fetch=(path,options)=>{
    if(path.endsWith('/live'))return Promise.resolve(new Response(new ReadableStream({start(c){stream=c;}})));
    return new Promise((resolve,reject)=>calls.push({resolve,reject,options}));
  };
  w.eval(compiled);
  t.after(()=>{w.reactRoot.unmount();w.close();});
  await until(()=>calls.length===1);
  return {w,calls,state:()=>JSON.parse(w.document.querySelector('output').textContent),
    reload:()=>w.document.querySelector('button').click(),
    live:async()=>{const stop=w.start();t.after(stop);await until(()=>stream);return ()=>stream.enqueue(new TextEncoder().encode('event: change\ndata: {"reset":true}\n\n'));}};
}

test('PackageProvider reload shares its initial request and publishes INITIAL while a refresh is pending', async t=>{
  const app=await setup(t);app.reload();app.reload();
  assert.equal(app.calls.length,1,'reload must coalesce while the first request is pending');
  app.calls[0].resolve(response('old-initial','initial'));
  await until(()=>app.calls.length===2);
  await until(()=>app.state().readState==='initial');
  assert.equal(app.state().catalog.items[0].name,'old-initial','a pending refresh must not hide a useful successful response');
  app.calls[1].resolve(response('projected'));
  await until(()=>app.state().catalog?.items[0].name==='projected');
  assert.equal(app.state().readState,'projected');
});

test('SSE reset refreshes the catalogue and its initial read state even when documents stay empty',async t=>{
  const app=await setup(t);app.calls[0].resolve(response('saved-manifest','initial'));
  await until(()=>app.state().catalog);
  assert.equal(app.state().readState,'initial');
  const reset=await app.live();reset();
  await until(()=>app.calls.length===2,'reset must reload packages');
  app.calls[1].resolve(response('real-projection'));
  await until(()=>app.state().readState==='projected');
  assert.equal(app.state().catalog.items[0].name,'real-projection');
});

test('a failed invalidated request still drains the pending reload without publishing its error',async t=>{
  const app=await setup(t);app.reload();
  app.calls[0].resolve(Response.json({detail:'obsolete rejection'},{status:400}));
  await until(()=>app.calls.length===2);
  assert.equal(app.state().error,'');
  app.calls[1].resolve(response('recovered'));
  await until(()=>app.state().catalog?.items[0].name==='recovered');
});

test('a replacement account keeps its catalogue when an old account response or error arrives',async t=>{
  for(const oldFailure of [false,true]){
    const app=await setup(t);app.w.mount('two');await until(()=>app.calls.length===2);
    app.calls[1].resolve(response('account-two'));
    await until(()=>app.state().catalog?.items[0].name==='account-two');
    app.calls[0].resolve(oldFailure?Response.json({detail:'old account failure'},{status:400}):response('account-one','initial'));
    await delay(10);
    assert.equal(app.state().catalog.items[0].name,'account-two');assert.equal(app.state().error,'');assert.equal(app.state().readState,'projected');
  }
});

// Keep the real provider and React lifecycle; control only the API boundary so
// request settlement and the following reload occupy adjacent microtasks.
const boundaryCompiled = (await build({
  stdin: { contents: `import React from 'react'; import {createRoot} from 'react-dom/client';
    import {PackageProvider,usePackages} from './src/package-data';
    function Probe(){const state=usePackages();window.reloadPackages=state.reload;return <output>{JSON.stringify({catalog:state.catalog,error:state.error,readState:state.readState})}</output>}
    window.reactRoot=createRoot(document.getElementById('root'));window.reactRoot.render(<PackageProvider><Probe/></PackageProvider>);`,
    loader: 'tsx', resolveDir: process.cwd() },
  plugins: [{name:'controlled-package-read',setup(builder){
    builder.onResolve({filter:/^\.\/api$/}, args => args.importer.endsWith('/package-data.tsx') ? {path:'package-read',namespace:'fixture'} : undefined);
    builder.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:'export const apiResponse = (...args) => window.readPackages(...args);',loader:'js'}));
  }}],
  bundle:true,write:false,format:'iife',platform:'browser',jsx:'automatic',define:{'process.env.NODE_ENV':'"production"'},
})).outputFiles[0].text;

test('a reload in the request settlement microtask still reads PROJECTED after INITIAL', async t => {
  const dom = new JSDOM('<div id="root"></div>', {url:'https://core.example.test',runScripts:'outside-only'});
  const w=dom.window, calls=[];
  // A cross-realm Promise adds assimilation microtasks and masks this boundary.
  w.readPackages=(path,options)=>new w.Promise((resolve,reject)=>calls.push({path,options,resolve,reject}));
  w.eval(boundaryCompiled);
  t.after(()=>{w.reactRoot.unmount();w.close();});
  await until(()=>calls.length===1);
  calls[0].resolve({data:catalogue('initial'),readState:'initial'});
  queueMicrotask(()=>w.reloadPackages());
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(calls.length,2,'a reload after request settlement must not be lost');
  calls[1].resolve({data:catalogue('projected'),readState:'projected'});
  await until(()=>JSON.parse(w.document.querySelector('output').textContent).readState==='projected');
  assert.equal(JSON.parse(w.document.querySelector('output').textContent).catalog.items[0].name,'projected');
});


test('successful PROJECTED responses advance the catalogue during repeated SSE resets', async t => {
  const app=await setup(t);
  app.calls[0].resolve(response('saved-initial','initial'));
  await until(()=>app.state().readState==='initial');
  const reset=await app.live();
  app.reload();
  await until(()=>app.calls.length===2);
  // Four responses model sustained invalidation, rather than one coincident reset.
  for(let attempt=1;attempt<=4;attempt++){
    const revision=app.w.liveRevision();
    reset();
    await until(()=>app.w.liveRevision()>revision,'the reset must be consumed before the response');
    app.calls[attempt].resolve(response(`projected-${attempt}`));
    await until(()=>app.calls.length===attempt+2,'invalidation must schedule the next read');
  }
  assert.equal(app.state().readState,'projected','successful current-account reads must not starve behind repeated resets');
  assert.equal(app.state().catalog.items[0].name,'projected-4');
});


test('a later INITIAL response cannot replace PROJECTED but a new account can begin INITIAL', async t => {
  const app=await setup(t);
  app.calls[0].resolve(response('first-account-projected'));
  await until(()=>app.state().readState==='projected');
  const reload=app.w.reloadPackages();
  app.calls[1].resolve(response('late-initial','initial'));
  await reload;
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(app.state().readState,'projected');
  assert.equal(app.state().catalog.items[0].name,'first-account-projected');
  const obsolete=app.w.reloadPackages();
  app.w.mount('two');
  await until(()=>app.calls.length===4);
  assert.equal(app.calls[2].options.signal.aborted,true,'account replacement aborts the pending read');
  app.calls[3].resolve(response('second-account-initial','initial'));
  app.calls[2].resolve(response('old-account-projected'));
  await obsolete;
  await until(()=>app.state().readState==='initial');
  assert.equal(app.state().catalog.items[0].name,'second-account-initial');
});
