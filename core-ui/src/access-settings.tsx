import { useCallback, useEffect, useState } from 'react';
import { AccountApi, Configuration, type PartyAccessRequest } from '../../sdk/typescript/generated';
import { authenticatedFetch } from './transport';
import { RefreshCw } from 'lucide-react';
import './access-settings.css';

const account = new AccountApi(new Configuration({ basePath: '', fetchApi: (url, init) => authenticatedFetch(String(url), init) }));
const labels: Record<string, string> = {
  WAITING_FOR_APPROVAL: 'Waiting for your approval', WAITING_FOR_ACCESS: 'Waiting for document access',
  APPLIED: 'Access updated', SATISFIED: 'Access is ready', NEEDS_REVIEW: 'A new review is needed',
  RETRY: 'Retrying', RETRY_EXHAUSTED: 'Could not complete',
};

async function unavailable(error: unknown) {
  if (!(error && typeof error === 'object' && 'response' in error && error.response instanceof Response)) return false;
  const body = await error.response.clone().json().catch(() => ({}));
  return body.type === 'urn:myos:problem:access_automation_unavailable';
}

async function message(error: unknown) {
  if (error && typeof error === 'object' && 'response' in error && error.response instanceof Response) {
    const body = await error.response.clone().json().catch(() => ({}));
    if (body.detail) return String(body.detail);
  }
  return error instanceof Error ? error.message : 'Unable to update access. Please try again.';
}

export function AccessSettings() {
  const [enabled, setEnabled] = useState<boolean>();
  const [requests, setRequests] = useState<PartyAccessRequest[]>([]);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [unsupported, setUnsupported] = useState(false);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [settings, pending] = await Promise.all([account.getPartyAccessAutomation(), account.listPartyAccessRequests()]);
      setEnabled(settings.automaticallyUpdatePartyAccess); setRequests(pending.items); setError(''); setUnsupported(false);
    } catch (failure) {
      setEnabled(undefined); setRequests([]);
      const unsupported = await unavailable(failure);
      setUnsupported(unsupported); setError(unsupported ? '' : await message(failure));
    }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  const update = async (action: () => Promise<unknown>) => {
    setBusy(true); setError('');
    try { await action(); await load(); } catch (failure) { setError(await message(failure)); }
    finally { setBusy(false); }
  };
  return <div className="access-settings">
    <section className="panel panel-pad" aria-labelledby="participant-access-title">
      <h2 id="participant-access-title">Participant access</h2>
      <p className="access-description">Control how your account handles access requests from shared documents.</p>
      <p>When a shared document approves a new participant, MyOS can update access to the timelines this account owns. The participant joins only after the required permissions are verified.</p>
      {error && <p role="alert" className="error">{error}</p>}
      {unsupported ? <p role="status" className="access-preference">Participant access automation is not available for this account yet. Existing document permissions are unchanged.</p> : <div className="access-preference">
      <label className="access-toggle">
        <input type="checkbox" checked={enabled ?? false} disabled={busy || loading || enabled === undefined} aria-describedby="access-preference-help"
          onChange={event => { const value = event.target.checked; void update(() => account.updatePartyAccessAutomation({ idempotencyKey: crypto.randomUUID(), partyAccessAutomationSettings: { automaticallyUpdatePartyAccess: value } })); }} />
        Automatically apply approved participant access updates
      </label>
      <p id="access-preference-help">When disabled, review and approve each request below. This setting does not approve adding participants or change timelines owned by other accounts.</p>
      </div>}
    </section>
    {!unsupported && <section className="panel" aria-labelledby="access-requests-title" aria-busy={loading}>
      <div className="panel-heading access-requests-heading">
        <h2 id="access-requests-title">Access requests</h2>
        <button type="button" className="secondary" onClick={() => void load()} disabled={busy || loading}><RefreshCw size={16} aria-hidden="true" />Refresh</button>
      </div>
      <div className="panel-pad access-requests-body">
      {loading ? <p role="status">Loading access requests…</p> : enabled === undefined ? <p>Access requests could not be loaded. Refresh to try again.</p> : requests.length === 0 && <p>No access requests yet.</p>}
      {!loading && requests.map(request => <article className="access-request" key={request.id}>
        <strong>{labels[request.status] || request.status}</strong>
        <p>Acting as {request.party} in <a href={`#documents/${encodeURIComponent(request.sessionId)}`}>the shared document</a>.</p>
        {typeof request.problem?.detail === 'string' && <p>{request.problem.detail}</p>}
        <details><summary>Review requested access</summary><pre>{JSON.stringify(request.request, null, 2)}</pre></details>
        {['WAITING_FOR_APPROVAL', 'WAITING_FOR_ACCESS', 'RETRY'].includes(request.status) && <button type="button" className="primary" disabled={busy}
          onClick={() => void update(() => account.approvePartyAccessRequest({ requestId: request.id, idempotencyKey: crypto.randomUUID() }))}>Approve access update</button>}
      </article>)}
      </div>
    </section>}
  </div>;
}
