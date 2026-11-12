import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
async function load(path) {
  const compiled=(await build({entryPoints:[path],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
  return import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
}
const {api}=await load('src/api.ts');
const {decodeNativeContent}=await load('src/components/viewer.tsx');
const {stringifyBlue,parseBlue,sourceField}=await load('src/blue-values.ts');
const {minimize}=await load('src/presentation.ts');
test('API and exact JSON decoding preserve integers above the safe number range',async()=>{
  globalThis.fetch=async()=>new Response('{"value":900719925474099312345,"count":2,"fraction":0.125}');
  const result=await api('contents/example');
  assert.equal(String(result.value),'900719925474099312345');
  assert.equal(result.count,2);
  assert.equal(result.fraction,0.125);
  assert.equal(String(decodeNativeContent({mode:'exact',text:'{"value":900719925474099312345}'}).value),'900719925474099312345');
});
test('exact YAML decodes using its format without rounding large integers',()=>{
  assert.deepEqual(decodeNativeContent({mode:'exact',format:'yaml',text:'name: sample\nvalue: 900719925474099312345'}),{name:'sample',value:900719925474099312345n});
});
test('display and copy serializers retain large integer digits as numbers in JSON and YAML',async()=>{
  const {stringify}=await import('yaml');
  const value=minimize({type:{blueId:'9eWaHYz2vKrFofdHTHAizNNu8xP6QE3WQ5y7DGrGZvyJ'},value:900719925474099312345n});
  assert.equal(value,900719925474099312345n);
  assert.equal(stringifyBlue({value},2),'{\n  "value": 900719925474099312345\n}');
  assert.equal(stringify({value}),'value: 900719925474099312345\n');
  assert.deepEqual(parseBlue(stringifyBlue({value},2)),{value});
});
test('request fields preserve source tokens without adding a transport wrapper',()=>{
  const source='count: 9007199254740993\nrate: 0.12345678901234567890123456789';
  assert.equal(sourceField('content',source),'content:\n  count: 9007199254740993\n  rate: 0.12345678901234567890123456789\n');
  assert.deepEqual(parseBlue(sourceField('message','"42"')),{message:'42'});
  assert.throws(()=>sourceField('message','[unterminated'));
});
for (const source of ['---', '---\ncount: 1\n...', '%YAML 1.2\n---\ncount: 9007199254740993\n...', '---\n!!str 42\n...', '---\n&root [1,2]\n...']) test('source fields accept YAML document boundaries while preserving scalar tags and anchors',()=>{
  assert.deepEqual(parseBlue(sourceField('content',source)),{content:parseBlue(source)});
});
