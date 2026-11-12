import { chromium } from '../core-ui/node_modules/playwright/index.mjs';
import { mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const url = process.argv[2];
if (!/^http:\/\/localhost:\d+$/.test(url)) throw new Error('Loopback fixture only');
const output = process.env.DASHBOARD_REPORTS || 'reports/package-browser';
await mkdir(output, {recursive:true});
const browser = await chromium.launch({headless:true});
try {
  const page = await browser.newPage({viewport:{width:1440,height:1000}});
  const errors = [];
  let signedIn = false;
  page.on('pageerror', error => errors.push({message:error.message}));
  page.on('response', response => {
    if (response.status() >= 400 && response.url().startsWith(url)
        && !(response.status() === 401 && !signedIn && ['/api/blue/v1/me','/api/auth/refresh'].includes(new URL(response.url()).pathname)))
      errors.push({status:response.status(),url:response.url()});
  });
  await page.goto(url);
  await page.getByLabel('Email').fill('sdk@example.invalid');
  await page.getByLabel('Password',{exact:true}).fill('loopback-test-only');
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await page.getByRole('heading',{name:'Documents',exact:true}).waitFor();
  signedIn = true;
  await page.goto(url+'/#packages');
  await page.getByRole('link',{name:'ManualPayments',exact:true}).click();
  await page.getByRole('heading',{name:'ManualPayments',exact:true}).waitFor();
  const remove = page.getByRole('button',{name:'Remove',exact:true});
  assert.equal(await remove.isEnabled(),true);
  await page.screenshot({path:output+'/installed.png',fullPage:true});
  await remove.click();
  await page.getByRole('button',{name:'Install',exact:true}).waitFor();
  await page.reload();
  await page.getByRole('button',{name:'Install',exact:true}).waitFor();
  await page.screenshot({path:output+'/removed.png',fullPage:true});
  await page.getByRole('button',{name:'Install',exact:true}).click();
  await page.getByRole('button',{name:'Remove',exact:true}).waitFor();
  await page.screenshot({path:output+'/reinstalled.png',fullPage:true});
  await page.goto(url+'/#packages');
  await page.getByRole('link',{name:'Contracts',exact:true}).click();
  await page.getByRole('heading',{name:'Contracts',exact:true}).waitFor();
  await page.getByText('Managed',{exact:true}).waitFor();
  assert.equal(await page.getByRole('button',{name:'Remove',exact:true}).count(),0);
  assert.equal(await page.getByRole('alert').count(),0);
  await writeFile(output+'/result.json',JSON.stringify({screens:3,errors},null,2));
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({screens:3,errors}));
} finally { await browser.close(); }
