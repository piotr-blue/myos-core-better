import assert from 'node:assert/strict';
import test from 'node:test';
import { projectProcess } from '@blue-labs/myos-sdk';
const profile = { format: 'independent-process-view/1', roots: { pi: 'payment', agreement: 'agreement' }, clauses: { payment: 'Payment Clause' } };

test('projection separates requests, confirmation, and customer funding without inventing totals', () => {
  const state = { name: 'Service payment', type: { blueId: 'pi' }, paymentTerms: { amountMinor: 200, currency: 'USD' },
    payment: { secured: 200, completed: 0, reversed: 0, requests: { c: { outcome: 'unknown' } } }, customerSession: { funded: true } };
  const view = projectProcess(state, profile);
  assert.equal(view.facts.find(f => f.path === '/payment/completed').value, 0);
  assert.equal(view.facts.find(f => f.path === '/customerSession/funded').value, true);
  assert.equal(view.facts.find(f => f.path === '/payment/requests').value.c.outcome, 'unknown');
  assert.ok(!view.facts.some(f => /paid|net|balance/i.test(f.label)));
  assert.equal(projectProcess({ ...state, type: { blueId: 'lookalike' } }, profile), undefined);
});

test('exact clause identity is required before interpreting preparation state', () => {
  const state = { type: { blueId: 'agreement' }, clauses: { payment: { type: { blueId: 'payment' }, parameters: { template: 'retained' } }, fake: { type: { blueId: 'unreviewed' } } },
    process_payment: { entries: { one: { status: 'awaiting_provider', reason: 'party_consent', nextActions: [] } } },
    process_fake: { entries: { one: { status: 'paid' } } } };
  const view = projectProcess(state, profile);
  assert.equal(view.processes.length, 1);
  assert.equal(view.processes[0].status, 'awaiting_provider');
  assert.equal(view.clauses[0].configuration.parameters.template, 'retained');
  assert.equal(view.clauses[1].kind, 'Custom / unprofiled');
});
