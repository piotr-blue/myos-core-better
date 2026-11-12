// Unmodified Core invitation forms and acceptance, using an isolated private mailbox fixture.
import {chromium} from '../core-ui/node_modules/playwright/index.mjs';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import assert from 'node:assert/strict';
const origin=process.argv[2];assert.match(origin,/^http:\/\/localhost:\d+$/);
assert.ok(process.env.MYOS_TEST_INVITATION_OUTBOX,'Only the isolated test mailbox is supported');
const output=process.env.DASHBOARD_REPORTS||'reports/invitations-browser';await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true});
const errors=[],pages=[];
async function pageIn(context){
 const page=await context.newPage();pages.push(page);
 page.on('pageerror',e=>errors.push({type:'page',message:e.message}));
 page.on('response',r=>{if(r.status()>=400&&r.url().startsWith(origin))errors.push({status:r.status(),path:new URL(r.url()).pathname});});
 return page;
}
async function signIn(page,email){
 await page.getByLabel('Email').fill(email);await page.getByLabel('Password',{exact:true}).fill('loopback-test-only');
 await page.getByRole('button',{name:'Sign in',exact:true}).click();
}
async function invite(page){
 const form=page.locator('#account-invitations');
 if(await form.getByLabel('Recipient email').inputValue()!=='admin@example.invalid')await form.getByLabel('Recipient email').fill('admin@example.invalid');
 await form.getByRole('combobox').selectOption('WRITE');
 const sent=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/blue/v1/account/invitations');
 await form.getByRole('button',{name:'Send invite',exact:true}).click();
 const response=await sent;assert.equal(response.status(),201);return response.json();
}
async function linkFor(invitation){
 for(let attempt=0;attempt<100;attempt++){
  try {
   const delivery=JSON.parse(await readFile(join(process.env.MYOS_TEST_INVITATION_OUTBOX,invitation.invitationId+'.json'),'utf8'));
   assert.equal(delivery.recipient,'admin@example.invalid');
   const link=new URL(delivery.link);assert.equal(link.origin,'https://console.example');
   return origin+'/'+link.hash;
  }catch(error){if(error.code!=='ENOENT')throw error;await new Promise(r=>setTimeout(r,25));}
 }
 assert.fail('Private delivery did not complete');
}
async function accept(page,invitation){
 await page.goto(await linkFor(invitation));
 const accepted=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===`/api/blue/v1/invitations/${invitation.invitationId}/accept`);
 await page.getByRole('button',{name:'Accept invitation',exact:true}).click();
 const response=await accepted;assert.equal(response.status(),200);return response.status();
}
try {
 const owner=await pageIn(await browser.newContext({viewport:{width:1440,height:1000}}));
 await owner.goto(origin);await signIn(owner,'sdk@example.invalid');
 await owner.getByRole('heading',{name:'Documents',exact:true}).waitFor();
 await owner.goto(origin+'/#settings/users');
 const first=await invite(owner);
 await owner.getByRole('row').filter({hasText:'admin@example.invalid'}).getByText('Pending invite',{exact:true}).waitFor();
 await owner.screenshot({path:output+'/pending.png',fullPage:true});
 const guest=await pageIn(await browser.newContext({viewport:{width:1440,height:1000}}));
 await guest.goto(await linkFor(first));await signIn(guest,'admin@example.invalid');
 await guest.getByRole('heading',{name:'Accept account invitation',exact:true}).waitFor();
 assert.equal(new URL(guest.url()).hash,'#invitation');
 await guest.screenshot({path:output+'/accept.png',fullPage:true});
 const accepted=guest.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname===`/api/blue/v1/invitations/${first.invitationId}/accept`);
 await guest.getByRole('button',{name:'Accept invitation',exact:true}).click();
 const response=await accepted;assert.equal(response.status(),200);
 await owner.reload();
 const member=owner.getByRole('row').filter({hasText:'admin@example.invalid'}).filter({has:owner.getByLabel('Role',{exact:true})});
 await member.getByText('Active',{exact:true}).waitFor();
 await owner.screenshot({path:output+'/joined.png',fullPage:true});
 owner.once('dialog',dialog=>dialog.accept());
 const removed=owner.waitForResponse(r=>r.request().method()==='PATCH'&&new URL(r.url()).pathname.startsWith('/api/blue/v1/account/users/'));
 await member.getByRole('button',{name:'Remove',exact:true}).click();assert.equal((await removed).status(),200);
 const former=owner.getByRole('row').filter({hasText:'admin@example.invalid'});
 await former.getByText('Removed',{exact:true}).waitFor();
 await former.getByRole('button',{name:'Invite again',exact:true}).click();
 const second=await invite(owner);assert.ok(second.recipientUserId);
 assert.equal(await accept(guest,second),200);
 await owner.reload();await member.getByText('Active',{exact:true}).waitFor();
 await owner.screenshot({path:output+'/rejoined.png',fullPage:true});
 const unexpected=errors.filter(e=>!(e.status===401&&['/api/blue/v1/me','/api/auth/refresh'].includes(e.path)));
 assert.deepEqual(unexpected,[]);
 const report={passed:true,screens:4,actions:['original invitation form','private test delivery','original acceptance after login','member removal','original Invite again','rejoin acceptance'],errors};
 await writeFile(output+'/report.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
} catch(error) {
 for(let i=0;i<pages.length;i++)await pages[i].screenshot({path:output+'/failure-'+i+'.png',fullPage:true});
 await writeFile(output+'/failure.json',JSON.stringify({errors,message:error.message},null,2)+'\n');
 throw error;
} finally {await browser.close();}
