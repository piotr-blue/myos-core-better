// Original API Keys UI against an isolated real host; credentials never enter screenshots/reports.
import {chromium} from '../core-ui/node_modules/playwright/index.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {Configuration,DocumentsApi,ResponseError} from '../sdk/typescript/dist/index.js';
const url=process.argv[2];assert.match(url,/^http:\/\/localhost:\d+$/);
const output=process.env.DASHBOARD_REPORTS||'reports/keys-browser';await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true});
try {
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];let signedIn=false;
 page.on('pageerror',e=>errors.push({type:'page',message:e.message}));
 page.on('response',r=>{if(r.status()>=400&&r.url().startsWith(url))errors.push({status:r.status(),path:new URL(r.url()).pathname,beforeLogin:!signedIn});});
 await page.goto(url);await page.getByLabel('Email').fill('sdk@example.invalid');await page.getByLabel('Password',{exact:true}).fill('loopback-test-only');
 await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByRole('heading',{name:'Documents',exact:true}).waitFor();signedIn=true;
 await page.goto(url+'/#api-keys');await page.getByRole('heading',{name:'API Keys',exact:true}).waitFor();
 await page.getByLabel('Key name',{exact:true}).fill('Browser membership key');await page.getByRole('button',{name:'Create key',exact:true}).click();
 await page.getByText('Your new API key',{exact:true}).waitFor();const secret=await page.locator('.key-value').innerText();assert.match(secret,/^myos_better_u1_/);
 const machine=new DocumentsApi(new Configuration({basePath:url,headers:{Authorization:'Bearer '+secret}}));assert.deepEqual((await machine.listDocuments({})).items,[]);
 await page.getByRole('button',{name:'Dismiss',exact:true}).click();await page.locator('.key-value').waitFor({state:'hidden'});
 const row=page.getByRole('row').filter({hasText:'Browser membership key'});await row.getByText('Active',{exact:true}).waitFor();
 await page.screenshot({path:output+'/created-key.png',fullPage:true});
 await page.reload();await row.getByText('Active',{exact:true}).waitFor();assert.equal(await page.locator('.key-value').count(),0);
 await row.getByRole('button',{name:'Revoke',exact:true}).click();await row.getByText('Revoked',{exact:true}).waitFor();
 await assert.rejects(machine.listDocuments({}),e=>e instanceof ResponseError&&e.response.status===401);
 await page.screenshot({path:output+'/revoked-key.png',fullPage:true});
 assert.deepEqual(errors.filter(e=>!(e.status===401&&e.beforeLogin&&['/api/blue/v1/me','/api/auth/refresh'].includes(e.path))),[]);
 const report={passed:true,screens:2,actions:['create','bearer read','dismiss secret','reload','revoke','bearer rejected'],errors};
 await writeFile(output+'/report.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
} finally {await browser.close();}
