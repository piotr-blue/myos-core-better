import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {build} from 'esbuild';
const read=path=>JSON.parse(readFileSync(path,'utf8'));
const runtimes=read('src/data/process-runtimes.json');
const original=read('../packages/current.json').packages;
const selected=read('../runtime/blue-core-service/src/main/resources/package-catalog/access-events-defaults.json').packages;
const examples=read('src/data/clause-examples.json');
const compiled=(await build({entryPoints:['src/process-packages.ts'],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
const {processEdition,processPackages,validateProcessSelection,selectProcessExecution}=await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));

test('each original lesson root maps only to the corresponding exact published access-events namespace',()=>{
  assert.equal(examples.length,29);
  for(const example of examples){
    assert.deepEqual(processPackages(runtimes.finalGas,example.packages),example.packages);
    assert.deepEqual(processPackages(runtimes.finalGasCanonical,example.packages),example.packages);
    const expected=example.packages.map(id=>selected.find(row=>row.name===original.find(prior=>prior.blueId===id)?.namespace)?.blueId);
    assert.ok(expected.every(Boolean),example.id);
    assert.deepEqual(processPackages(runtimes.accessEvents,example.packages),expected,example.id);
  }
});

test('the full exact native identity is required; package containment and shared protocol IDs are insufficient',()=>{
  assert.equal(processEdition(runtimes.accessEvents),'accessEvents');
  assert.equal(processEdition(Object.fromEntries(Object.entries(runtimes.finalGas).reverse())),'finalGas');
  for(const runtime of [undefined,{}, {...runtimes.finalGas,unknown:true},
    {...runtimes.accessEvents,supportedPackages:runtimes.accessEvents.supportedPackages.slice(1)},
    {...runtimes.accessEvents,contractsSpecification:'different'},
    {...runtimes.accessEvents,canonicalWireMeasurementV1:'unknown'}])
    assert.throws(()=>processEdition(runtime),/runtime is not supported/);
  assert.throws(()=>processPackages(runtimes.accessEvents,['unreviewed-root']),/no reviewed package selection/);
});

test('saved selections are validated without rewriting their packages or runtime',()=>{
  const roots=examples[0].packages;
  const selection={runtime:runtimes.accessEvents,packages:processPackages(runtimes.accessEvents,roots)};
  const before=JSON.stringify(selection);validateProcessSelection(selection,roots);assert.equal(JSON.stringify(selection),before);
  assert.throws(()=>validateProcessSelection({...selection,packages:roots},roots),/saved example package selection/);
  assert.throws(()=>validateProcessSelection({...selection,runtime:{...selection.runtime,language:'unknown'}},roots),/runtime is not supported/);
});

test('canonical access-events uses the same exact published package roots',()=>{
  const canonical={...runtimes.accessEvents,canonicalWireMeasurementV1:runtimes.finalGasCanonical.canonicalWireMeasurementV1};
  assert.equal(processEdition(canonical),'accessEvents');
  assert.deepEqual(processPackages(canonical),selected.map(row=>row.blueId));
});


test('execution choices preserve implicit defaults and exact retained request mode',()=>{
 const canonical={...runtimes.accessEvents,canonicalWireMeasurementV1:runtimes.finalGasCanonical.canonicalWireMeasurementV1};
 const account={runtime:runtimes.finalGas,registrationExecutionSelections:[canonical]};
 assert.deepEqual(selectProcessExecution([account,account]),{runtime:runtimes.finalGas});
 const explicit={runtime:canonical,executionSelection:canonical};
 assert.deepEqual(selectProcessExecution([account,account],explicit),explicit);
 assert.throws(()=>selectProcessExecution([{...account,registrationExecutionSelections:[]}],explicit),/saved execution selection/);
 assert.throws(()=>selectProcessExecution([{runtime:canonical}],explicit),/saved execution selection/);
 assert.throws(()=>selectProcessExecution([{runtime:{unknown:true},registrationExecutionSelections:[{...canonical,unknown:true}]}]),/common supported/);
 const differingDefaults=[account,{runtime:{unknown:true},registrationExecutionSelections:[canonical]}];
 assert.deepEqual(selectProcessExecution(differingDefaults),explicit);
 assert.throws(()=>validateProcessSelection({...explicit,executionSelection:runtimes.finalGas,packages:processPackages(canonical,examples[0].packages)},examples[0].packages),/saved example package selection/);
});

const definitionRuntime={...runtimes.accessEvents,
 definitionReadV1:'H42SsDn1Ku9uEdLhuuYW28XiV1e2Pg4KY8NxeVQB5YmA',
 supportedPackages:[...runtimes.accessEvents.supportedPackages,'DksJU7gWzXhVx7X4oggEYcNtTvn9JBQLUmHKRk38CMUH'].sort()};
test('fresh definition-reading accounts use their exact default and the access-events catalogue',()=>{
 assert.equal(processEdition(definitionRuntime),'accessEvents');
 assert.deepEqual(selectProcessExecution([{runtime:definitionRuntime}]),{runtime:definitionRuntime});
 assert.deepEqual(processPackages(definitionRuntime),selected.map(row=>row.blueId));
});
test('retained access-events accounts explicitly select definition reads for new examples without changing their default',()=>{
 const account={runtime:runtimes.accessEvents,registrationExecutionSelections:[definitionRuntime]};
 const before=JSON.stringify(account);
 assert.deepEqual(selectProcessExecution([account]),{runtime:definitionRuntime,executionSelection:definitionRuntime});
 assert.equal(JSON.stringify(account),before);
 const retained={runtime:runtimes.accessEvents};
 assert.deepEqual(selectProcessExecution([account],retained),retained);
});

test('mixed old and new accounts select one advertised definition runtime in either participant order',()=>{
 const old={runtime:runtimes.accessEvents,registrationExecutionSelections:[definitionRuntime]};
 const fresh={runtime:definitionRuntime,registrationExecutionSelections:[definitionRuntime]};
 const expected={runtime:definitionRuntime,executionSelection:definitionRuntime};
 assert.deepEqual(selectProcessExecution([old,fresh]),expected);
 assert.deepEqual(selectProcessExecution([fresh,old]),expected);
 assert.throws(()=>selectProcessExecution([old,{...fresh,registrationExecutionSelections:[]}]),/common supported/);
});

const better=read('src/data/process-better.json');
test('Better uses its own complete reviewed execution and exact deployed roots',()=>{
 assert.equal(processEdition(better.runtime),'better');
 assert.deepEqual(selectProcessExecution([{runtime:better.runtime}]),{runtime:better.runtime});
 assert.deepEqual(processPackages(better.runtime),better.packages.map(p=>p.blueId));
 for(const change of [{hostHistory:'old'}, {contracts:runtimes.finalGas.contracts}, {supportedPackages:better.runtime.supportedPackages.slice(1)}])
   assert.throws(()=>processEdition({...better.runtime,...change}),/runtime is not supported/);
 const hello=examples.find(row=>row.id==='00-hello');
 assert.deepEqual(processPackages(better.runtime,hello.packages),hello.packages.map(id=>
   better.packages.find(row=>row.name===original.find(prior=>prior.blueId===id).namespace).blueId));
 const needsMore=examples.find(row=>row.id==='01-order-payment');
 assert.throws(()=>processPackages(better.runtime,needsMore.packages),/not yet deployed/);
});
