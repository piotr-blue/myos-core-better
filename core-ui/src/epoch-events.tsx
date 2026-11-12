import { useEffect, useState } from "react";
import { api } from "./api";
import { contentPath } from "./content-paths";
import { BlueId, CodeViewer } from "./components/viewer";

function EventContent({ blueId, receiptRef, first }: { blueId: string; receiptRef?: { blueId: string }; first: boolean }) {
  const [open, setOpen] = useState(first);
  const [content, setContent] = useState<any>();
  const [error, setError] = useState("");
  useEffect(() => {
    if (!open || content !== undefined) return;
    let alive = true;
    setError("");
    api(contentPath(blueId))
      .then(value => { if (alive) setContent(value); })
      .catch(e => { if (alive) setError(e.message); });
    return () => { alive = false; };
  }, [blueId, open, content]);
  return <details className="epoch-event" open={open} onToggle={e => setOpen(e.currentTarget.open)}>
    <summary><BlueId value={blueId} /></summary>
    {receiptRef && <p>Receipt: <BlueId value={receiptRef.blueId} /></p>}
    {open && (error ? <p role="alert">{error}</p> : content !== undefined
      ? <CodeViewer value={content} compact />
      : <p className="subtle">Loading event…</p>)}
  </details>;
}

export function EpochEvents({ id, epoch }: { id: string; epoch?: number }) {
  const [data, setData] = useState<any>();
  const [error, setError] = useState("");
  useEffect(() => {
    let alive = true;
    setData(undefined); setError("");
    if (epoch !== undefined) api(`documents/${encodeURIComponent(id)}/epochs/${epoch}/events`)
      .then(value => { if (alive) setData(value); })
      .catch(e => { if (alive) setError(e.message); });
    return () => { alive = false; };
  }, [id, epoch]);
  if (epoch === undefined) return null;
  const selected = data?.epoch === epoch && data?.sessionId === id ? data : undefined;
  return <div className="epoch-events" aria-label={`Events for epoch ${epoch}`}>
    {([['triggeredBy', 'Triggered by'], ['emitted', 'Emitted events']] as const).map(([field, title]) =>
      <section className="panel panel-pad" key={field}>
        <h3>{title}</h3>
        <p className="subtle">Epoch {epoch}{selected && field === 'emitted' ? ` · ${selected.emitted.length} events` : ''}</p>
        {error ? <p role="alert" className="error">{error}</p> : !selected ? <p className="subtle">Loading events…</p>
          : !selected[field].length ? <p className="subtle">{field === 'emitted' ? 'No events emitted for this epoch.' : selected.kind === 'INITIALIZE' ? 'Document initialization — no input event.' : 'No trigger event recorded for this epoch.'}</p>
          : selected[field].map((event: any, i: number) => {
              const reference = field === 'emitted' ? event.eventRef : event;
              return <EventContent key={`${id}/${epoch}/${field}/${i}/${reference.blueId}`} blueId={reference.blueId} receiptRef={event.receiptRef} first={i === 0} />;
            })}
      </section>)}
  </div>;
}
