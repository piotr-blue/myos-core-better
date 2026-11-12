import assert from 'node:assert/strict';
import test from 'node:test';
import { createRumLifecycle } from '../observability/lifecycle.mjs';
import { readAnalyticsPreference } from '../observability/preferences.mjs';
import { beforeSend } from '../observability/privacy.mjs';

const signedOut = { authenticated: false, publicPage: false, analytics: null };
function fixture(configuration = {}, allowCurrentPage) {
  const calls = [];
  let initialized;
  const sdk = Object.fromEntries(['addError', 'startView', 'stopSessionReplayRecording', 'startSessionReplayRecording', 'setTrackingConsent'].map(name => [name, value => calls.push([name, value])]));
  sdk.init = config => { initialized = config; };
  const lifecycle = createRumLifecycle(async () => { calls.push(['load']); return sdk; }, configuration, allowCurrentPage);
  return { lifecycle, calls, initialized: () => initialized };
}
test('anonymous visits do not load the SDK; public analytics cannot start replay', async () => {
  const { lifecycle, calls, initialized } = fixture();
  await lifecycle.update(signedOut);
  await lifecycle.update({ ...signedOut, publicPage: true });
  assert.deepEqual(calls, []);
  await lifecycle.update({ ...signedOut, publicPage: true, analytics: true });
  assert.equal(initialized().trackingConsent, 'not-granted');
  assert.equal(initialized().startSessionReplayRecordingManually, true);
  assert.ok(calls.some(([name, value]) => name === 'setTrackingConsent' && value === 'granted'));
  assert.ok(!calls.some(([name]) => name === 'startSessionReplayRecording'));
  await lifecycle.update({ ...signedOut, analytics: true });
  assert.deepEqual(calls.at(-1), ['setTrackingConsent', 'not-granted']);
});
test('verified service use records independently of optional analytics, and logout revokes it', async () => {
  const { lifecycle, calls, initialized } = fixture();
  await lifecycle.update({ ...signedOut, authenticated: true, analytics: false });
  assert.deepEqual(calls.at(-1), ['startSessionReplayRecording', undefined]);
  const serviceCalls = [...calls];
  await lifecycle.update({ ...signedOut, authenticated: true, analytics: true });
  await lifecycle.update({ ...signedOut, authenticated: true, analytics: false });
  assert.deepEqual(calls, serviceCalls, 'Changing public analytics must not stop or restart service recording');
  const event = { context: { collection: 'service' } };
  assert.equal(initialized().beforeSend(event), true);
  assert.deepEqual(event.context, { collection: 'service' });
  await lifecycle.update(signedOut);
  assert.deepEqual(calls.slice(-2), [['stopSessionReplayRecording', undefined], ['setTrackingConsent', 'not-granted']]);
  assert.equal(initialized().beforeSend({}), false);
});
for (const env of ['dev', 'prod']) test(`${env}: demo collects public RUM for every banner choice; public replay is PROD only`, async () => {
  for (const analytics of [null, false, true]) {
    const { lifecycle, calls, initialized } = fixture({ env, sessionSampleRate: 100, sessionReplaySampleRate: 100 });
    await lifecycle.update({ ...signedOut, publicPage: true, analytics });
    assert.equal(initialized().sessionSampleRate, 100);
    assert.equal(initialized().sessionReplaySampleRate, 100);
    assert.equal(initialized().startSessionReplayRecordingManually, true);
    assert.ok(calls.some(([name, value]) => name === 'setTrackingConsent' && value === 'granted'));
    assert.equal(calls.some(([name]) => name === 'startSessionReplayRecording'), env === 'prod');
    if (env === 'prod') assert.deepEqual(calls.at(-1), ['startSessionReplayRecording', { force: true }]);
    await lifecycle.update({ ...signedOut, authenticated: true, analytics });
    assert.deepEqual(calls.at(-1), ['startSessionReplayRecording', { force: true }]);
    const replayStarts = calls.filter(([name]) => name === 'startSessionReplayRecording').length;
    await lifecycle.update({ ...signedOut, authenticated: true, analytics });
    assert.equal(calls.filter(([name]) => name === 'startSessionReplayRecording').length, replayStarts);
    await lifecycle.update({ ...signedOut, publicPage: true, analytics });
    assert.deepEqual(calls.slice(env === 'prod' ? -3 : -2), [
      ['stopSessionReplayRecording', undefined], ['setTrackingConsent', 'granted'],
      ...(env === 'prod' ? [['startSessionReplayRecording', { force: true }]] : []),
    ]);
    await lifecycle.update(signedOut);
    assert.deepEqual(calls.at(-1), ['setTrackingConsent', 'not-granted']);
  }
});
test('logout while the SDK loads cannot start a stale authenticated recording', async () => {
  let ready;
  const calls = [];
  const sdk = { init: config => calls.push(config.trackingConsent), stopSessionReplayRecording: () => calls.push('stop'), setTrackingConsent: value => calls.push(value), startSessionReplayRecording: () => calls.push('replay') };
  const lifecycle = createRumLifecycle(() => new Promise(resolve => { ready = resolve; }), {});
  const pending = lifecycle.update({ ...signedOut, authenticated: true });
  await lifecycle.update(signedOut);
  ready(sdk);
  await pending;
  assert.deepEqual(calls, ['not-granted']);
});
test('final views stay sanitized after logout or analytics withdrawal', async () => {
  const origin = 'https://dev.beta.myos.blue';
  for (const collection of ['service', 'visitor']) {
    const { lifecycle, initialized } = fixture({ beforeSend: beforeSend(origin) });
    await lifecycle.update({ authenticated: collection === 'service', publicPage: true, analytics: collection === 'visitor' });
    await lifecycle.update(signedOut);
    const event = { type: 'view', view: { url: origin + '/developers/app/?email=private@example.test#SECRET', referrer: origin + '/?token=SECRET' }, context: { collection, document: 'SECRET' } };
    assert.equal(initialized().beforeSend(event), true, 'The SDK cannot discard its final view');
    assert.deepEqual(event.context, { collection });
    assert.ok(!JSON.stringify(event).match(/private|SECRET|\?|#/));
    assert.equal(initialized().beforeSend({ type: 'error' }), false, 'New events remain disabled');
  }
});
test('manual views follow eligible routes and auth-route events are rejected before an effect updates eligibility', async () => {
  let allowed = true;
  const { lifecycle, calls, initialized } = fixture({}, () => allowed);
  const service = { ...signedOut, authenticated: true };
  const view = { key: '/documents/opaque-id', name: '/documents/:id' };
  await lifecycle.update({ ...service, view });
  await lifecycle.update({ ...service, view });
  assert.deepEqual(calls.filter(([name]) => name === 'startView'), [['startView', { name: '/documents/:id', context: { collection: 'service' } }]]);
  await lifecycle.update({ ...service, view: { key: '/accounts', name: '/accounts' } });
  assert.deepEqual(calls.at(-1), ['startView', { name: '/accounts', context: { collection: 'service' } }]);
  allowed = false;
  assert.equal(initialized().beforeSend({ type: 'resource' }), false);
  assert.equal(initialized().beforeSend({ type: 'error' }), false);
  await lifecycle.update({ ...signedOut, view: { key: '/sign-in', name: '/sign-in' } });
  assert.equal(calls.filter(([name]) => name === 'startView').length, 2);
});
test('a prior view keeps its collection after the page switches between visitor and service', async () => {
  const { lifecycle, initialized } = fixture({ beforeSend: beforeSend('https://dev.beta.myos.blue') });
  for (const collection of ['visitor', 'service']) {
    await lifecycle.update({ ...signedOut, authenticated: collection === 'visitor', publicPage: true, analytics: true,
      view: { key: '/same-page', name: '/same-page' } });
    const oldView = { type: 'view', context: { collection, private: 'SECRET' } };
    initialized().beforeSend(oldView);
    assert.deepEqual(oldView.context, { collection });
  }
});
test('missing, corrupted, expired and unavailable preference storage defaults to no choice', () => {
  for (const value of [null, '{', '{"analytics":true}', '{"analytics":"true","expiresAt":100}', '{"analytics":true,"expiresAt":1}'])
    assert.equal(readAnalyticsPreference({ getItem: () => value }, 2), null);
  assert.equal(readAnalyticsPreference({ getItem: () => { throw new Error(); } }), null);
  assert.equal(readAnalyticsPreference({ getItem: () => '{"analytics":false,"expiresAt":100}' }, 2), false);
  assert.equal(readAnalyticsPreference({ getItem: () => '{"analytics":true,"expiresAt":100}' }, 2), true);
});

test('React errors obey eligibility and never load or queue a signed-out error', async () => {
  const { lifecycle, calls } = fixture();
  const error = new TypeError('private fixture content');
  lifecycle.reportError(error);
  assert.deepEqual(calls, []);
  await lifecycle.update({ ...signedOut, authenticated: true });
  lifecycle.reportError(error);
  assert.deepEqual(calls.at(-1), ['addError', error]);
  await lifecycle.update(signedOut);
  const count = calls.length;
  lifecycle.reportError(error);
  assert.equal(calls.length, count);
});
