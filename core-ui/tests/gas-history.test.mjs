import { test } from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
const compiled = (await build({ entryPoints: ['src/gas-history.ts'], bundle: true, write: false, format: 'esm', platform: 'node' })).outputFiles[0].text;
const { gasActivity, gasSnapshot, formatGas, signedGas } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
const snapshot = (credited, spent = '0', reserved = '0') => ({ credited, spent, reserved, available: String(BigInt(credited) - BigInt(spent) - BigInt(reserved)) });
const event = (id, object) => ({ id, sequence: 1, created: '2026-10-07T14:00:00Z', object });

test('a newly provisioned zero balance is explained as the opening entry', () => {
  const opening = gasActivity(event('opening', { ...snapshot('0'), before: snapshot('0'), causes: [] }));
  assert.equal(opening.title, 'Opening balance');
  assert.equal(opening.balanceChange, 0n);
});

test('large balances and one-unit changes retain exact precision', () => {
  const before = snapshot('1000000000000000000'), after = snapshot('1000000000000000001');
  const row = gasActivity(event('credit', { ...after, before }));
  assert.equal(formatGas(row.after.balance), '1,000,000,000,000,000,001');
  assert.equal(signedGas(row.balanceChange), '+1');
  assert.equal(row.title, 'Gas credited');
  assert.equal(gasSnapshot({ ...after, available: '1000000000000000000' }), null);
  assert.equal(formatGas(undefined), '—');
});

test('reserving 500 does not spend it and settling 150 returns only 350', () => {
  const opening = snapshot('1000'), held = snapshot('1000', '0', '500'), settled = snapshot('1000', '150');
  const reservation = gasActivity(event('hold', { ...held, before: opening }));
  assert.equal(reservation.balanceChange, 0n); assert.equal(reservation.availableChange, -500n);
  assert.equal(reservation.title, 'Gas reserved');
  const charge = gasActivity(event('charge', { ...settled, before: held }));
  assert.equal(charge.balanceChange, -150n); assert.equal(charge.availableChange, 350n);
  assert.equal(charge.after.balance, 850n); assert.match(charge.description, /350 unused gas/);
  assert.equal(signedGas(charge.balanceChange), '−150');
});

test('each event carries its own before-state so pagination never changes a delta', () => {
  const recorded = event('recorded', { ...snapshot('1000', '150'), before: snapshot('1000', '0', '500') });
  assert.equal(gasActivity(recorded).balanceChange, -150n);
  assert.equal(gasActivity(recorded).availableChange, 350n);
  assert.equal(gasActivity(event('incomplete', snapshot('1000'))).balanceChange, null, 'An incomplete response cannot imply a zero opening balance');
});

test('no-match and abandoned reservations remain zero charges, with recorded reasons', () => {
  const after = snapshot('1000'), before = snapshot('1000', '0', '500');
  const free = gasActivity(event('free', { ...after, before, causes: [{ kind: 'SETTLED', id: 'hold', instance: 'document', units: '500', debit: '0', usage: '23', returned: '500', disposition: 'NO_MATCH' }] }));
  assert.equal(free.title, 'Execution not charged'); assert.equal(free.balanceChange, 0n);
  assert.match(free.description, /no charge/); assert.match(free.description, /500 gas returned/);
  const release = gasActivity(event('release', { ...after, before, causes: [{ kind: 'RELEASED', id: 'hold', units: '500' }] }));
  assert.equal(release.title, 'Reservation released'); assert.equal(release.balanceChange, 0n);
  assert.match(release.description, /without a committed charge/);
});

test('credit evidence and failed execution reasons stay tied to the recorded cause', () => {
  const credit = gasActivity(event('credit', { ...snapshot('1000'), before: snapshot('0'), causes: [{ kind: 'CREDIT', id: 'topup', units: '1000', evidence: 'Manual test allocation' }] }));
  assert.match(credit.description, /Manual test allocation/);
  const failed = gasActivity(event('failure', { ...snapshot('1000', '150'), before: snapshot('1000', '0', '500'), causes: [{ kind: 'SETTLED', id: 'hold', instance: 'document', debit: '150', returned: '350', disposition: 'FAILED' }] }));
  assert.match(failed.description, /failed execution/); assert.equal(failed.balanceChange, -150n);
});
