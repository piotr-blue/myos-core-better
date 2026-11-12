import { test } from 'node:test';
import { webcrypto } from 'node:crypto';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

const compiled = (await build({
  entryPoints: ['src/main.tsx'], bundle: true, write: false,
  format: 'iife', platform: 'browser', jsx: 'automatic',
  loader: { '.css': 'empty' }, define: { 'process.env.NODE_ENV': '"production"' },
})).outputFiles[0].text;

const json = (body, status = 200) => new Response(JSON.stringify(body), { status });
const membership = {generation:1,revision:1,role:'ADMIN',status:'ACTIVE'};
const account = {accountId:'test-workspace',kind:'PERSONAL',profile:{displayName:'Workspace'},revision:1,membership,userId:'user-1',email:'workspace@example.test',sessionContext:'selected-guard',accountModel:'MEMBERSHIPS_V1',sessionExpiresAt:4102444800000,capabilities:{}};
const user = {userId:account.userId,email:account.email,sessionContext:'user-guard',sessionExpiresAt:account.sessionExpiresAt,accountModel:account.accountModel,preferences:{defaultAccountId:account.accountId,revision:1},accounts:[account]};
function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}
async function until(predicate, message) {
  const deadline = Date.now() + 2000;
  while (!predicate() && Date.now() < deadline) await delay(5);
  assert.ok(predicate(), message);
}

function openConsole(t, { signedIn = false, info, route = 'documents', portalConfig = {}, agents = [], accessUnavailable = false } = {}) {
  const dom = new JSDOM('<div id="root"></div>', {
    url: `https://console.example.test/#${route}`, runScripts: 'outside-only', pretendToBeVisual: true,
  });
  const { window } = dom;
  t.after(() => window.close());
  const calls = [];
  let mandateSharingEnabled = false;
  const workspace = deferred();
  Object.defineProperty(window, 'crypto', { value: webcrypto });
  window.Headers = Headers; window.Request = Request; window.Response = Response;
  window.BroadcastChannel = class {close() {} postMessage() {}};
  window.sessionStorage.setItem('myos-core-account',JSON.stringify({accountId:account.accountId,userId:account.userId,generation:1}));
  window.TextDecoder = TextDecoder;
  window.scrollTo = () => {};
  window.fetch = async (path, options = {}) => {
    calls.push({ path, options });
    if (path === '/api/config') return json(portalConfig);
    if (path === '/api/auth/refresh') return json({ detail: 'Sign in required' }, 401);
    if (path === '/api/blue/v1/me') {
      if (!signedIn) return json({ detail: 'Sign in required' }, 401);
      return info ? info() : workspace.promise;
    }
    if (path === '/api/blue/v1/account') return json(account);
    if (path === '/api/blue/v1/account/agents') return json({ items: agents });
    if (path.startsWith('/api/blue/v1/account/agents/') && path.endsWith('/connections')) return json({ items: [] });
    if (path === '/api/blue/v1/account/mandate-sharing') {
      if (options.method === 'POST') mandateSharingEnabled = JSON.parse(options.body).enabled;
      return json({ enabled: mandateSharingEnabled });
    }
    if (accessUnavailable && ['/api/blue/v1/account/access-automation', '/api/blue/v1/access-requests'].includes(path))
      return json({type:'urn:myos:problem:access_automation_unavailable',detail:'This account does not use the access-events runtime'},409);
    if (path === '/api/blue/v1/account/access-automation')
      return json(options.method === 'POST' ? JSON.parse(options.body) : { automaticallyUpdatePartyAccess: true });
    if (path === '/api/blue/v1/access-requests') return json({ items: [] });
    if (path === '/api/blue/v1/live') return new Promise(() => {});
    if (path === '/api/blue/v1/packages' || path.startsWith('/api/blue/v1/documents?'))
      return json({ items: [], hasMore: false });
    throw new Error(`Unexpected request: ${path}`);
  };
  window.eval(compiled);
  const document = window.document;
  return {
    window, document, calls, workspace,
  };
}

