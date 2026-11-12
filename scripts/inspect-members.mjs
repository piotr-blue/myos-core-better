// Reused account-users UI against the real isolated membership command implementation.
import {chromium} from '../core-ui/node_modules/playwright/index.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const url=process.argv[2];assert.match(url,/^http:\/\/localhost:\d+$/);
const output=process.env.DASHBOARD_REPORTS||'reports/members-browser';await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true});
try {
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];let signedIn=false;
 page.on('pageerror',e=>errors.push({type:'page',message:e.message}));
 page.on('response',r=>{if(r.status()>=400&&r.url().startsWith(url))errors.push({status:r.status(),path:new URL(r.url()).pathname,beforeLogin:!signedIn});});
 await page.goto(url);await page.getByLabel('Email').fill('sdk@example.invalid');await page.getByLabel('Password',{exact:true}).fill('loopback-test-only');
 await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByRole('heading',{name:'Documents',exact:true}).waitFor();signedIn=true;
 await page.goto(url+'/#settings/users');
 const own=page.getByRole('row',{name:'Your membership',exact:true});await own.waitFor();
 assert.equal(await own.getByLabel('Role',{exact:true}).inputValue(),'OWNER');
 assert.equal(await own.getByRole('button',{name:'Remove',exact:true}).isDisabled(),true);
 await page.screenshot({path:output+'/owner.png',fullPage:true});
 await own.getByLabel('Role',{exact:true}).selectOption('ADMIN');
 const saved=page.waitForResponse(r=>r.request().method()==='PATCH'&&new URL(r.url()).pathname.startsWith('/api/blue/v1/account/users/'));
 await own.getByRole('button',{name:'Save',exact:true}).click();const response=await saved;assert.equal(response.status(),200);
 const receipt=await response.json();assert.equal(receipt.role,'ADMIN');assert.equal(receipt.revision,2);
 await own.getByText('Keep an Owner or Admin assigned before removing this user or reducing their role.',{exact:true}).waitFor();
 await page.reload();await own.waitFor();
 assert.equal(await own.getByLabel('Role',{exact:true}).inputValue(),'ADMIN');
 assert.equal(await own.getByRole('button',{name:'Remove',exact:true}).isDisabled(),true);
 assert.equal(await own.getByRole('button',{name:'Save',exact:true}).isDisabled(),true);
 await page.screenshot({path:output+'/admin.png',fullPage:true});
 assert.deepEqual(errors.filter(e=>!(e.status===401&&e.beforeLogin&&['/api/blue/v1/me','/api/auth/refresh'].includes(e.path))),[]);
 const report={passed:true,screens:2,actions:['read owner','release to admin','reload durable role','last-admin removal disabled'],errors};
 await writeFile(output+'/report.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
} finally {await browser.close();}
