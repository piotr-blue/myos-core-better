import { projectBlueData } from '../../sdk/typescript/helpers/blue-data';
import { projectProcess, type ProcessViewProfile } from '../../sdk/typescript/helpers/process-view';
import profiles from './process-profile.json';
import { CodeViewer } from './components/viewer';

export function processView(value: unknown) {
  return projectProcess(value, profiles as ProcessViewProfile);
}

export function ProcessOverview({ value, sessionId }: { value: unknown; sessionId?: string }) {
  const view = processView(value);
  const state: any = projectBlueData(value);
  if (!view) return <p className="empty">This exact document type has no business view profile. Use State to inspect its native content.</p>;
  return <div className="process-view">
    {sessionId && view.kind === 'payment' && state?.customerSession?.id && <p><a href={`#customer-payment/${encodeURIComponent(sessionId)}?action=${encodeURIComponent(state.customerSession.id)}`}>Open customer payment action</a></p>}
    {state?.activation?.accessReady === false && <p role="status">Requests are paused while required access is unavailable. Changes to the embedded access documents can restore access; existing acceptance is retained.</p>}
    {state?.activation?.accessReady === true && state?.activation?.termsAccepted === false && <p role="status">Access is ready. The required parties still need to accept the terms before business operations can run.</p>}
    <p className="muted">Facts from this document’s selected stable version. Requested actions and confirmed outcomes are shown separately.</p>
    <dl className="metadata-grid">{view.facts.map(fact => <div key={fact.path}><dt>{fact.label}</dt><dd>{typeof fact.value === 'object'
      ? <CodeViewer value={fact.value} label={fact.label} compact /> : String(fact.value)}</dd></div>)}</dl>
    {view.processes.length > 0 && <section><h3>Connected processes</h3>{view.processes.map(process => <article className="panel" key={`${process.clause}/${process.item}`}>
      <strong>{process.clause} · {process.item}</strong><p>{String(process.status ?? 'Status unavailable')}</p>
      {process.reason != null && <CodeViewer value={process.reason} label="Waiting reason" compact />}
      {process.nextActions.length > 0 && <CodeViewer value={process.nextActions} label="Requested next actions" compact />}
    </article>)}</section>}
  </div>;
}

export function ProcessTerms({ value }: { value: unknown }) {
  const view = processView(value);
  if (!view) return <p className="empty">Use Source and State to inspect this document’s terms.</p>;
  return <div className="process-view"><p className="muted">Effective clause configuration at the selected version. Custom actions are shown as code; their presence alone does not grant permission.</p>
    {view.clauses.map(clause => <details key={clause.key} className="panel"><summary><strong>{clause.key}</strong> · {clause.kind}</summary>
      <CodeViewer value={clause.configuration} label={`${clause.key} configuration`} compact />
    </details>)}
  </div>;
}