test('account settings shows the selected profile and preserves the ordinary Business switch', async t => {
  const app = openConsole(t, {
    signedIn: true, info: () => json(user), route: 'settings/accounts?source=menu',
    portalConfig: { businessOrigin: 'https://console.example.test/business/', sharedPortalOrigin: 'https://console.example.test' },
  });
  await until(() => app.document.querySelector('h1')?.textContent === 'Settings', 'Account settings opens the selected profile');
  assert.equal(app.window.location.hash, '#settings/accounts?source=menu');
  assert.equal(app.document.querySelector('input').value,'Workspace');
  app.document.querySelector('button[aria-label="Account menu"]').click();
  await until(() => [...app.document.querySelectorAll('[role="menuitem"]')].some(item => item.textContent === 'MyOS Business'), 'Ordinary Business navigation remains available');
  assert.ok(app.document.querySelector('a[href="#settings/accounts"]'));
  assert.equal(app.document.querySelector('[aria-label="Business connection"]'), null);
  const link = new URL([...app.document.querySelectorAll('[role="menuitem"]')].find(item => item.textContent === 'MyOS Business').href);
  assert.equal(link.origin, 'https://console.example.test');
  assert.equal(link.pathname, '/business/');
  assert.equal(link.search, '');
  assert.equal(app.calls.filter(call => call.path === '/api/auth/onboarding').length, 0);
});

// Better intentionally retains its existing Cognito forms; all post-login UI is upstream.
test('Better sign-in retains the example selected before authentication', async t => {
  const route = 'signup?example=02-reseller-ledger';
  const app = openConsole(t, { route });
  await until(() => app.document.querySelector('input[type="password"]'), 'Existing Better sign-in must be shown');
  assert.equal(app.window.location.hash, '#' + route);
  assert.equal(app.calls.filter(call => call.path.includes('/documents/bootstrap')).length, 0);
});

test('the console preserves Better login and signup forms without an external redirect', async t => {
  const app = openConsole(t);
  await until(() => app.document.querySelector('input[type="password"]'), 'Existing Better sign-in must be shown');
  assert.equal(app.document.querySelector('a[href*="/sign-in?"]'), null);
  [...app.document.querySelectorAll('button')].find(button => button.textContent === 'Create an account').click();
  await until(() => app.document.querySelector('h2')?.textContent === 'Create your account', 'Signup remains available');
  assert.equal(app.calls.filter(call => call.path === '/api/auth/login').length, 0);
});

test('an existing session shows workspace loading until user discovery completes', async t => {
  const app = openConsole(t, { signedIn: true });
  await until(() => app.document.querySelector('.workspace-loading'), 'Session restoration must show workspace loading');
  assert.equal(app.document.querySelector('form'), null);
  app.workspace.resolve(json(user));
  await until(() => app.document.querySelector('nav[aria-label="Main navigation"]'), 'Restored session must open the workspace');
  assert.equal(app.calls.filter(call => call.path === '/api/auth/login').length, 0);
});

test('a rejected session returns from workspace loading to Better sign-in', async t => {
  const app = openConsole(t, { signedIn: true });
  await until(() => app.document.querySelector('.workspace-loading'), 'Workspace loading must be visible');
  app.workspace.resolve(json({ detail: 'Session expired' }, 401));
  await until(() => app.document.querySelector('input[type="password"]'), 'Rejected session must return to Better sign-in');
  assert.ok(app.document.querySelector('form'));
  assert.equal(app.document.querySelector('.workspace-loading'), null);
});

test('workspace loading stays visible during a transient user discovery retry', async t => {
  let attempts = 0;
  const retry = deferred();
  const app = openConsole(t, { signedIn: true, info: () => {
    attempts++;
    return attempts === 1
      ? new Response('{}', { status: 503, headers: { 'Retry-After': '0' } })
      : retry.promise;
  } });
  await until(() => attempts === 2, 'Info must be retried after a temporary failure');
  await until(() => app.document.querySelector('.workspace-loading'), 'The workspace loading screen must remain visible during the retry');
  assert.equal(app.document.querySelector('form'), null);
  retry.resolve(json(user));
  await until(() => app.document.querySelector('nav[aria-label="Main navigation"]'), 'Workspace must open after recovery');
});


