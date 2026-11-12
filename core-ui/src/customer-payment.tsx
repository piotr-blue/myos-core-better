import { useCallback, useEffect, useRef, useState } from 'react';
import { Configuration, DocumentsApi, ContentsApi, AdmissionsApi, TimelinesApi } from '../../sdk/typescript/generated';
import { submitReviewedOperation } from '../../sdk/typescript/helpers/operations';
import type { Document, Operation, TimelineResource } from '../../sdk/typescript/generated';
import { authenticatedFetch } from './transport';
import { PageHeader, CodeViewer } from './components/viewer';
import { ProcessOverview } from './process-views';
import profile from './process-profile.json';
import { projectBlueData } from '../../sdk/typescript/helpers/blue-data';
import { parseBlue } from './blue-values';

const configuration = new Configuration({ basePath: '', fetchApi: (url, init) => authenticatedFetch(String(url), init) });
const documents = new DocumentsApi(configuration);
const contents = new ContentsApi(configuration);
const admissions = new AdmissionsApi(configuration);
const timelines = new TimelinesApi(configuration);
type Review = { document: Document; state: any; operations: Operation[] };

export function CustomerPayment({ id, actionId, navigate }: { id: string; actionId: string; navigate: (path: string) => void }) {
  const [review, setReview] = useState<Review>();
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const pending = useRef<{ operation: string; request: any; review: Review; key: string } | undefined>(undefined);
  const load = useCallback(async (signal?: AbortSignal): Promise<Review> => {
    const document = await documents.getDocument({ sessionId: id }, { signal });
    if (!document.lastStable) throw new Error('The payment instruction is still initializing.');
    const response = await contents.getContentRaw({ blueId: document.lastStable.documentRef.blueId }, { signal });
    const state: any = projectBlueData(parseBlue(await response.raw.text()));
    const customerTypes = (profile as typeof profile & { customerPaymentTypes?: string[] }).customerPaymentTypes ?? [];
    if (!customerTypes.includes(state.type?.blueId) || state.customerSession?.id !== actionId) {
      throw new Error('This action does not match the selected customer payment instruction.');
    }
    const operations: Operation[] = [];
    let pageToken: string | undefined;
    do {
      const page = await documents.getDocumentOperations({ sessionId: id, limit: 100, pageToken }, { signal });
      operations.push(...page.items); pageToken = page.nextPageToken ?? undefined;
    } while (pageToken);
    // Operation routes describe the document, not this account's permission.
    // Only offer direct payer actions on timelines the account can append to.
    // Core still checks the native actor and policy on every submitted input.
    const owned: TimelineResource[] = [];
    pageToken = undefined;
    do {
      const page = await timelines.listTimelines({ limit: 100, pageToken }, { signal });
      owned.push(...page.items); pageToken = page.nextPageToken ?? undefined;
    } while (pageToken);
    const available = operations.map(operation => ({ ...operation, routes: operation.routes.filter(route =>
      owned.some(timeline => timeline.owned === true
        ? timeline.timelineId === route.timeline
        : timeline.owned !== false && timeline.blueId === route.timeline && timeline.actors?.includes(route.actor))) }));
    return { document, state, operations: available };
  }, [id, actionId]);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    setReview(undefined); setError(''); setMessage(''); pending.current = undefined;
    const refresh = async () => {
      try {
        const next = await load(controller.signal);
        if (!controller.signal.aborted) setReview(next);
      } catch (e: any) {
        if (!controller.signal.aborted) { setReview(undefined); setError(e.message); }
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(refresh, 2000);
      }
    };
    void refresh();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [load]);
  const invoke = async (operation: string, request: unknown) => {
    if (!review) return;
    setBusy(true); setError(''); setMessage('Submitting the reviewed action…');
    const attempt = pending.current ?? { operation, request, review, key: crypto.randomUUID() };
    pending.current = attempt;
    try {
      const payer = attempt.review.state.paymentTerms.payerChannel;
      const route = attempt.review.operations.find(item => item.name === attempt.operation)?.routes.find(route => route.channel === payer);
      if (!route) throw new Error('Your account has no payer authority for this operation.');
      const outcome = await submitReviewedOperation(documents, admissions, {
        sessionId: id, documentVersion: attempt.review.document.lastStable!.documentRef.blueId,
        operation: attempt.operation, request: attempt.request, route, idempotencyKey: attempt.key,
      });
      pending.current = undefined;
      const rejected: any = outcome.assessment ?? outcome.disposition;
      if (rejected) setError(`The instruction did not apply this action: ${rejected.reason ?? rejected.outcome}. Review the current state before trying again.`);
      else setMessage('The instruction processed your action. Confirmed amounts below update when the payment provider reports its outcome.');
      setReview(await load());
    } catch (e: any) {
      setError(`${e.message} Retry keeps the same reviewed request.`);
    } finally { setBusy(false); }
  };
  const state = review?.state;
  const permitted = (name: string) => review?.operations.some(item => item.name === name && item.routes.some(route => route.channel === state.paymentTerms.payerChannel));
  const expired = state && Number(state.customerSession.expiresAt) <= Date.now() * 1000;
  return <>
    <PageHeader title="Customer payment" back={() => navigate(`documents/${encodeURIComponent(id)}`)} />
    <section className="panel process-view">
      {error && <p className="error" role="alert">{error}</p>}
      {message && <p role="status">{message}</p>}
      {state ? <>
        <p className="notice">Local provider simulation. No real money moves in this demonstration.</p>
        <h2>{state.name}</h2>
        <p><strong>{String(state.paymentTerms.amountMinor)} minor units · {state.paymentTerms.currency}</strong></p>
        <p>Recipient: {state.paymentTerms.payeeChannel}. Payment is confirmed only by {state.paymentTerms.guarantorChannel}.</p>
        {state.businessContext && <CodeViewer value={state.businessContext} label="What this payment is for" compact />}
        {expired && !state.customerSession.requested && <p>This payment action has expired. Ask the merchant for a replacement payment instruction.</p>}
        {!expired && !state.customerSession.requested && permitted('requestCustomerFunding') && <button className="primary" disabled={busy || pending.current !== undefined} onClick={() => invoke('requestCustomerFunding', { sessionId: actionId })}>Continue to funding</button>}
        {state.customerSession.requested && !state.customerSession.funded && <p>Waiting for the payment provider to confirm funding.</p>}
        {state.customerSession.funded && state.payment.transactionId && permitted('authorize') && Number(state.payment.secured) === 0 && <button className="primary" disabled={busy || pending.current !== undefined} onClick={() => invoke('authorize', { requestId: `authorize-${actionId}`, amountMinor: state.paymentTerms.amountMinor })}>Authorize this amount</button>}
        {pending.current && !busy && <button onClick={() => invoke(pending.current!.operation, pending.current!.request)}>Retry pending request</button>}
        {!review.operations.some(item => item.routes.some(route => route.channel === state.paymentTerms.payerChannel)) && <p>Your account can inspect this instruction but has no payer operation available.</p>}
      </> : <p>Loading the verified payment instruction…</p>}
    </section>
    {state && <ProcessOverview value={state} />}
  </>;
}
