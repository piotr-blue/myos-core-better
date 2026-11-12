// Use the original Bootstrap and Timeline append forms; assert native document execution.
import {chromium} from '../core-ui/node_modules/playwright/index.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const url=process.argv[2];assert.match(url,/^http:\/\/localhost:\d+$/);
const output=process.env.DASHBOARD_REPORTS||'reports/timeline-operation-browser';await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true});let page;
try {
 page=await browser.newPage({viewport:{width:1440,height:1100}});const errors=[];let signedIn=false;
 page.on('pageerror',e=>errors.push({type:'page',message:e.message}));
 page.on('response',r=>{if(r.status()>=400&&r.url().startsWith(url))errors.push({status:r.status(),path:new URL(r.url()).pathname,beforeLogin:!signedIn});});
 await page.goto(url);await page.getByLabel('Email').fill('sdk@example.invalid');await page.getByLabel('Password',{exact:true}).fill('loopback-test-only');
 await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByRole('heading',{name:'Documents',exact:true}).waitFor();signedIn=true;
 await page.getByRole('button',{name:'Bootstrap document',exact:true}).click();
 const payload=page.getByLabel('Bootstrap payload');await payload.waitFor();
 await page.waitForFunction(()=>document.querySelector('textarea')?.value.includes('REPLACE_BOB_ACCOUNT_ID'));
 const text=await payload.inputValue(),owner=text.match(/accountId: (user-[a-z0-9]+)/)[1];
 await payload.fill(text.replace('REPLACE_BOB_ACCOUNT_ID',owner));
 await page.getByRole('button',{name:'Bootstrap',exact:true}).click();
 await page.getByRole('tab',{name:'Operations',exact:true}).waitFor({timeout:15000});
 const id=decodeURIComponent(new URL(page.url()).hash.slice('#documents/'.length));
 async function get(path){const r=await page.request.get(url+'/api/blue/v1/'+path);assert.equal(r.status(),200);return r.json();}
 const before=await get('documents/'+id),operations=await get('documents/'+id+'/operations');
 const operation=operations.items.find(x=>x.name==='increment'),route=operation.routes[0];
 await page.goto(url+'/#timelines/'+route.timeline);await page.getByRole('heading',{name:'Timeline Details',exact:true}).waitFor();
 await page.getByRole('button',{name:'Append entry',exact:true}).click();
 await page.getByLabel('Document ID (required for operation requests)',{exact:true}).fill(before.documentId);
 await page.getByLabel('Message (JSON or YAML)',{exact:false}).fill('type: Contracts/Operation Request\nchannel: '+route.channel+'\noperation: increment');
 await page.screenshot({path:output+'/append-form.png',fullPage:true});
 const submitted=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/entries'));
 await page.getByRole('button',{name:'Append message',exact:true}).click();
 const response=await submitted;assert.equal(response.status(),202,await response.text());
 const admission=await response.json();assert.equal(admission.status,'SUCCEEDED');
 await page.getByText('Entry accepted:',{exact:false}).waitFor();
 await page.screenshot({path:output+'/accepted.png',fullPage:true});
 const deadline=Date.now()+10000;let current;
 do {current=await get('documents/'+id);if(current.lastStable.epoch===before.lastStable.epoch+1)break;await new Promise(r=>setTimeout(r,25));} while(Date.now()<deadline);
 assert.equal(current.lastStable.epoch,before.lastStable.epoch+1);
 const value=await get('documents/'+id+'/content?view=operation_context&path=%2Fcount');assert.equal(value.valueJson,'1');
 await page.goto(url+'/#documents/'+id);await page.getByRole('tab',{name:'History',exact:true}).click();
 await page.getByText('Epoch 1',{exact:false}).first().waitFor();
 await page.screenshot({path:output+'/history.png',fullPage:true});
 assert.deepEqual(errors.filter(e=>!(e.status===401&&e.beforeLogin&&['/api/blue/v1/me','/api/auth/refresh'].includes(e.path))),[]);
 const report={passed:true,entry:admission.details.entryRef,document:id,epoch:current.lastStable.epoch,count:value.valueJson,errors};
 await writeFile(output+'/report.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
} catch (failure) {
 if(page){await page.screenshot({path:output+'/failure.png',fullPage:true});await writeFile(output+'/failure.html',await page.content());}
 throw failure;
} finally {await browser.close();}