test('customer payment sign-in retains the exact session and action route', async t => {
  const route = 'customer-payment/session-200?action=action-200';
  const app = openConsole(t, { route });
  await until(() => app.document.querySelector('input[type="password"]'), 'Existing Better sign-in must be shown');
  assert.equal(app.window.location.hash, '#' + route);
});

test('participant-access settings reads the owner preference and updates it only on user action', async t => {
  const app = openConsole(t, { signedIn: true, info: () => json(user), route: 'settings' });
  await until(() => app.document.querySelector('a[href="#access-settings"]'), 'Workspace settings exposes participant access');
  app.document.querySelector('a[href="#access-settings"]').click();
  await until(() => app.document.querySelector('input[type="checkbox"]')?.checked, 'The saved preference is shown');
  assert.equal(app.document.querySelector('h1')?.textContent, 'Settings');
  assert.equal(app.document.querySelector('a[href="#access-settings"]')?.getAttribute('aria-current'), 'page');
  assert.equal(app.calls.filter(call => call.options.method === 'POST').length, 0);
  app.document.querySelector('input[type="checkbox"]').click();
  await until(() => app.calls.some(call => call.path.endsWith('/account/access-automation') && call.options.method === 'POST'), 'The explicit choice reaches the SDK endpoint');
  const mutation = app.calls.find(call => call.path.endsWith('/account/access-automation') && call.options.method === 'POST');
  assert.deepEqual(JSON.parse(mutation.options.body), { automaticallyUpdatePartyAccess: false });
  assert.ok(new Headers(mutation.options.headers).get('Idempotency-Key'));
});

test('account mandate verification stays singular and enabled while agent details are opened and closed', async t => {
  const app = openConsole(t, {
    signedIn: true, info: () => json(user), route: 'settings/accounts',
    agents: [{ agentId: 'external-agent', kind: 'EXTERNAL', name: 'Connected agent', role: 'READ_ALL', status: 'ACTIVE', inclusion: false, revision: 1 }],
  });
  await until(() => app.document.querySelector('a[href="#settings/agents"]'), 'Settings navigation opens');
  app.document.querySelector('a[href="#settings/agents"]').click();
  await until(() => app.document.querySelector('.agent-expand'), 'The connected agent is listed');
  const controls = () => [...app.document.querySelectorAll('[aria-label="Mandate verification"] input[type="checkbox"]')];
  const expand = () => app.document.querySelector('.agent-expand').click();
  expand();
  await until(() => app.document.querySelector('.agent-settings') && controls().every(input => !input.disabled), 'Agent details and account preference load');
  assert.equal(controls().length, 1, 'An account-wide preference must have one control even inside agent details');
  controls()[0].click();
  await until(() => controls()[0].checked && !controls()[0].disabled, 'The account preference is enabled');
  expand();
  await until(() => !app.document.querySelector('.agent-settings'), 'Agent details close');
  expand();
  await until(() => app.document.querySelector('.agent-settings'), 'Agent details reopen');
  assert.equal(controls().length, 1);
  assert.equal(controls()[0].checked, true, 'Opening an agent must not show a conflicting disabled account preference');
  const writes = app.calls.filter(call => call.path === '/api/blue/v1/account/mandate-sharing' && call.options.method === 'POST');
  assert.equal(writes.length, 1);
  assert.equal(JSON.parse(writes[0].options.body).enabled, true);
});


test('unsupported participant automation explains availability without a broken preference or fake empty list', async t => {
  const app = openConsole(t, { signedIn: true, info: () => json(user), route: 'access-settings', accessUnavailable: true });
  await until(() => app.document.querySelector('.access-preference')?.textContent.includes('not available for this account yet'), 'The account capability is explained');
  assert.equal(app.document.querySelector('input[type="checkbox"]'), null);
  assert.equal(app.document.querySelector('[role="alert"]'), null);
  assert.equal(app.document.querySelector('#access-requests-title'), null);
  assert.equal(app.calls.filter(call => call.options.method === 'POST').length, 0);
});
