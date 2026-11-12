// Original Agent settings UI; the unrelated, unfinished mandate-disclosure API is recorded explicitly.
import { chromium } from '../core-ui/node_modules/playwright/index.mjs';
import { AccountApi, Configuration } from '../sdk/typescript/dist/index.js';
import { mkdir, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
const url=process.argv[2];
assert.match(url,/^http:\/\/localhost:\d+$/);
const output=process.env.DASHBOARD_REPORTS || 'reports/agents-browser';
await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true});
try {
  const page=await browser.newPage({viewport:{width:1440,height:1000}});
  const errors=[];
  let signedIn=false;
  page.on('pageerror',e=>errors.push({type:'page',message:e.message}));
  page.on('response',r=>{if(r.status()>=400&&r.url().startsWith(url))errors.push({status:r.status(),path:new URL(r.url()).pathname,beforeLogin:!signedIn});});
  await page.goto(url);
  await page.getByLabel('Email').fill('sdk@example.invalid');
  await page.getByLabel('Password',{exact:true}).fill('loopback-test-only');
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await page.getByRole('heading',{name:'Documents',exact:true}).waitFor();
  signedIn=true;
  await page.goto(url+'/#settings/accounts');
  await page.getByRole('button',{name:'Create account',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Create account',exact:true});
  await dialog.getByLabel('Business Name').fill('Agent settings check');
  await dialog.getByRole('button',{name:'Create account',exact:true}).click();
  await dialog.waitFor({state:'hidden'});
  await page.waitForFunction(()=>document.querySelector('select[aria-label="Workspace"]')?.selectedOptions[0]?.textContent.includes('Agent settings check'));
  const account=await page.getByLabel('Workspace',{exact:true}).inputValue();
  const api=new AccountApi(new Configuration({basePath:url,headers:{Authorization:'Bearer sdk-test-user',Origin:url,'X-MyOS-Account':account}}));
  const identity=await api.getAccount({});
  const created=await api.createAccountAgent({idempotencyKey:randomUUID(),xMyOSSessionContext:identity.sessionContext,createAccountAgent:{name:'Browser Agent'}});
  await page.goto(url+'/#settings/agents');
  await page.getByRole('button',{name:'MyOS Agent',exact:true}).click();
  const builtin=page.getByRole('region',{name:'MyOS Agent',exact:true});
  assert.equal(await builtin.getByRole('checkbox').isChecked(),true);
  await Promise.all([
    page.waitForResponse(r=>r.request().method()==='PATCH'&&r.url().includes('/account/agents/')&&r.status()===200),
    builtin.getByRole('checkbox').click(),
  ]);
  assert.equal((await api.listAccountAgents({})).items.find(a=>a.kind==='BUILT_IN').inclusion,false);
  await page.screenshot({path:output+'/builtin-agent.png',fullPage:true});
  await page.getByRole('button',{name:'Browser Agent',exact:true}).click();
  let external=page.getByRole('region',{name:'Browser Agent',exact:true});
  await external.getByText('No approved connections. Connect an application to use this agent.',{exact:true}).waitFor();
  await external.getByLabel('Name',{exact:true}).fill('Renamed Agent');
  await external.getByRole('button',{name:'Save name',exact:true}).click();
  external=page.getByRole('region',{name:'Renamed Agent',exact:true});
  await external.getByRole('button',{name:'Pause Agent',exact:true}).click();
  await external.getByRole('button',{name:'Resume Agent',exact:true}).waitFor();
  const stored=(await api.listAccountAgents({})).items.find(a=>a.agentId===created.agentId);
  assert.equal(stored.name,'Renamed Agent');
  assert.equal(stored.status,'PAUSED');
  await page.screenshot({path:output+'/external-agent.png',fullPage:true});
  const pending=errors.filter(e=>e.status===501&&e.path==='/api/blue/v1/account/mandate-sharing');
  assert.ok(pending.length>0,'Keep the unfinished mandate API visible in this report');
  assert.deepEqual(errors.filter(e=>!(e.status===401&&e.beforeLogin&&['/api/blue/v1/me','/api/auth/refresh'].includes(e.path))&&!pending.includes(e)),[]);
  await writeFile(output+'/report.json',JSON.stringify({settingsPassed:true,screens:2,pending:pending,errors},null,2)+'\n');
  console.log(JSON.stringify({settingsPassed:true,screens:2,pending:pending,errors}));
} finally { await browser.close(); }
