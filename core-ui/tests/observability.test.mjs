import assert from 'node:assert/strict';
import test from 'node:test';
import { rumBuildConfig } from '../observability/build-config.mjs';
import { beforeSend, safeUrl, safeViewName, safeStack, rumConfiguration } from '../observability/privacy.mjs';

const origin = 'https://dev.beta.myos.blue';
const input = { version: 'a'.repeat(40), origin, service: 'myos-core-console' };
const env = { MYOS_RUM_ENABLED: 'true', MYOS_RUM_ENV: 'dev', MYOS_RUM_APPLICATION_ID: '12345678-1234-1234-1234-123456789abc', MYOS_RUM_CLIENT_TOKEN: 'public-client-token-for-tests' };

test('local builds stay off; enabled builds bind environment, public credentials and actual revision', () => {
  assert.equal(rumBuildConfig({}, input), null);
  const config = rumBuildConfig(env, input);
  assert.equal(config.version, input.version);
  assert.equal(config.sessionReplaySampleRate, 100);
  assert.equal(config.defaultPrivacyLevel, 'mask');
  assert.equal(rumBuildConfig({ ...env, MYOS_RUM_ENV: 'prod' }, { ...input, origin: 'https://beta.myos.blue' }).sessionReplaySampleRate, 100);
  for (const bad of [{ ...input, version: 'latest' }, { ...input, origin: 'https://beta.myos.blue' }])
    assert.throws(() => rumBuildConfig(env, bad));
  assert.throws(() => rumBuildConfig({ ...env, MYOS_RUM_CLIENT_TOKEN: '' }, input));
  for (const level of ['mask', 'allow']) {
    const configured = rumBuildConfig({ ...env, MYOS_RUM_REPLAY_PRIVACY_LEVEL: level }, input);
    assert.equal(rumConfiguration(configured, origin).defaultPrivacyLevel, level);
    assert.equal(rumConfiguration(configured, origin).enablePrivacyForActionName, true);
  }
  for (const level of ['', 'mask-user-input', 'ALLOW']) {
    assert.throws(() => rumBuildConfig({ ...env, MYOS_RUM_REPLAY_PRIVACY_LEVEL: level }, input));
    assert.throws(() => rumConfiguration({ ...config, defaultPrivacyLevel: level }, origin));
  }
});

test('URL policy removes secrets, document IDs and untrusted hosts while retaining generated asset locations', () => {
  const secret = 'private-document-token';
  for (const value of [`${origin}/developers/app/#documents/${secret}`, `https://core.api.dev.beta.myos.blue/api/blue/v1/sessions/${secret}?email=private@example.com`, `https://${secret}.example.com/${secret}`, `https://${secret}@dev.beta.myos.blue/sign-in?code=${secret}`]) {
    assert.ok(!safeUrl(value, origin).includes(secret));
    assert.ok(!safeUrl(value, origin).includes('@'));
  }
  assert.equal(safeUrl(origin + '/assets/index-aB123.js?token=secret', origin), origin + '/assets/index-aB123.js');
  const chunk = origin + '/developers/app/chunks/browser-rum-A_b123.js';
  assert.equal(safeUrl(chunk + '?token=secret', origin), chunk);
  assert.equal(safeStack(`Error: secret\n    at dynamicImport (${chunk}:12:34)`, origin), `    at ${chunk}:12:34`);
  assert.ok(!safeUrl(origin + '/developers/app/chunks/private@example.test.js', origin).includes('private'));
  assert.equal(safeViewName(origin + '/developers/app/#documents/SECRET', origin), '/developers/app/documents');
  assert.equal(safeViewName(origin + '/developers/app/#SECRET', origin), '/developers/app/other');
});

test('event privacy preserves timing and source-map coordinates without application content', () => {
  const secret = 'private@example.com';
  const event = { type: 'error', context: { document: secret }, view: { url: origin + '/sign-in?email=' + secret, referrer: 'https://example.com/' + secret }, error: { type: 'TypeError', message: secret, stack: `TypeError: ${secret}\n    at secretFunction (${origin}/assets/index-abcd.js:12:34)\n${secret}` } };
  assert.equal(beforeSend(origin)(event), true);
  assert.ok(!JSON.stringify(event).includes(secret));
  assert.equal(event.error.stack, `TypeError\n    at ${origin}/assets/index-abcd.js:12:34`);
  assert.equal(beforeSend(origin)({ error: { type: secret } }), false);
  assert.equal(beforeSend(origin)({ error: { causes: [{ message: secret }] } }), false);
  const config = rumConfiguration(rumBuildConfig(env, input), origin);
  assert.equal(config.defaultPrivacyLevel, 'mask');
  assert.equal(config.trackViewsManually, true);
  assert.equal(config.propagateTraceBaggage, false);
  assert.equal(config.allowedTracingUrls[0].match('https://core.api.dev.beta.myos.blue/api/blue/v1/accounts'), false);
});

test('browser trace injection requires verified authentication and the exact Core API origin and route', () => {
  let authenticated = false;
  const config = rumConfiguration(rumBuildConfig(env, input), origin, () => authenticated);
  const match = config.allowedTracingUrls[0].match;
  const api = 'https://core.api.dev.beta.myos.blue';
  assert.equal(match(api + '/api/blue/v1/accounts'), false);
  authenticated = true;
  assert.equal(match(api + '/api/blue/v1/documents/private-id?query=private'), true);
  for (const url of [api + '.foreign.invalid/api/blue/v1/accounts', origin + '/api/blue/v1/accounts', api + '/api/auth/login', api + '/mcp', api + '/health', api + '/internal/fleet/control', api.replace('https:', 'http:') + '/api/blue/v1/accounts', api.replace('://', '://user:password@') + '/api/blue/v1/accounts'])
    assert.equal(match(url), false, url);
  authenticated = false;
  assert.equal(match(api + '/api/blue/v1/accounts'), false);
  assert.deepEqual(config.allowedTracingUrls[0].propagatorTypes, ['tracecontext']);
  assert.equal(config.propagateTraceBaggage, false);
});

for (const [envName, origins, api] of [
  ['dev', ['https://dev.myos.blue', 'https://dev.beta.myos.blue'], 'https://core.api.dev.beta.myos.blue'],
  ['prod', ['https://myos.blue', 'https://beta.myos.blue'], 'https://core.api.beta.myos.blue'],
]) {
  test(`${envName}: both frontend aliases retain the original API tracing and privacy boundary`, () => {
    for (const origin of origins) {
      const built = rumBuildConfig({ ...env, MYOS_RUM_ENV: envName }, { ...input, origin });
      const config = rumConfiguration(built, origin, () => true);
      assert.equal(config.allowedTracingUrls[0].match(api + '/api/blue/v1/accounts'), true);
      assert.equal(config.allowedTracingUrls[0].match('https://core.api.myos.blue/api/blue/v1/accounts'), false);
      assert.equal(safeUrl(api + '/api/blue/v1/documents/secret?token=secret', origin), api + '/api/blue/v1/documents/:id');
      assert.throws(() => rumBuildConfig({ ...env, MYOS_RUM_ENV: envName === 'dev' ? 'prod' : 'dev' }, { ...input, origin }));
    }
  });
}
