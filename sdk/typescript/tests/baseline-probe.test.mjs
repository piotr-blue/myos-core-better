import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('../../../scripts/core-baseline-sdk-probe.mjs', import.meta.url));
const runtime = { language: 'current', supportedPackages: ['native'] };
const registrationSelection = { ...runtime, canonicalWireMeasurementV1: 'exact' };
const input = { origin: 'https://core.invalid', tokens: ['secret-one', 'secret-two'], runtime, registrationSelection };

const accounts = ['account-one', 'account-two'].map(accountId => ({ accountId, capabilities: { manageAccount: true } }));

function probe(info, header = 'projection', status = 200, selectedAccounts = accounts) {
  const loader = `let index = 0;
    globalThis.fetch = async (url, options) => {
      const account = Math.floor(index / 2), discovery = index++ % 2 === 0;
      if (url !== 'https://core.invalid/api/blue/v1/' + (discovery ? 'account' : 'info')
          || options.headers.Authorization !== 'Bearer ' + ['secret-one','secret-two'][account]
          || !(options.signal instanceof AbortSignal)) throw Error('SDK route, token or deadline differs');
      const body = discovery ? ${JSON.stringify(selectedAccounts)}[account] : ${JSON.stringify(info)};
      return new Response(JSON.stringify(body), {
        status: ${status}, headers: {'Content-Type':'application/json','X-MyOS-Read-Source':${JSON.stringify(header)}}});
    };`;
  return spawnSync(process.execPath, ['--import', `data:text/javascript,${encodeURIComponent(loader)}`, script],
    { input: JSON.stringify(input), encoding: 'utf8', timeout: 10000 });
}

test('generated SDK proves both projected current accounts and exact selection', () => {
  const result = probe({ runtime, registrationExecutionSelections: [registrationSelection] });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), { status: 'passed', accounts: 2,
    projectedCurrentRuntime: true, exactRegistrationSelection: true });
});

test('different credentials cannot qualify the same Account or non-Admin authority', () => {
  for (const selectedAccounts of [
    [accounts[0], accounts[0]],
    [accounts[0], { accountId: 'account-two', capabilities: { manageAccount: false } }],
    [{ capabilities: { manageAccount: true } }, accounts[1]],
  ]) {
    const result = probe({ runtime, registrationExecutionSelections: [registrationSelection] }, 'projection', 200, selectedAccounts);
    assert.equal(result.status, 1);
    assert.equal(result.stderr, '');
    assert.deepEqual(JSON.parse(result.stdout), { status: 'failed' });
  }
});

test('historical, empty, incorrect and direct-read responses cannot qualify', () => {
  for (const [info, header] of [
    [{ runtime: { ...runtime, language: 'historical' }, registrationExecutionSelections: [] }, 'projection'],
    [{ runtime, registrationExecutionSelections: [] }, 'projection'],
    [{ runtime, registrationExecutionSelections: [runtime] }, 'projection'],
    [{ runtime, registrationExecutionSelections: [registrationSelection] }, 'direct'],
  ]) {
    const result = probe(info, header);
    assert.equal(result.status, 1);
    assert.deepEqual(JSON.parse(result.stdout), { status: 'failed' });
  }
});

test('SDK failures never emit tokens or response bodies', () => {
  const result = probe({ private: 'secret-one' }, 'projection', 503);
  assert.equal(result.status, 1);
  assert.equal(result.stderr, '');
  assert.deepEqual(JSON.parse(result.stdout), { status: 'failed' });
});
