// Real SDK intake qualification: every request is intercepted; nothing reaches Datadog.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { chromium } from 'playwright';
import { fileURLToPath, URL } from 'node:url';
import { unzipSync } from 'node:zlib';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const origin = 'https://dev.myos.blue';
const apiOrigin = 'https://core.api.dev.beta.myos.blue';
const apiRequests = [];
const secrets = ['private@example.test', 'SECRET_API_KEY_785', 'SECRET_TOKEN_345'];
const visible = ['VISIBLE_DOCUMENT_BODY_785', 'VISIBLE_FORM_VALUE_345', 'VISIBLE_ORDINARY_LINK_426'];
const revision = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
// Exercise the documented demo-disabled build without changing the shipping policy.
// The separate demo qualification exercises the unmodified DEV/PROD policy.
const standardConsentPolicy = {
  name: 'standard-consent-policy',
  setup(builder) {
    builder.onLoad({ filter: /[/\\]demo-policy\.mjs$/ }, async ({ path }) => {
      const source = await readFile(path, 'utf8');
      const flag = /const demoVisitorAnalytics = (?:true|false);/g;
      assert.equal(source.match(flag)?.length, 1, 'Bind the fixture to the documented demo switch');
      return { contents: source.replace(flag, 'const demoVisitorAnalytics = false;'), loader: 'js' };
    });
  },
};
const bundle = await build({ stdin: { contents: `
  import { browserObservability } from './observability/browser.mjs';
  import { saveAnalyticsPreference } from './observability/preferences.mjs';
  window.sdkLoads = 0;
  window.observability = browserObservability({applicationId:'12345678-1234-1234-1234-123456789abc',clientToken:'public-client-token-for-tests',site:'datadoghq.eu',service:'myos-core-console',env:'dev',version:${JSON.stringify(revision)},sessionSampleRate:100,sessionReplaySampleRate:100,defaultPrivacyLevel:window.fixtureReplayPrivacy}, async () => {
    window.sdkLoads++;
    const { datadogRum } = await import('@datadog/browser-rum');
    window.rum = datadogRum;
    return datadogRum;
  });
  import { Component, createElement } from 'react';
  import { createRoot } from 'react-dom/client';
  class FixtureBoundary extends Component {
    state = { failed: false };
    static getDerivedStateFromError() { return { failed: true }; }
    render() { return this.state.failed ? 'Fixture fallback' : this.props.children; }
  }
  function BrokenComponent() { throw new RangeError('private@example.test SECRET_API_KEY_785'); }
  window.triggerReactError = () => createRoot(document.getElementById('react-error'), {
    onCaughtError: window.observability.reportError,
  }).render(createElement(FixtureBoundary, null, createElement(BrokenComponent)));
  window.chooseAnalytics = saveAnalyticsPreference;
  document.querySelector('button').onclick = () => { setTimeout(() => { throw new TypeError('private@example.test SECRET_API_KEY_785'); }); };
`, resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'js' }, write: false, bundle: true, format: 'iife', plugins: [standardConsentPolicy] });
const payloads = [], errors = [];
const browser = await chromium.launch();
const started = Date.now();
try {
  const pages = {};
  for (const scenario of ['visitor', 'service', 'visible']) {
    const context = await browser.newContext();
    await context.addInitScript(level => { window.fixtureReplayPrivacy = level; }, scenario === 'visible' ? 'allow' : 'mask');
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.hostname === 'browser-intake-datadoghq.eu') {
        const buffer = request.postDataBuffer();
        if (buffer) {
          const type = request.headers()['content-type'] || '';
          if (type.startsWith('multipart/form-data')) {
            const form = await new globalThis.Response(buffer, { headers: { 'Content-Type': type } }).formData();
            for (const [key, part] of form) {
              const bytes = typeof part === 'string' ? Buffer.from(part) : Buffer.from(await part.arrayBuffer());
              payloads.push({ scenario, kind: key, text: key === 'segment' ? unzipSync(bytes).toString() : bytes.toString() });
            }
          } else payloads.push({ scenario, kind: 'rum', text: buffer.toString() });
        }
        return route.fulfill({ status: 202, headers: { 'Access-Control-Allow-Origin': origin }, body: '{}' });
      }
      if (url.origin === apiOrigin) {
        if (request.method() !== 'OPTIONS') apiRequests.push({ scenario, headers: await request.allHeaders() });
        return route.fulfill({ status: request.method() === 'OPTIONS' ? 204 : 200,
          headers: { 'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Headers': 'traceparent, tracestate', 'Access-Control-Allow-Credentials': 'true', 'Content-Type': 'application/json' },
          body: request.method() === 'OPTIONS' ? '' : '{}' });
      }
      if (url.origin !== origin) return route.abort();
      if (url.pathname === '/assets/index-test1234.js') return route.fulfill({ contentType: 'application/javascript', body: bundle.outputFiles[0].text });
      return route.fulfill({ contentType: 'text/html', headers: { 'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://browser-intake-datadoghq.eu https://core.api.dev.beta.myos.blue; worker-src 'self' blob:" }, body: `<!doctype html><title>Observability fixture</title><p>${visible[0]}</p><input type="text" value="${visible[1]}"><input type="email" value="${secrets[0]}"><input type="password" value="${secrets[2]}"><section data-dd-privacy="hidden"><p>${secrets[1]}</p><input type="text" value="${secrets[2]}"></section><a data-dd-privacy="hidden" href="/sign-in?token=${secrets[2]}">Private credential link</a><a href="/developers">${visible[2]}</a><div id="react-error"></div><button>Test error</button><script src="/assets/index-test1234.js"></script>` });
    });
    const page = pages[scenario] = await context.newPage();
    page.on('pageerror', error => errors.push(error.name));
    await page.goto(origin + '/developers/app/?email=' + secrets[0] + '#' + secrets[2]);
    await page.waitForFunction(() => globalThis.window.observability);
    assert.equal(await page.evaluate(() => globalThis.window.sdkLoads), 0, 'Anonymous entry must not load telemetry');
    assert.ok(!(await context.cookies()).some(cookie => cookie.name.startsWith('_dd')), 'Anonymous entry must not create Datadog cookies');
    await page.evaluate(mode => {
      const { observability, chooseAnalytics } = globalThis.window;
      if (mode === 'visitor') { observability.setPublicPage(true); chooseAnalytics(true); }
      else { chooseAnalytics(false); observability.setAuthenticated(true, Date.now() + 60000); }
    }, scenario);
    await page.waitForFunction(() => globalThis.window.rum?.getInternalContext()?.session_id);
    await page.evaluate(url => fetch(url, { credentials: 'include' }).then(response => { if (!response.ok) throw new Error('Fixture request failed'); }), apiOrigin + '/api/blue/v1/documents/opaque-document-id?token=' + secrets[2]);
    const observed = apiRequests.filter(request => request.scenario === scenario).at(-1);
    assert.ok(observed, 'The browser API request must actually run');
    assert.equal(Boolean(observed.headers.traceparent), scenario !== 'visitor', 'Only verified service mode injects trace context');
    assert.ok(!Object.keys(observed.headers).some(key => key === 'baggage' || key.startsWith('x-datadog-')));
    await page.evaluate(() => globalThis.window.triggerReactError());
    await page.getByRole('button').click();
  }
  // Three isolated scenarios share one real SDK batching wait, bounded to 45 seconds.
  const deadline = Date.now() + 45000;
  while (!(['service', 'visible'].every(scenario => payloads.some(p => p.scenario === scenario && p.kind === 'segment')) && ['visitor', 'service', 'visible'].every(scenario => payloads.some(p => p.scenario === scenario && p.kind === 'rum' && p.text.includes('"type":"error"'))))) {
    assert.ok(Date.now() < deadline, 'Missing real RUM error or replay intake');
    await new Promise(resolve => globalThis.setTimeout(resolve, 100));
  }
  assert.ok(!payloads.some(p => p.scenario === 'visitor' && p.kind === 'segment'), 'Public analytics must never start replay');
  for (const payload of payloads) for (const secret of secrets) assert.ok(!payload.text.includes(secret), `${payload.scenario} ${payload.kind} leaked seeded credential ${secrets.indexOf(secret)}`);
  for (const content of visible) {
    assert.ok(payloads.some(p => p.scenario === 'visible' && p.kind === 'segment' && p.text.includes(content)), 'Allow mode must record ordinary visible DOM and form values');
    assert.ok(!payloads.some(p => p.scenario === 'service' && p.kind === 'segment' && p.text.includes(content)), 'Mask mode must still hide ordinary content');
    assert.ok(!payloads.some(p => p.kind === 'rum' && p.text.includes(content)), 'DOM visibility must not attach content to RUM events');
  }
  for (const scenario of ['visitor', 'service']) {
    const rum = payloads.filter(p => p.scenario === scenario && p.kind === 'rum').map(p => p.text).join('\n');
    assert.ok(rum.includes(revision) && rum.includes('myos-core-console') && rum.includes('index-test1234.js'));
    assert.ok(rum.includes(`"collection":"${scenario}"`));
    const handled = rum.split('\n').filter(Boolean).map(line => JSON.parse(line)).filter(event => event.type === 'error' && event.error?.type === 'RangeError');
    assert.ok(handled.length > 0, 'React caught rendering errors must reach intake');
    assert.ok(handled.every(event => event.error.stack.includes('index-test1234.js:') && event.error.source === 'custom'), 'Handled errors need safe source locations for Error Tracking');
    assert.ok(!rum.includes('opaque-document-id'), 'RUM must redact document path IDs');
  }
  const traced = apiRequests.find(request => request.scenario === 'service').headers.traceparent;
  assert.match(traced, /^00-[a-f0-9]{32}-[a-f0-9]{16}-01$/);
  const [, traceId, spanId] = traced.split('-');
  const resourceEvents = payloads.filter(p => p.scenario === 'service' && p.kind === 'rum')
    .flatMap(p => p.text.split('\n').filter(Boolean).map(line => JSON.parse(line)))
    .filter(event => event.type === 'resource');
  assert.ok(resourceEvents.some(event => event._dd?.trace_id === BigInt('0x' + traceId).toString()
    && event._dd?.span_id === BigInt('0x' + spanId).toString()), 'The received RUM resource must carry the injected W3C IDs');
  assert.ok(errors.includes('TypeError'));

  await pages.service.evaluate(() => {
    history.pushState(null, '', '/developers/app/?email=private@example.test#documents/opaque-document-id');
    globalThis.window.observability.syncView();
  });
  await pages.service.evaluate(() => {
    const channel = new globalThis.BroadcastChannel('myos-session');
    channel.postMessage('signed-out'); channel.close();
  });
  await pages.service.waitForFunction(() => !globalThis.window.rum?.getInternalContext()?.session_id);
  await pages.visitor.evaluate(() => {
    history.pushState(null, '', '/sign-in?email=private@example.test');
    // Route effects may run later; the synchronous event guard must already reject this.
    globalThis.window.observability.reportError(new RangeError('AUTH_ROUTE_SHOULD_NOT_EXPORT'));
    globalThis.window.observability.setPublicPage(false);
  });
  await pages.visitor.waitForFunction(() => !globalThis.window.rum?.getInternalContext()?.session_id);
  await pages.visitor.evaluate(() => {
    history.pushState(null, '', '/developers/');
    globalThis.window.observability.setPublicPage(true);
  });
  await pages.visitor.waitForFunction(() => globalThis.window.rum?.getInternalContext()?.session_id);
  await pages.visitor.evaluate(() => globalThis.window.chooseAnalytics(false));
  await pages.visitor.waitForFunction(() => !globalThis.window.rum?.getInternalContext()?.session_id);
  await pages.service.evaluate(url => fetch(url, { credentials: 'include' }), apiOrigin + '/api/blue/v1/accounts');
  assert.ok(!apiRequests.filter(request => request.scenario === 'service').at(-1).headers.traceparent, 'Logout stops header injection');
  // Unloading flushes the SDK's final consent-withdrawal view without another batch wait.
  for (const page of Object.values(pages)) await page.goto('about:blank');
  const finalViews = scenario => payloads.filter(payload => payload.scenario === scenario && payload.kind === 'rum')
    .flatMap(payload => payload.text.split('\n').filter(Boolean).map(line => JSON.parse(line)))
    .filter(event => event.type === 'view' && event.session?.is_active === false);
  const stoppedDeadline = Date.now() + 5000;
  while (!['visitor', 'service'].every(scenario => finalViews(scenario).length > 0)) {
    assert.ok(Date.now() < stoppedDeadline, 'Missing final stopped-session view intake');
    await new Promise(resolve => globalThis.setTimeout(resolve, 25));
  }
  for (const scenario of ['visitor', 'service']) assert.ok(finalViews(scenario).every(event => event.context?.collection === scenario), 'Final views retain their eligible collection mode');
  const visitorEvents = payloads.filter(payload => payload.scenario === 'visitor' && payload.kind === 'rum')
    .flatMap(payload => payload.text.split('\n').filter(Boolean).map(line => JSON.parse(line)));
  assert.ok(visitorEvents.some(event => event.type === 'view' && event.view.url === origin + '/developers/'), 'Returning to an eligible route must resume manual views');
  assert.ok(visitorEvents.every(event => !event.view?.url.includes('/sign-in')), 'SPA auth navigation must create no view or event attributed to sign-in');
  assert.equal(visitorEvents.filter(event => event.type === 'error' && event.error?.type === 'RangeError').length, 1, 'The auth-route error is rejected before route effects');
  assert.ok(finalViews('service').some(event => event.view.name === '/developers/app/documents'), 'Authenticated hash routes use a bounded manual view family');
  for (const payload of payloads) for (const secret of secrets) assert.ok(!payload.text.includes(secret), `${payload.scenario} ${payload.kind} leaked seeded credential ${secrets.indexOf(secret)} after stop`);
  assert.ok(!payloads.some(payload => payload.scenario === 'visitor' && payload.kind === 'segment'), 'Withdrawal must not start visitor Replay');
  const testedFiles = {};
  for (const path of ["core-ui/observability/browser.mjs", "core-ui/observability/demo-policy.mjs", "core-ui/observability/lifecycle.mjs", "core-ui/observability/preferences.mjs", "core-ui/observability/privacy.mjs", "core-ui/qualification/rum-browser.mjs", "core-ui/package-lock.json"]) testedFiles[path] = createHash('sha256').update(await readFile(path)).digest('hex');
  const evidence = { status: 'PASS', policy: 'DEMO_DISABLED_BUILD', sourceRevision: revision, testedFiles, sdk: '7.15.0', elapsedMs: Date.now() - started, intakePayloads: payloads.length, replaySegments: payloads.filter(p => p.kind === 'segment').length, seededPrivacyAssertions: secrets.length, replayPrivacyModes: 'MASK_HIDES_CONTENT_ALLOW_SHOWS_ORDINARY_CONTENT_BOTH_EXCLUDE_CREDENTIALS', anonymousEntry: 'NO_SDK_OR_COOKIES', publicAnalytics: 'RUM_WITHOUT_REPLAY', service: 'RUM_AND_REPLAY_WITHOUT_ANALYTICS_CONSENT', logoutAndWithdrawal: 'SESSION_EXPIRED_FINAL_VIEWS_SANITIZED', browserTraceHeaders: 'AUTHENTICATED_CORE_API_ONLY', rumResourceTraceIds: 'MATCH_INJECTED_W3C_IDS', liveDatadogIntake: 'NOT_RUN' };
  await mkdir('reports/datadog-observability', { recursive: true });
  await writeFile('reports/datadog-observability/browser-sdk.json', JSON.stringify(evidence, null, 2) + '\n');
  console.log(JSON.stringify(evidence));
} finally { await browser.close(); }
