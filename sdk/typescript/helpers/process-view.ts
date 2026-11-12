import { projectBlueData } from './blue-data.js';
/** Exact candidate identities select deterministic views; this is presentation, not authority. */
export interface ProcessViewProfile {
  format: 'independent-process-view/1';
  roots: Record<string, 'agreement' | 'order' | 'document' | 'payment' | 'shipment'>;
  clauses: Record<string, string>;
}
export interface ProcessFact { label: string; path: string; value: unknown }
export interface ProcessView {
  profile: ProcessViewProfile['format'];
  kind: string;
  title: string;
  facts: ProcessFact[];
  clauses: { key: string; kind: string; configuration: unknown }[];
  processes: { clause: string; item: string; status: unknown; reason: unknown; nextActions: unknown[] }[];
}

function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
}
function typeId(value: unknown) { return object(object(value).type).blueId; }
function pointer(value: unknown, path: string): unknown {
  for (const key of path.slice(1).split('/')) value = object(value)[key];
  return value;
}

/**
 * Project only known native fields, with no inferred totals, permission decisions,
 * or success inferred from an attachment/action. Unknown types get no profile.
 * Callers must obtain the exact snapshot through the authenticated Core API.
 */
export function projectProcess(value: unknown, profile: ProcessViewProfile): ProcessView | undefined {
  if (profile.format !== 'independent-process-view/1') throw new Error('Unsupported process view profile');
  const state = object(projectBlueData(value)), id = typeId(value);
  const kind = typeof id === 'string' ? profile.roots[id] : undefined;
  if (!kind) return undefined;
  const facts: ProcessFact[] = [];
  const fact = (label: string, path: string) => {
    const value = pointer(state, path);
    if (value !== undefined) facts.push({ label, path, value });
  };
  fact('Activation status', '/activation/status');
  fact('Access ready', '/activation/accessReady');
  fact('Terms accepted', '/activation/termsAccepted');
  fact('Participation active', '/activation/active');
  fact('Business status', '/status');
  if (kind === 'payment') {
    fact('Amount (minor units)', '/paymentTerms/amountMinor');
    fact('Currency', '/paymentTerms/currency');
    fact('Payer role', '/paymentTerms/payerChannel');
    fact('Recipient role', '/paymentTerms/payeeChannel');
    fact('Confirmation role', '/paymentTerms/guarantorChannel');
    fact('Confirmation basis', '/paymentTerms/evidence');
    fact('Instruction accepted', '/payment/accepted');
    fact('Secured (minor units)', '/payment/secured');
    fact('Completed (minor units)', '/payment/completed');
    fact('Reversed (minor units)', '/payment/reversed');
    fact('Customer funding confirmed', '/customerSession/funded');
    fact('Payment requests and outcomes', '/payment/requests');
  }
  if (kind === 'shipment') {
    fact('Shipment status', '/shipment/status');
    fact('Shipment requests and outcomes', '/shipment/requests');
  }
  fact('Inspection result', '/inspection');
  fact('Committed amount (minor units)', '/committedMinor');
  const clauses = Object.entries(object(state.clauses)).map(([key, clause]) => {
    const id = typeId(clause);
    return { key, kind: typeof id === 'string' ? profile.clauses[id] ?? 'Custom / unprofiled' : 'Custom / unprofiled',
      configuration: clause };
  });
  const processes: ProcessView['processes'] = [];
  for (const clause of clauses) {
    if (!['Document Clause', 'Documents Clause', 'Payment Clause', 'Payments Clause', 'Shipment Clause', 'Inspection Clause'].includes(clause.kind)) continue;
    const entries = object(object(state[`process_${clause.key}`]).entries);
    for (const [item, raw] of Object.entries(entries)) {
      const entry = object(raw);
      processes.push({ clause: clause.key, item: typeof entry.key === 'string' ? entry.key : item, status: entry.status, reason: entry.reason,
        nextActions: Array.isArray(entry.nextActions) ? entry.nextActions : [] });
    }
  }
  return { profile: profile.format, kind, title: typeof state.name === 'string' ? state.name : kind,
    facts, clauses, processes };
}
