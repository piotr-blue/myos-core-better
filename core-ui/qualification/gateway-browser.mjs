// Opt-in full-process fixture. All API/SSE/session behavior comes from the console modules.
import assert from 'node:assert/strict';
import { readFile, writeFile, unlink, mkdir, rename } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { chromium } from 'playwright';

const input = process.argv[2];
const config = JSON.parse(await readFile(input, 'utf8'));
await unlink(input); // The disposable session cookie must not remain in evidence.
const root = dirname(input), ui = join(root, 'ui');
const output = join(root, 'browser-result.json');
let browser;
process.once('SIGTERM', () => { void (async () => { try { await browser?.close(); } finally { process.exit(143); } })(); });
const until = async (label, condition, seconds = 60) => {
  const deadline = Date.now() + seconds * 1000;
  while (!await condition()) {
    assert.ok(Date.now() < deadline, label);
    await new Promise(resolve => setTimeout(resolve, 50));
  }
};
const marker = async name => JSON.parse(await readFile(join(root, name + '.json'), 'utf8').catch(error => {
  if (error.code === 'ENOENT') return 'false';
  throw error;
}));
const record = async (name, value) => {
  const target = join(root, name + '.json');
  await writeFile(target + '.pending', JSON.stringify(value));
  await rename(target + '.pending', target);
};
try {
  await mkdir(ui, { recursive: true });
  await build({ stdin: { contents: `
    import { startLive, subscribeLive } from './src/live';
    import { api } from './src/api';
    import { setAccountContext, rememberAccount } from './src/account-context';
    import { serviceWaiting } from './src/service-recovery';
    import { accountApi, discoverAccounts } from './src/account-api';
    window.probe = { startLive, subscribeLive, api, accountApi, discoverAccounts, setAccountContext, rememberAccount, serviceWaiting };
  `, resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'ts' }, bundle: true,
    format: 'iife', platform: 'browser', outfile: join(ui, 'client.js'),
    define: { __MYOS_PORTAL_CONFIG__: JSON.stringify({ base: '', api: config.api, web: config.portal }) } });
  await writeFile(join(ui, 'index.html'), '<!doctype html><title>Core gateway qualification</title><script src="/client.js"></script>');
  browser = await chromium.launch({ channel: process.env.CORE_QUALIFICATION_BROWSER_CHANNEL || undefined,
    args: ['--host-resolver-rules=MAP *.example.test 127.0.0.1', '--no-proxy-server'] });
  const context = await browser.newContext({ ignoreHTTPSErrors: true }); // Fixture-only Caddy local CA.
  const origins = new Set([config.portal, config.api]);
  await context.route('**/*', route => origins.has(new URL(route.request().url()).origin) ? route.continue() : route.abort());
  await context.addCookies([{ name: '__Secure-myos_fixture', value: config.token, domain: '.api.example.test',
    path: '/', httpOnly: true, secure: true, sameSite: 'Lax' }]);
  config.token = undefined;
  const beforeCookies = await context.cookies();
  assert.equal(beforeCookies.length, 1);
  const page = await context.newPage();
  const network = [], startedAt = Date.now();
  let navigations = 0, phase = 'setup';
  page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigations++; });
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.origin === config.api) network.push({ elapsedMs: Date.now() - startedAt, phase, path: url.pathname, status: response.status() });
  });
  page.on('requestfailed', request => network.push({ elapsedMs: Date.now() - startedAt, phase,
    path: new URL(request.url()).pathname, failure: request.failure()?.errorText }));
  await page.goto(config.portal, { waitUntil: 'load' });
  const before = await page.evaluate(async ({ account, content }) => {
    const p = window.probe;
    const user = await p.accountApi({}).getMe();
    const membership = user.accounts.find(value => value.accountId === account).membership;
    p.rememberAccount(account, user.userId, membership.generation);
    const selected = await p.discoverAccounts();
    const identity = selected.account;
    p.setAccountContext(identity, selected.user);
    p.states = []; p.resets = []; p.sessionEvents = [];
    for (const event of ['myos-session-ended', 'myos-session-changed'])
      window.addEventListener(event, () => p.sessionEvents.push(event));
    p.unsubscribe = p.subscribeLive(change => { if (change.reset) p.resets.push({ atMs: Date.now(), sinceStartMs: performance.now(), source: p.latestState === "live" ? "stream" : "fallback" }); });
    p.stop = p.startLive(state => { p.latestState = state; p.states.push({ state, atMs: Date.now(), sinceStartMs: performance.now() }); });
    return { userId: identity.userId, context: identity.sessionContext, identity,
      content: await p.api('contents/' + encodeURIComponent(content)), storage: { ...sessionStorage } };
  }, config);
  await page.waitForFunction(() => window.probe.resets.filter(row => row.source === "stream").length >= 1);
  assert.equal(await page.evaluate(() => document.cookie.includes('__Secure-myos_fixture')), false);
  phase = 'controlled-retirement';
  await record('browser-ready', { live: true, cookieHiddenFromScript: true });
  await until('parent did not retire gateway', () => marker('gateway-retired'), 110);
  await page.evaluate(content => {
    const p = window.probe;
    p.pending = p.api('contents/' + encodeURIComponent(content));
    p.pending.catch(() => {});
  }, config.content);
  await page.waitForFunction(() => window.probe.serviceWaiting(), undefined, { timeout: 20000 });
  await record('browser-pending', { automaticServiceRecovery: true });
  await until('parent did not restart gateway', () => marker('gateway-restarted'));
  phase = 'after-restart';
  await page.waitForFunction(() => window.probe.latestState === "live" && window.probe.resets.filter(row => row.source === "stream").length >= 2, undefined, { timeout: 45000 });
  const after = await page.evaluate(async () => {
    const p = window.probe;
    const content = await p.pending;
    const identity = (await p.discoverAccounts()).account;
    return { content, identity, sessionEvents: p.sessionEvents, storage: { ...sessionStorage },
      states: p.states, resets: p.resets, latestState: p.latestState, serviceWaiting: p.serviceWaiting() };
  });
  assert.deepEqual(after.content, before.content);
  assert.equal(after.identity.userId, before.userId);
  assert.equal(after.identity.sessionContext, before.context);
  assert.deepEqual(after.storage, before.storage);
  assert.deepEqual(after.sessionEvents, []);
  assert.equal(after.serviceWaiting, false);
  assert.equal(after.latestState, "live");
  assert.deepEqual(await context.cookies(), beforeCookies);
  assert.equal(navigations, 1, 'Client must recover without reload or navigation');
  assert.equal(network.some(row => row.status === 401 || row.path.startsWith('/api/auth/')), false);
  phase = 'teardown';
  await page.evaluate(() => { window.probe.stop(); window.probe.unsubscribe(); });
  const restarted = await marker('gateway-restarted');
  const streamResets = after.resets.filter(row => row.source === 'stream');
  const disconnected = after.states.find(row => row.state === 'reconnecting');
  assert.ok(disconnected && streamResets.length >= 2);
  const result = { status: 'PASS', browser: browser.version(), sourceRevision: config.sourceRevision,
    sameHttpOnlyCookie: true, sameSessionContext: true, sameAccountSelection: true, logoutEvents: 0,
    manualReloads: 0, automaticLiveReconnect: true, automaticFiniteReadRecovery: true,
    durableContentReconciled: true, streamResetCount: streamResets.length, fallbackResetCount: after.resets.length - streamResets.length, network,
    reconnectObservedAfterParentReadyMs: streamResets[1].atMs - restarted.atMs,
    controlledOutageReconnectMs: streamResets[1].atMs - disconnected.atMs,
    resets: after.resets,
    states: after.states, scope: 'Actual console modules in a browser with HTTPS/CORS and a real gateway restart; '
      + 'fixture-seeded session, serial replacement, controlled outage. Not Cognito login, ALB or rollout timing. '
      + 'SSE reset/state reconciliation, not replay of every transient notification.' };
  await browser.close(); browser = undefined;
  await writeFile(output, JSON.stringify(result, null, 2) + '\n');
} catch (error) {
  await writeFile(output, JSON.stringify({ status: 'FAIL', error: String(error) }, null, 2) + '\n');
  throw error;
} finally {
  await browser?.close();
}
