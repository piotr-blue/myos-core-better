import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname } from 'node:path';
import { build } from 'esbuild';
import { parse } from 'yaml';

const require = createRequire(import.meta.url);
const compiled = (await build({
  entryPoints: ['src/main.tsx'],
  bundle: true,
  write: false,
  format: 'esm',
  loader: { '.css': 'empty' },
  plugins: [{
    name: 'operation-form-harness',
    setup(build) {
      // Expose the real form without changing the production entry point.
      build.onLoad({ filter: /\/main\.tsx$/ }, async ({ path }) => ({
        contents: `${await readFile(path, 'utf8')}\nexport { Operation, EventsPage, Raw, App }; export { EpochEvents, EventContent } from "./epoch-events"; export { EntryCard, DocumentDetail } from "./resource-pages";`,
        loader: 'tsx',
        resolveDir: dirname(path),
      }));
      build.onLoad({ filter: /\/resource-pages\.tsx$/ }, async ({ path }) => ({
        contents: `${await readFile(path, 'utf8')}\nexport { EntryCard };`,
        loader: 'tsx', resolveDir: dirname(path),
      }));
      build.onLoad({ filter: /\/epoch-events\.tsx$/ }, async ({ path }) => ({
        contents: `${await readFile(path, 'utf8')}\nexport { EventContent };`,
        loader: 'tsx', resolveDir: dirname(path),
      }));
      build.onResolve({ filter: /^react$/ }, () => ({ path: 'react-hooks', namespace: 'form-test' }));
      build.onResolve({ filter: /^react-dom\/client$/ }, () => ({ path: 'mount', namespace: 'form-test' }));
      build.onLoad({ filter: /.*/, namespace: 'form-test' }, ({ path }) => ({
        contents: path === 'mount'
          ? 'export const createRoot = () => ({ render() {} });'
          : `export * from ${JSON.stringify(require.resolve('react'))};
             export { default } from ${JSON.stringify(require.resolve('react'))};
             export const useContext = context => context._currentValue;
             export const useCallback = callback => callback; export const useRef = value => ({current:value});
             export const useEffect = callback => globalThis.operationEffects?.push(callback);
             export const useState = initial => globalThis.operationFormState.useState(initial);`,
        resolveDir: process.cwd(),
      }));
    },
  }],
})).outputFiles[0].text;
globalThis.location = {hash:'',origin:'https://console.example.test'};
globalThis.document = { getElementById: () => ({}) };
globalThis.localStorage = { getItem: () => null };
globalThis.sessionStorage = { getItem: () => null, setItem() {}, removeItem() {} };
const { Operation, decodeRouteSegment, EpochEvents, EventContent, EntryCard, DocumentDetail, EventsPage, Raw, App } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);

function findElement(element, type) {
  if (!element || typeof element !== 'object') return undefined;
  if (element.type === type) return element;
  const children = [element.props?.children].flat(Infinity);
  return children.map(child => findElement(child, type)).find(Boolean);
}

for (const [name, source] of [
  ['large integer', '{"value": 9007199254740993}'],
  ['precise decimal', 'value: 0.12345678901234567890123456789'],
  ['quoted root boolean', '"true"'],
  ['quoted root number', '"42"'],
]) {
  test(`operation form preserves ${name} source through the write transport`, async () => {
    const state = [];
    let cursor = 0;
    globalThis.operationFormState = {
      useState(initial) {
        const index = cursor++;
        if (!(index in state)) state[index] = initial;
        return [state[index], value => { state[index] = value; }];
      },
    };
    const writes = [];
    globalThis.fetch = async (path, options = {}) => {
      if (options.method === 'POST') {
        writes.push({ path, body: parse(options.body, {intAsBigInt:true}), raw: options.body });
        return Response.json({ admissionId: 'admission', status: 'SUCCEEDED', details: { entryRef: { blueId: 'entry' } } });
      }
      assert.equal(path, '/api/blue/v1/documents/document/inputs/entry');
      return Response.json({ localHistory: [{}] });
    };
    let changes = 0;
    const props = {
      op: { name: 'update', channel: 'notes', routes: [{ channel: 'notes', timeline: 'timeline', actor: 'actor' }] },
      id: 'document', documentId: 'native-document', versionId: 'reviewed-document',
      onChange: () => { changes++; },
    };
    const render = () => { cursor = 0; return Operation(props); };
    findElement(render(), 'textarea').props.onChange({ target: { value: source } });
    await render().props.onSubmit({ preventDefault() {} });

    assert.equal(writes.length, 1);
    assert.equal(writes[0].path, '/api/blue/v1/documents/document/operations');
    assert.deepEqual(writes[0].body, {
      timeline: {blueId:'timeline'}, actor:'actor', inputMode:'source',
      message: {type:'Contracts/Operation Request', document:{blueId:'reviewed-document'}, requireExactDocumentVersion:true,
        channel:'notes', operation:'update', request:parse(source,{intAsBigInt:true})},
    });
    if (name === 'precise decimal') assert.match(writes[0].raw,/0\.12345678901234567890123456789/);
    assert.equal(changes, 2);
    assert.equal(findElement(render(), 'button').props.disabled, false);
  });
}


