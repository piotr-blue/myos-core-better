import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile, unlink } from 'node:fs/promises';
import { build } from 'esbuild';
import { parse } from 'yaml';
const compiled = (await build({stdin:{contents:'export * from "./src/example-inspection"; export * from "./src/featured-examples";',resolveDir:process.cwd()},bundle:true,write:false,format:'esm',packages:'external',loader:{'.css':'empty'}})).outputFiles[0].text;
const target = new URL('../.example-inspection-test.mjs', import.meta.url);
await writeFile(target, compiled);
let module;
try { module = await import(target.href); } finally { await unlink(target); }
const {inspectExample,exampleHref}=module;
const catalogue=JSON.parse(await readFile('src/data/clause-examples.json','utf8'));
test('all catalogue examples retain exact source and expose valid clause/template paths',()=>{
  for(const example of catalogue){
    const documents=inspectExample(example.source);
    assert.equal(documents[0].source,example.source);
    assert.ok(documents[0].clauses.length);
    const root=parse(example.source,{intAsBigInt:true});
    for(const doc of documents){
      const expected=doc.path==='/'?root:doc.path.slice(1).split('/').reduce((v,k)=>v[k.replace(/~1/g,'/').replace(/~0/g,'~')],root);
      assert.deepEqual(parse(doc.source,{intAsBigInt:true}),expected);
      for(const clause of doc.clauses)assert.deepEqual(parse(clause.source,{intAsBigInt:true}),expected.clauses[clause.key]);
    }
  }
});
test('nested inspection keeps high precision values and escaped paths',()=>{
  const docs=inspectExample('name: Root\nclauses: {}\na/b:\n  name: Child\n  amount: 9007199254740993\n  rate: 0.123456789012345678901\n  clauses:\n    feature:\n      type: Features/Feature Clause\n');
  assert.equal(docs[1].path,'/a~1b');
  assert.match(docs[1].source,/9007199254740993/);assert.match(docs[1].source,/0\.123456789012345678901/);
  assert.equal(new URLSearchParams(exampleHref('id','/a~1b','feature').split('?')[1]).get('document'),'/a~1b');
});
test('the dashboard no longer includes its own marketing site or examples index',async()=>{
  const main=await readFile('src/main.tsx','utf8'),layout=await readFile('src/components/dev-layout.tsx','utf8');
  assert.doesNotMatch(main,/LandingPage|<Examples/);
  assert.doesNotMatch(layout,/href="#home"|label: "Examples"/);
  assert.match(layout,/aria-label=\{adminPanel \? "MyOS Admin Panel" : "MyOS Dev home"\}/);
  assert.match(layout,/portalOrigin\(\)}\/dev\//);
});
