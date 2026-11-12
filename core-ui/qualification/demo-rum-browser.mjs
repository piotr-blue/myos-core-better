// Real SDK qualification with all network requests intercepted locally.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { chromium } from 'playwright';
import { fileURLToPath } from 'node:url';

const bundle = await build({ stdin: { contents: `
  import { browserObservability } from './observability/browser.mjs';
  window.observability = browserObservability({applicationId:'12345678-1234-1234-1234-123456789abc',
    clientToken:'public-client-token-for-tests',site:'datadoghq.eu',service:'myos-core-web',env:window.fixtureEnv,
    version:'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',sessionSampleRate:100,
    sessionReplaySampleRate:location.pathname === '/previous-release' ? 0 : 100}, async () => {
    const { datadogRum } = await import('@datadog/browser-rum');
    window.rum = datadogRum; return datadogRum;
  });
  window.observability.setPublicPage(true);
`, resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'js' }, write: false, bundle: true, format: 'iife' });
const browser = await chromium.launch(process.env.CHROME_CHANNEL ? { channel: process.env.CHROME_CHANNEL } : {});
const results = [];
try {
  await Promise.all(['dev', 'prod'].flatMap(env => [null, false, true].flatMap(analytics => [false, true].map(async upgrade => {
    const origin = env === 'prod' ? 'https://myos.blue' : 'https://dev.beta.myos.blue';
    const requests = [];
    const context = await browser.newContext();
    const stored = analytics === null ? null : JSON.stringify({ analytics, expiresAt: Date.now() + 60000 });
    await context.addInitScript(({ stored, env }) => {
      window.fixtureEnv = env;
      if (stored !== null) localStorage.setItem('myos:privacy:v1', stored);
    }, { stored, env });
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.hostname === 'browser-intake-datadoghq.eu') {
        if (request.method() === 'POST') requests.push(url.pathname);
        return route.fulfill({ status: request.method() === 'OPTIONS' ? 204 : 202,
          headers: { 'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Headers': '*' }, body: '' });
      }
      if (url.origin !== origin) return route.abort();
      if (url.pathname === '/app.js') return route.fulfill({ contentType: 'application/javascript', body: bundle.outputFiles[0].text });
      return route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Demo RUM fixture</title><p>Public fixture</p><script src="/app.js"></script>' });
    });
    const page = await context.newPage();
    // Exercise fresh sessions and retained cookies from the no-replay release.
    let previousSession;
    if (upgrade) {
      await page.goto(origin + '/previous-release');
      await page.waitForFunction(() => window.rum?.getInternalContext()?.session_id);
      previousSession = await page.evaluate(() => window.rum.getInternalContext().session_id);
    }
    await page.goto(origin + '/');
    await page.waitForFunction(() => window.rum?.getInternalContext()?.session_id);
    if (upgrade) assert.equal(await page.evaluate(() => window.rum.getInternalContext().session_id), previousSession);
    const initial = await page.evaluate(() => ({
      replayRate: window.rum.getInitConfiguration().sessionReplaySampleRate,
      sampleRate: window.rum.getInitConfiguration().sessionSampleRate,
      preference: localStorage.getItem('myos:privacy:v1'),
    }));
    assert.equal(initial.replayRate, 100);
    assert.equal(initial.sampleRate, 100);
    assert.equal(initial.preference, stored);
    const deadline = Date.now() + 45000;
    while (!requests.some(path => path.includes('/rum'))) {
      assert.ok(Date.now() < deadline, 'Expected an actual RUM intake request');
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    if (env === 'prod') {
      while (!requests.some(path => path.includes('replay'))) {
        assert.ok(Date.now() < deadline, 'PROD public visit must send Replay before login');
        await new Promise(resolve => setTimeout(resolve, 100));
      }
    } else assert.ok(!requests.some(path => path.includes('replay')), 'DEV public visit must not send Replay');
    const publicReplayRequests = requests.filter(path => path.includes('replay')).length;
    await page.evaluate(() => {
      window.observability.setAuthenticated(true, Date.now() + 60000);
      document.querySelector('p').textContent = 'Authenticated fixture';
    });
    const replayDeadline = Date.now() + 45000;
    while (requests.filter(path => path.includes('replay')).length <= publicReplayRequests) {
      assert.ok(Date.now() < replayDeadline, 'Expected actual Replay intake after verified authentication');
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    await page.evaluate(() => window.observability.setAuthenticated(false));
    assert.equal(await page.evaluate(() => localStorage.getItem('myos:privacy:v1')), stored);
    results.push({ env, analytics, rumRequests: requests.filter(path => path.includes('/rum')).length,
      replayRequestsAfterLogin: requests.filter(path => path.includes('replay')).length - publicReplayRequests,
      publicReplayRequests, preferencePreserved: true, previousNoReplaySessionPreserved: upgrade });
    await context.close();
  }))));
  console.log(JSON.stringify({ status: 'PASS', realSdk: true, externalRequestsSent: 0, results }));
} finally { await browser.close(); }