test('hash routing decodes exactly once and tolerates malformed user URLs', () => {
  assert.equal(decodeRouteSegment('cyclic%230'), 'cyclic#0');
  assert.equal(decodeRouteSegment('literal%2523name'), 'literal%23name');
  assert.equal(decodeRouteSegment('invalid%'), 'invalid%');
});


test('epoch events resolve the immutable event reference and retain its receipt identity',()=>{
  const data={sessionId:'session',epoch:1,kind:'APPLY',triggeredBy:[{blueId:'input'}],emitted:[{eventRef:{blueId:'output#0'},receiptRef:{blueId:'receipt'}}]};
  let cursor=0;globalThis.operationFormState={useState:()=>[cursor++===0?data:'',()=>{}]};
  const rendered=EpochEvents({id:'session',epoch:1});
  const sections=rendered.props.children;
  const emitted=sections[1].props.children[2][0];
  assert.equal(emitted.props.blueId,'output#0');
  assert.deepEqual(emitted.props.receiptRef,{blueId:'receipt'});
  assert.equal(sections[0].props.children[2][0].props.blueId,'input');
});


test('timeline entry card reads the canonical immutable message reference',()=>{
  let cursor=0;globalThis.operationFormState={useState:initial=>[initial,()=>{}]};
  globalThis.operationEffects=[];
  const paths=[];globalThis.fetch=async path=>{paths.push(path);return Response.json({name:'Message'});};
  const entry={blueId:'entry#0',entry:{message:{blueId:'message#1'},timestamp:1}};
  const card=EntryCard({entry,messageOnly:true,expanded:true});
  assert.equal(findElement(card,'a').props.href,'#contents/entry%230');
  for(const effect of globalThis.operationEffects)effect();
  delete globalThis.operationEffects;
  assert.deepEqual(paths,['/api/blue/v1/contents/message%231']);
});
function findComponent(element,name){
  if(!element||typeof element!=='object')return;
  if(element.type?.name===name)return element;
  return [element.props?.children].flat(Infinity).map(child=>findComponent(child,name)).find(Boolean);
}
for(const [tab,row,key,expected] of [
  ['Timelines',{timeline:'native#0'},'timeline','references/native%230'],
  ['Links',{direction:'outgoing',path:'/child',source:{sessionId:'child#0',documentRef:{blueId:'child-doc'}},target:{sessionId:'root',documentRef:{blueId:'root-doc'}}},'source','documents/child%230'],
])test(`document ${tab} navigation respects native and host identities`,()=>{
  const states=[{name:'Root',documentId:'source',control:"RUNNING",channels:[row]},'',undefined,[row],[],[],'',false,false];
  let cursor=0;globalThis.operationFormState={useState:()=>[states[cursor++],()=>{}]};
  const paths=[];
  const document=DocumentDetail({id:'root',search:`view=${tab}`,navigate:path=>paths.push(path),operations:()=>{},accountId:'account'});
  const table=findComponent(document,'DataTable');
  const cell=table.props.columns.find(column=>column.key===key).render(row);
  cell.props.onClick();
  assert.deepEqual(paths,[expected]);
});


for(const [type,expected] of [
  ['TIMELINE_CREATED','#timelines/timeline%230'],
  ['TIMELINE_ENTRY_CREATED','#references/timeline%230'],
])test(`${type} links respect the retained event identity role`,()=>{
  const row={type,ref:{timelineId:'timeline#0'}};
  const states=[[row],undefined,'','','',''];
  let cursor=0;globalThis.operationFormState={useState:()=>[states[cursor++],()=>{}]};
  const events=EventsPage({});
  const table=findComponent(events,'DataTable');
  const link=table.props.columns.find(column=>column.key==='ref').render(row);
  assert.equal(link.props.href,expected);
});

