import {test} from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {parse} from 'yaml';
const output=await build({entryPoints:['src/lessons/accounts.ts','src/lessons/model.ts'],bundle:true,write:false,format:'esm',outdir:'unused'});
const load=async file=>import('data:text/javascript;base64,'+Buffer.from(output.outputFiles.find(f=>f.path.endsWith(file)).text).toString('base64'));
const {createRun,suggestAccounts,mainRoles,actingAccount,runnable}=await load('accounts.js');
const {lessons,definitionOf}=await load('model.js');
const member=id=>({accountId:id,profile:{displayName:id},membership:{status:'ACTIVE',generation:1}});
const user={userId:'human',accounts:[member('one'),member('two'),member('three')]};
for(const lesson of lessons)test(`account mapping preserves exact packages and source for ${lesson.id}`,()=>{
 const mapping=Object.fromEntries(mainRoles(lesson).map(r=>[r.name,'one']));
 if(!runnable(lesson)){assert.throws(()=>createRun(lesson,user,mapping),/source preview/);return;}
 for(const [,right] of lesson.distinctRoles||[])mapping[right]='two';
 const run=createRun(lesson,user,mapping),source=parse(run.source);
 assert.equal(run.definition,definitionOf(lesson));assert.equal(run.results.length,0);
 for(const role of lesson.roles){const [party,rep]=role.name.split('__');const node=source.clauses.activation.parameters.parties[party];assert.equal((rep?node.representatives[rep]:node).myosAccountId,mapping[party]);if(rep)assert.equal(node.representatives[rep].representativeId,'human');}
 assert.equal(actingAccount(run,lesson),'one');
});
test('suggest distinct managed accounts, leave missing roles explicit, permit reuse',()=>{
 const l=lessons.find(l=>l.id==='01-order-payment');
 assert.deepEqual(Object.values(suggestAccounts(l,user,'two')),['two','one','three']);
 assert.deepEqual(Object.values(suggestAccounts(l,{...user,accounts:[member('one')]},'one')),['one','','']);
 assert.throws(()=>createRun(l,user,{customer:'one',seller:'unmanaged',guarantor:'one'}),/available account/);
 assert.throws(()=>createRun(l,user,{}),/available account/);
});
test('negative representative actions use the representative account and retries retain the submitting account',()=>{
 const lesson=lessons.find(l=>l.id==='08-participants-agents');const run=createRun(lesson,user,{buyer:'one',seller:'two'});run.root='root';run.step=3;
 assert.equal(actingAccount(run,lesson),'one');run.pending={accountId:'three'};assert.equal(actingAccount(run,lesson),'three');
});
test('account-to-account payment retains its declared distinct-account requirement',()=>{
 const lesson=lessons.find(l=>l.id==='22-myos-account');
 assert.throws(()=>createRun(lesson,user,{customer:'one',seller:'one',guarantor:'one'}),/need different accounts/);
 assert.ok(createRun(lesson,user,{customer:'one',seller:'two',guarantor:'one'}));
});
