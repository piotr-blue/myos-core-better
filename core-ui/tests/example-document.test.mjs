import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { parse } from 'yaml';
const compiled=(await build({entryPoints:['src/example-document.ts'],bundle:true,write:false,format:'esm'})).outputFiles[0].text;
const {prepareExampleDocument,distinctExampleAccounts}=await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
const source=`name: Example
amount: 9007199254740993
rate: 0.12345678901234567890123456789
clauses:
  activation:
    type: Activation/Activation Clause
    parameters:
      parties:
        buyer:
          type: MyOS/MyOS Party
          myosAccountId: old-account
          representatives:
            agent: {email: old@example.com}
`;
test('launch binds a human representative to its party account without changing business values',()=>{
  const result=prepareExampleDocument(source,[{name:'buyer',label:'Buyer'},{name:'buyer__agent',label:'Agent'}],{buyer:'buyer-account',buyer__agent:'buyer-account'},'current',{buyer__agent:'user-1'});
  const doc=parse(result,{intAsBigInt:true});
  assert.equal(doc.amount,9007199254740993n);
  assert.match(result,/0\.12345678901234567890123456789/);
  const buyer=doc.clauses.activation.parameters.parties.buyer;
  assert.equal(buyer.email,undefined);assert.equal(buyer.myosAccountId,'buyer-account');
  assert.equal(doc.clauses.activation.parameters.frozen,true);
  assert.deepEqual(buyer.representatives.agent,{myosAccountId:'buyer-account',representativeId:'user-1'});
});
test('an explicitly cleared role cannot silently become the current account',()=>{
  assert.throws(()=>prepareExampleDocument(source,[{name:'buyer',label:'Buyer'}],{buyer:' '},'current'),/Choose an account for Buyer/);
});

test('example launch requires chosen account IDs and validates the complete batch before bootstrap',async()=>{
  await assert.rejects(distinctExampleAccounts([['buyer','agent']],{buyer:'alice@example.com',agent:'bob'},'current',async()=>assert.fail('Choose an account before launching')),/account ID/);
  await assert.rejects(distinctExampleAccounts([['buyer','agent']],{buyer:'alice',agent:'alice'},'current',async()=>({items:[{accountId:'alice'}],hasMore:false,nextPageToken:null})),/different accounts/);
  const requests=[];
  const result=await distinctExampleAccounts([['buyer','agent']],{buyer:'alice',agent:'bob'},'current',async request=>{requests.push(request);return {items:request.accountIds.map(accountId=>({accountId})),hasMore:false,nextPageToken:null};});
  assert.deepEqual(result,{buyer:'alice',agent:'bob'});
  assert.equal(requests.length,1);assert.deepEqual(requests[0].accountIds,['alice','bob']);
  await assert.rejects(distinctExampleAccounts([['buyer','agent']],{buyer:'alice',agent:'missing'},'current',async()=>({items:[{accountId:'alice'}],hasMore:false,nextPageToken:null})),/missing.*not found/);
});

test('example batch lookup follows account pages and rejects incomplete continuation',async()=>{
  const requests=[];
  const result=await distinctExampleAccounts([['buyer','seller']],{buyer:'alice',seller:'bob'},'current',async request=>{
    requests.push(request);
    return request.pageToken?{items:[{accountId:'bob'}],hasMore:false,nextPageToken:null}:{items:[{accountId:'alice'}],hasMore:true,nextPageToken:'page-2'};
  });
  assert.deepEqual(result,{buyer:'alice',seller:'bob'});assert.equal(requests[1].pageToken,'page-2');
  assert.deepEqual(requests[0].accountIds,requests[1].accountIds);
  await assert.rejects(distinctExampleAccounts([['buyer','seller']],{buyer:'alice',seller:'bob'},'current',async()=>({items:[],hasMore:true,nextPageToken:null})),/incomplete/);
});