test('document Links loads the default traversal with a valid paginated URL',async()=>{
 const doc={sessionId:'root',instanceId:'root',name:'Root',control:'RUNNING',lastStable:null,channels:[]};
 const states=[doc,'',undefined,[],[],[],'',false,false];
 let cursor=0;globalThis.operationFormState={useState:()=>[states[cursor++],()=>{}]};
 globalThis.operationEffects=[];
 const paths=[];globalThis.fetch=async path=>{paths.push(path);return Response.json(path.endsWith('/documents/root')?doc:{items:[],nextPageToken:null});};
 DocumentDetail({id:'root',search:'view=Links',navigate:()=>{},operations:()=>{},accountId:'account'});
 const cleanups=globalThis.operationEffects.map(effect=>effect());
 delete globalThis.operationEffects;
 await new Promise(resolve=>setImmediate(resolve));
 cleanups.forEach(cleanup=>cleanup?.());
 assert.deepEqual(paths.filter(path=>path.includes('/relationships')),['/api/blue/v1/documents/root/relationships?limit=100']);
});

function contentHarness(component, props) {
  const state=[];let cursor=0;
  globalThis.operationFormState={useState:initial=>{
    const index=cursor++;
    if (!(index in state)) state[index]=initial;
    return [state[index],value=>{state[index]=value;}];
  }};
  return {render(){cursor=0;return component(props);},effects(){
    globalThis.operationEffects=[];this.render();
    const effects=globalThis.operationEffects;delete globalThis.operationEffects;
    return effects.map(effect=>effect());
  }};
}
const literalContent={mode:'exact',text:'{"hidden":true}',format:'json',value:42,body:{items:[1,2,3]},proof:'ordinary data'};
test('content pages preserve literal mode, text, value, body and proof fields',async()=>{
  globalThis.fetch=async()=>Response.json(literalContent);
  const harness=contentHarness(Raw,{path:'contents/direct%230'});
  const cleanups=harness.effects();
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(findComponent(harness.render(),'CodeViewer').props.value,literalContent);
  cleanups.forEach(cleanup=>cleanup?.());
});
for (const variant of ['reference','inline','entry']) test(`timeline ${variant} displays direct values without inferring wrappers`,async()=>{
  const paths=[];globalThis.fetch=async path=>{paths.push(path);return Response.json(variant==='entry'?{message:literalContent}:literalContent);};
  const entry={blueId:'entry#0',entry:{message:variant==='inline'?literalContent:{blueId:'message#1'}}};
  const harness=contentHarness(EntryCard,{entry,messageOnly:variant!=='entry',expanded:true});
  const cleanups=harness.effects();
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(findComponent(harness.render(),'CodeViewer').props.value,variant==='entry'?{message:literalContent}:literalContent);
  assert.deepEqual(paths,variant==='inline'?[]:[`/api/blue/v1/contents/${variant==='entry'?'entry%230':'message%231'}`]);
  cleanups.forEach(cleanup=>cleanup?.());
});
for(const retired of ['collections/owner?path=%2Fitems','reader-keys']) test(`removed ${retired} route does not expose a retired browser`,()=>{
  globalThis.location={hash:'#'+retired};
  const account={accountId:'account',userId:'user',sessionContext:'guard',membership:{generation:1,revision:1,role:'ADMIN'}};
  const state=[{userId:'user'},account,false,false,'',retired];
  let cursor=0;globalThis.operationFormState={useState:()=>[state[cursor++],()=>{}]};
  const tree=App();
  assert.equal(findComponent(tree,'CollectionDetail'),undefined);assert.equal(findComponent(tree,'ReaderKeys'),undefined);
  assert.ok(findComponent(tree,'Explorer'));
});

for (const value of [literalContent, false, 0, '', null, [1, {items:[2,3]}]]) test(`epoch event preserves direct ${JSON.stringify(value)} content`,async()=>{
  const paths=[];globalThis.fetch=async path=>{paths.push(path);return Response.json(value);};
  const harness=contentHarness(EventContent,{blueId:'event#2',first:true});
  const cleanups=harness.effects();
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(findComponent(harness.render(),'CodeViewer').props.value,value);
  harness.effects();
  assert.deepEqual(paths,['/api/blue/v1/contents/event%232']);
  cleanups.forEach(cleanup=>cleanup?.());
});
