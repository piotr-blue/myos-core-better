import { impersonation } from "./impersonation-state";
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { ChevronDown, Copy, ExternalLink, Play, Square, Terminal, X } from "lucide-react";
import { PageHeader } from "./components/viewer";
import { Dialog } from "./components/dialog";
import { ExplorerDocs } from "./explorer-docs";
import {
  loadContract, getOperations, getExamples, formatValue, parameterKey, swaggerHref,
  type ExplorerOperation, type OpenApiSpec,
} from "./explorer-contract";
import {
  buildRequest, executeRequest, copyCurl, executeStream, formatResponse, admissionFrom, admissionRequest,
  type RequestDraft, type PreparedRequest, type ExplorerResponse, type SseFrame,
} from "./explorer-request";
import { parseBlue } from "./blue-values";
import "./explorer.css";

export function filterOperations(operations: ExplorerOperation[], query: string) {
  const words = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
  return operations.filter(op => words.every(word => `${op.method} ${op.path} ${op.tag} ${op.summary} ${op.operationId}`.toLowerCase().includes(word)))
    .sort((a, b) => a.tag.localeCompare(b.tag));
}
export function retainStreamFrame(frames: SseFrame[], frame: SseFrame) { return [...frames, frame].slice(-100); }
function Method({ method }: { method: string }) { return <span className="explorer-method" data-method={method}>{method}</span>; }
function OperationPicker({ operations, selected, onSelect }: { operations: ExplorerOperation[]; selected: ExplorerOperation; onSelect: (op: ExplorerOperation) => void }) {
  const [open, setOpen] = useState(false), [query, setQuery] = useState(""), [active, setActive] = useState(0);
  const ref = useRef<HTMLDivElement>(null), search = useRef<HTMLInputElement>(null), trigger = useRef<HTMLButtonElement>(null);
  const filtered = useMemo(() => filterOperations(operations, query), [operations, query]);
  useEffect(() => { if (open) search.current?.focus(); }, [open]);
  useEffect(() => { if (open) document.getElementById(`explorer-option-${active}`)?.scrollIntoView({ block: "nearest" }); }, [active, open]);
  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => { if (!ref.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close); return () => document.removeEventListener("mousedown", close);
  }, [open]);
  const choose = (op: ExplorerOperation) => { onSelect(op); setOpen(false); trigger.current?.focus(); };
  const keydown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Escape") { event.preventDefault(); setOpen(false); trigger.current?.focus(); }
    else if (event.key === "ArrowDown") { event.preventDefault(); setActive(a => Math.min(a + 1, filtered.length - 1)); }
    else if (event.key === "ArrowUp") { event.preventDefault(); setActive(a => Math.max(0, a - 1)); }
    else if (event.key === "Home") { event.preventDefault(); setActive(0); }
    else if (event.key === "End") { event.preventDefault(); setActive(filtered.length - 1); }
    else if (event.key === "Enter" && filtered[active]) { event.preventDefault(); choose(filtered[active]); }
    else if (event.key === "Tab") setOpen(false);
  };
  return <div className="explorer-operation" ref={ref}>
    <button type="button" ref={trigger} className="explorer-operation-trigger" aria-haspopup="listbox" aria-expanded={open} aria-label={`Choose operation: ${selected.method} ${selected.path}`}
      onClick={() => { setQuery(""); setActive(0); setOpen(!open); }}><Method method={selected.method} /><code>{selected.path}</code><ChevronDown size={16} /></button>
    {open && <div className="explorer-operation-menu">
      <input ref={search} role="combobox" aria-label="Search operations" aria-expanded="true" aria-controls="explorer-operations" aria-autocomplete="list"
        aria-activedescendant={filtered[active] ? `explorer-option-${active}` : undefined} value={query} placeholder="Search path, method or operation…" onKeyDown={keydown}
        onChange={event => { setQuery(event.target.value); setActive(0); }} />
      <div className="explorer-operation-list" role="listbox" id="explorer-operations" aria-label="API operations">
        {filtered.map((op, index) => <div key={op.key}>
          {(index === 0 || filtered[index - 1].tag !== op.tag) && <div className="explorer-operation-group" aria-hidden="true">{op.tag}</div>}
          <div id={`explorer-option-${index}`} role="option" aria-selected={op.key === selected.key} className="explorer-operation-option" data-active={active === index}
            onMouseDown={event => event.preventDefault()} onMouseEnter={() => setActive(index)} onClick={() => choose(op)}><Method method={op.method} /><code>{op.path}</code><small>{op.summary}</small></div>
        </div>)}
        {!filtered.length && <p className="explorer-hint">No matching operations.</p>}
      </div>
    </div>}
  </div>;
}
function ResponseView({ response }: { response: ExplorerResponse }) {
  const [raw, setRaw] = useState(false), [copied, setCopied] = useState(false);
  const media = response.headers["content-type"] || "";
  const display = raw ? response.rawBody : formatResponse(response.rawBody, media);
  return <>
    <div className="explorer-response-meta"><strong data-error={response.status >= 400}>{response.status} {response.statusText}</strong><span>{Math.round(response.elapsedMs)} ms</span><span>{response.bytes.toLocaleString()} bytes</span></div>
    <div className="explorer-response-label">{response.method} {response.url}</div>
    <div className="explorer-section-title" style={{ padding: "14px 24px", margin: 0 }}>
      <label className="explorer-inline"><input type="checkbox" style={{ width: "auto" }} checked={raw} onChange={e => setRaw(e.target.checked)} /> <span className="explorer-hint" style={{ margin: 0 }}>Raw body</span></label>
      <button type="button" className="explorer-text-button" onClick={async () => { try { await navigator.clipboard.writeText(response.rawBody); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch { setCopied(false); } }}><Copy size={12} /> {copied ? "Copied" : "Copy body"}</button>
    </div>
    <pre className="explorer-code">{display || "(empty response body)"}</pre>
    <details className="explorer-response-headers"><summary>Response headers ({Object.keys(response.headers).length})</summary><dl>{Object.entries(response.headers).map(([name, value]) => <div key={name} style={{ display: "contents" }}><dt>{name}</dt><dd>{value}</dd></div>)}</dl></details>
  </>;
}
function ResourcePicker({ kind, loadPage, onPick, onClose }: { kind: "sessionId" | "timelineId"; loadPage: (token?: string, signal?: AbortSignal) => Promise<any>; onPick: (id: string) => void; onClose: () => void }) {
  const [page, setPage] = useState<any>(), [token, setToken] = useState<string>(), [busy, setBusy] = useState(true), [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController(); setBusy(true); setError("");
    loadPage(token, controller.signal).then(setPage).catch(e => { if (!controller.signal.aborted) setError(e.message); }).finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [token, loadPage]);
  return <Dialog title={kind === "sessionId" ? "Choose an existing document" : "Choose an existing timeline"} onClose={onClose}>
    <div className="panel-pad"><p className="explorer-hint">Resources available with the selected authentication.</p>
      {error && <p role="alert" className="error">{error}</p>}
      {busy ? <p role="status">Loading resources…</p> : <div className="explorer-resource-list">{(page?.items || []).map((item: any) => <button type="button" key={String(item[kind])} onClick={() => onPick(String(item[kind]))}><code>{String(item[kind])}</code><small>{item.name || item.document?.name || item.channel || item.status || ""}</small></button>)}{!page?.items?.length && <p>No resources found.</p>}</div>}
      <div className="explorer-inline"><button type="button" className="explorer-secondary" disabled={!token || busy} onClick={() => setToken(undefined)}>First page</button><button type="button" className="explorer-secondary" disabled={busy || !page?.nextPageToken || page?.hasMore === false} onClick={() => setToken(String(page.nextPageToken))}>Next page</button></div>
    </div>
  </Dialog>;
}
const emptyDraft = (): RequestDraft => ({ parameters: {}, body: "", sendBody: false, mediaType: "application/json", accept: "application/json", auth: { mode: "session" }, headers: {} });
export function Explorer() {
  const [spec, setSpec] = useState<OpenApiSpec>(), [loadError, setLoadError] = useState(""), [reload, setReload] = useState(0), [operationKey, setOperationKey] = useState("GET /api/blue/v1/documents");
  const [draft, setDraft] = useState<RequestDraft>(emptyDraft), [headerRows, setHeaderRows] = useState<{ name: string; value: string }[]>([]), [dirty, setDirty] = useState(false);
  const [docs, setDocs] = useState(false), [exampleKey, setExampleKey] = useState(""), [showExamples, setShowExamples] = useState(false), [sampleKey, setSampleKey] = useState("");
  const [response, setResponse] = useState<ExplorerResponse>(), [error, setError] = useState(""), [busy, setBusy] = useState(false), [responseTab, setResponseTab] = useState("actual"), [copied, setCopied] = useState(false);
  const [resource, setResource] = useState<"sessionId" | "timelineId">(), [tracking, setTracking] = useState(false), [tracked, setTracked] = useState<ExplorerResponse>(), [trackingNote, setTrackingNote] = useState("");
  const [frames, setFrames] = useState<SseFrame[]>([]), [streamNote, setStreamNote] = useState("");
  const [requestNote, setRequestNote] = useState("");
  const [confirmation, setConfirmation] = useState<{ title: string; message: string; action: string; apply: () => void }>();
  const requestController = useRef<AbortController | undefined>(undefined), trackingController = useRef<AbortController | undefined>(undefined), lastRequest = useRef<PreparedRequest | undefined>(undefined);
  useEffect(() => {
    const controller = new AbortController(); setLoadError("");
    loadContract(controller.signal).then(setSpec).catch(e => { if (!controller.signal.aborted) setLoadError(e.message); });
    return () => controller.abort();
  }, [reload]);
  useEffect(() => () => { requestController.current?.abort(); requestController.current = undefined; trackingController.current?.abort(); trackingController.current = undefined; }, []);
  const operations = useMemo(() => spec ? getOperations(spec) : [], [spec]);
  const operation = operations.find(op => op.key === operationKey) || operations[0];
  const isStream = !!operation && Object.values(operation.responses).some(value => value.content?.["text/event-stream"]);
  const contentTypes = Object.keys(operation?.requestBody?.content || {});
  const examples = spec && operation ? getExamples(spec, operation.requestBody?.content?.[draft.mediaType]) : [];
  const example = examples.find(value => value.key === exampleKey) || examples[0];
  const samples = useMemo(() => {
    if (!spec || !operation) return [];
    return Object.entries(operation.responses).flatMap(([status, entry]) => Object.entries(entry.content || {}).flatMap(([media, content]) => getExamples(spec, content).map(value => ({ ...value, key: `${status}:${media}:${value.key}`, status, media }))));
  }, [spec, operation]);
  const sample = samples.find(value => value.key === sampleKey) || samples[0];
  const update = (patch: Partial<RequestDraft>) => { setDraft(current => ({ ...current, ...patch })); setDirty(true); };
  const headers = Object.fromEntries(headerRows.filter(row => row.name.trim()).map(row => [row.name.trim(), row.value]));
  const currentDraft = { ...draft, headers, accept: isStream ? "text/event-stream" : draft.accept };
  const resourceLoader = useMemo(() => {
    const context = { ...draft, body: "", sendBody: false, headers, accept: "application/json" };
    return async (token?: string, signal?: AbortSignal) => {
      const target = operations.find(op => op.key === `GET /api/blue/v1/${resource === "sessionId" ? "documents" : "timelines"}`);
      if (!target) throw new Error("Resource listing is not available in the served contract.");
      const result = await executeRequest(buildRequest(target, { ...context, parameters: { "query:limit": "25", ...(token ? { "query:pageToken": token } : {}) } }), signal);
      if (result.status >= 400) throw new Error(`Resource listing failed (${result.status}): ${result.rawBody}`);
      return parseBlue(result.rawBody);
    };
  }, [resource, operations, draft.auth.mode, draft.auth.token, JSON.stringify(headers)]);
  const changeOperation = (next: ExplorerOperation) => {
    requestController.current?.abort(); requestController.current = undefined; trackingController.current?.abort(); trackingController.current = undefined; setBusy(false); setTracking(false);
    lastRequest.current = undefined; setResponse(undefined);
    setOperationKey(next.key); setDraft(current => ({ ...current, parameters: {}, body: "", sendBody: !!next.requestBody?.required, mediaType: Object.keys(next.requestBody?.content || {})[0] || "application/json" }));
    setDirty(false); setError(""); setExampleKey(""); setShowExamples(false); setSampleKey(""); setFrames([]); setStreamNote(""); setRequestNote(""); setTracked(undefined); setTrackingNote("");
  };
  const selectOperation = (next: ExplorerOperation) => {
    if (next.key === operation?.key) return;
    if (dirty) setConfirmation({ title: "Discard request edits", message: `Change to ${next.method} ${next.path}? Current parameter and body edits will be discarded.`, action: "Change operation", apply: () => changeOperation(next) });
    else changeOperation(next);
  };
  const loadExample = () => {
    if (!example) return;
    const body = formatValue(example.value, draft.mediaType);
    const mediaType = draft.mediaType;
    const apply = () => update({ body, mediaType, sendBody: true });
    if (dirty) setConfirmation({ title: "Replace edited request", message: `Replace the request body with “${example.name}”? Your current body edits will be discarded.`, action: "Replace example", apply });
    else apply();
  };
  const send = async (retry?: PreparedRequest) => {
    if (!operation || busy) return;
    setError(""); setTracked(undefined); setTrackingNote(""); trackingController.current?.abort(); setTracking(false); setResponseTab("actual");
    let prepared: PreparedRequest;
    try { prepared = retry || buildRequest(operation, currentDraft); } catch (e: any) { setError(e.message); return; }
    lastRequest.current = prepared; const controller = new AbortController(); requestController.current = controller;
    setBusy(true); setResponse(undefined); setFrames([]); setStreamNote(""); setRequestNote("");
    try {
      if (isStream) {
        setStreamNote("Connecting…");
        await executeStream(prepared, controller.signal, result => { if (requestController.current !== controller || controller.signal.aborted) return; setResponse(result); setStreamNote(result.status < 400 ? "Connected · retaining the last 100 frames" : "Stream request failed"); }, frame => { if (requestController.current === controller && !controller.signal.aborted) setFrames(current => retainStreamFrame(current, frame)); });
        if (requestController.current === controller && !controller.signal.aborted) setStreamNote("Stream closed.");
      } else { const result = await executeRequest(prepared, controller.signal); if (requestController.current === controller && !controller.signal.aborted) setResponse(result); }
    } catch (e: any) {
      if (requestController.current !== controller) return;
      if (controller.signal.aborted) {
        if (isStream) setStreamNote("Stream stopped.");
        else setRequestNote("Request cancelled. The server may still have processed it. Retry same request preserves the original request and any idempotency key.");
      } else setError(e.message);
    }
    finally {
      if (requestController.current === controller) {
        setBusy(false);
        if (controller.signal.aborted) {
          if (isStream) setStreamNote("Stream stopped.");
          else setRequestNote("Request cancelled. The server may still have processed it. Retry same request preserves the original request and any idempotency key.");
        }
      }
    }
  };
  const track = async () => {
    const admission = spec && operation && response && admissionFrom(spec, operation, response);
    const readOperation = operations.find(op => op.key === "GET /api/blue/v1/admissions/{admissionId}");
    if (!spec || !admission || !readOperation || !lastRequest.current) return;
    const controller = new AbortController(); trackingController.current = controller; setTracking(true); setTrackingNote("Checking every 2 seconds, for up to 120 seconds.");
    const deadline = Date.now() + 120000;
    const timeout = setTimeout(() => { controller.abort(); if (trackingController.current === controller) setTrackingNote("Tracking paused after 120 seconds. The admission may still be processing."); }, 120000);
    try {
      while (!controller.signal.aborted && Date.now() < deadline) {
        const result = await executeRequest(admissionRequest(lastRequest.current, admission.admissionId), controller.signal); if (controller.signal.aborted) break; setTracked(result);
        if (result.status >= 400) { setTrackingNote(`Tracking stopped: HTTP ${result.status}.`); break; }
        const state = admissionFrom(spec, readOperation, result)?.status;
        if (state && state !== "PENDING") { setTrackingNote(`Admission ${state.toLowerCase()}.`); break; }
        await new Promise<void>(resolve => { const timer = setTimeout(done, 2000); function done() { clearTimeout(timer); controller.signal.removeEventListener("abort", done); resolve(); } controller.signal.addEventListener("abort", done, { once: true }); });
      }
      if (Date.now() >= deadline) setTrackingNote("Tracking paused after 120 seconds. The admission may still be processing.");
    } catch (e: any) { if (!controller.signal.aborted) setTrackingNote(`Tracking stopped: ${e.message}`); }
    finally { clearTimeout(timeout); if (trackingController.current === controller) setTracking(false); }
  };
  const stopTracking = () => { trackingController.current?.abort(); setTracking(false); setTrackingNote("Tracking stopped."); };
  const admission = spec && operation && response ? admissionFrom(spec, operation, response) : undefined;
  if (!spec || !operation) return <div className="explorer-page"><PageHeader title="API Explorer" />{loadError ? <div role="alert" className="explorer-error">{loadError} <button type="button" onClick={() => setReload(value => value + 1)}>Retry loading contract</button></div> : <p role="status">Loading the server’s API contract…</p>}</div>;
  return <div className="explorer-page">
    <PageHeader title="API Explorer" />
    <div className="explorer-intro"><p>Build a request. Inspect the real response.</p><a href={swaggerHref(operation)} target={impersonation() ? undefined : "_blank"} rel="noreferrer">Open Swagger <ExternalLink size={13} /></a></div>
    <div className="explorer-grid">
      <section className="panel" aria-label="Request builder" data-dd-privacy="hidden">
        <div className="panel-heading"><span className="panel-title">Request</span><span className="explorer-hint" style={{ margin: 0 }}>OpenAPI {spec.openapi}</span></div>
        <div className="explorer-auth-context" aria-label="Current request context"><span>{draft.auth.mode === "session" ? "Signed-in session" : "Bearer token"}</span></div>
        <div className="explorer-section"><OperationPicker operations={operations} selected={operation} onSelect={selectOperation} /><p className="explorer-operation-summary">{operation.summary}</p><button type="button" className="explorer-text-button" onClick={() => setDocs(true)}>View documentation</button></div>
        <form onSubmit={event => { event.preventDefault(); void send(); }}>
          {!!operation.parameters.filter(p => p.in === "path" || p.in === "query").length && <div className="explorer-section"><h3>Parameters</h3><div className="explorer-parameters">{operation.parameters.filter(p => p.in === "path" || p.in === "query").map(parameter => <label className="field" data-resource={["sessionId", "timelineId"].includes(parameter.name)} key={parameterKey(parameter)}>
            <span className="explorer-parameter-label"><span>{parameter.name}{parameter.required && <span className="explorer-required"> *</span>}</span><small>{parameter.in} · {parameter.schema?.type || "string"}</small></span>
            <div className="explorer-inline">{parameter.schema?.enum ? <select aria-label={parameter.name} required={parameter.required} value={draft.parameters[parameterKey(parameter)] || ""} onChange={e => update({ parameters: { ...draft.parameters, [parameterKey(parameter)]: e.target.value } })}><option value="">{parameter.required ? "Select a value" : "Not set"}</option>{parameter.schema.enum.map((value: any) => <option key={String(value)}>{String(value)}</option>)}</select> : <input aria-label={parameter.name} required={parameter.required} value={draft.parameters[parameterKey(parameter)] || ""} placeholder={parameter.schema?.default !== undefined ? `Default: ${parameter.schema.default}` : parameter.name} onChange={e => update({ parameters: { ...draft.parameters, [parameterKey(parameter)]: e.target.value } })} />}
              {["sessionId", "timelineId"].includes(parameter.name) && <button type="button" className="explorer-secondary" onClick={() => setResource(parameter.name)}>Choose existing</button>}</div>
            {parameter.description && <span className="form-note">{parameter.description.split("\n")[0]}</span>}
          </label>)}</div></div>}
          {operation.requestBody && <div className="explorer-section">
            <div className="explorer-section-title"><h3>Request body{operation.requestBody.required && <span className="explorer-required"> *</span>}</h3><select className="explorer-format" aria-label="Request content type" value={draft.mediaType} onChange={e => { update({ mediaType: e.target.value }); setExampleKey(""); }}>{contentTypes.map(media => <option key={media}>{media}</option>)}</select></div>
            {!operation.requestBody.required && <label className="explorer-inline" style={{ marginBottom: 12, fontSize: 13 }}><input type="checkbox" style={{ width: "auto" }} checked={draft.sendBody} onChange={e => update({ sendBody: e.target.checked })} />Include optional body</label>}
            {(draft.sendBody || operation.requestBody.required) && <textarea className="explorer-editor" aria-label="Request body" spellCheck={false} value={draft.body} placeholder={draft.mediaType.includes("yaml") ? "Enter YAML request body" : "Enter JSON request body"} onChange={e => update({ body: e.target.value })} />}
            {!!examples.length && <><button type="button" className="explorer-text-button" style={{ marginTop: 12 }} aria-expanded={showExamples} onClick={() => setShowExamples(!showExamples)}>{showExamples ? "Hide" : "Browse"} request examples ({examples.length})</button>{showExamples && example && <div className="explorer-examples-preview"><label className="field">Example<select value={example.key} onChange={e => setExampleKey(e.target.value)}>{examples.map(value => <option key={value.key} value={value.key}>{value.name}</option>)}</select></label>{example.description && <p className="explorer-hint">{example.description}</p>}<pre className="explorer-code">{formatValue(example.value, draft.mediaType)}</pre><button type="button" className="explorer-secondary" onClick={loadExample}>Load example</button><p className="explorer-hint">Preview only until loaded. Replace example resource IDs with your own.</p></div>}</>}
          </div>}
          <details className="explorer-section explorer-advanced"><summary>Headers and advanced options</summary>
            <label className="field">Authentication<select value={draft.auth.mode} onChange={e => update({ auth: { mode: e.target.value as "session" | "bearer", token: "" } })}><option value="session">Signed-in console session</option><option value="bearer">Bearer token (memory only)</option></select></label>
            {draft.auth.mode === "bearer" && <label className="field">Bearer token<input type="password" autoComplete="off" value={draft.auth.token || ""} onChange={e => update({ auth: { mode: "bearer", token: e.target.value } })} /><span className="form-note">Kept in this page’s memory. Never saved to browser storage.</span></label>}
            {!isStream && <label className="field">Accept<select value={draft.accept} onChange={e => update({ accept: e.target.value })}><option>application/json</option><option>application/yaml</option><option>text/plain</option><option>*/*</option></select></label>}
            {operation.parameters.filter(parameter => parameter.in === "header" && !["x-myos-client"].includes(parameter.name.toLowerCase())).map(parameter => <label className="field" key={parameterKey(parameter)}>{parameter.name}{parameter.required ? " *" : ""}<input value={draft.parameters[parameterKey(parameter)] || ""} placeholder={parameter.name.toLowerCase() === "idempotency-key" ? "Automatically generated per new request" : "Not set"} onChange={e => update({ parameters: { ...draft.parameters, [parameterKey(parameter)]: e.target.value } })} /></label>)}
            <h3>Additional headers</h3>{headerRows.map((row, index) => <div className="explorer-header-row" key={index}><input aria-label={`Header ${index + 1} name`} placeholder="Header name" value={row.name} onChange={e => { setHeaderRows(rows => rows.map((value, i) => i === index ? { ...value, name: e.target.value } : value)); setDirty(true); }} /><input aria-label={`Header ${index + 1} value`} placeholder="Value" value={row.value} onChange={e => { setHeaderRows(rows => rows.map((value, i) => i === index ? { ...value, value: e.target.value } : value)); setDirty(true); }} /><button type="button" aria-label={`Remove header ${index + 1}`} onClick={() => { setHeaderRows(rows => rows.filter((_, i) => i !== index)); setDirty(true); }}><X size={15} /></button></div>)}<button type="button" className="explorer-text-button" onClick={() => setHeaderRows(rows => [...rows, { name: "", value: "" }])}>Add header</button>
          </details>
          {error && <div role="alert" className="explorer-error">{error}</div>}
          <div className="explorer-actions"><button type="submit" className="primary" disabled={busy}><Play size={14} />{busy ? (isStream ? "Streaming…" : "Sending…") : isStream ? "Start stream" : "Send request"}</button>{busy && <button type="button" className="explorer-secondary" onClick={() => requestController.current?.abort()}><Square size={12} />Stop</button>}<button type="button" className="explorer-secondary" onClick={async () => { try { const prepared = buildRequest(operation, currentDraft); await navigator.clipboard.writeText(copyCurl(prepared)); setCopied(true); setTimeout(() => setCopied(false), 1500); setError(""); } catch (e: any) { setError(e.message); } }}><Copy size={13} />{copied ? "Copied" : "Copy cURL"}</button>{!busy && (error || requestNote || (response && response.status >= 400)) && lastRequest.current && <button type="button" className="explorer-text-button" onClick={() => void send(lastRequest.current)}>Retry same request</button>}</div>
        </form>
      </section>
      <section className="panel" aria-label="Response inspector" data-dd-privacy="hidden"><div className="panel-heading"><span className="panel-title">Response</span><span className="explorer-hint" style={{ margin: 0 }}>{busy ? "Request in progress" : response ? "Actual exchange" : "Ready when you are"}</span></div>
        <div className="explorer-response-tabs" role="tablist" aria-label="Response source" onKeyDown={event => { if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) { event.preventDefault(); const next = event.key === "Home" ? "actual" : event.key === "End" ? "samples" : responseTab === "actual" ? "samples" : "actual"; setResponseTab(next); document.getElementById(`explorer-${next}-tab`)?.focus(); } }}><button type="button" role="tab" tabIndex={responseTab === "actual" ? 0 : -1} id="explorer-actual-tab" aria-selected={responseTab === "actual"} aria-controls="explorer-actual-panel" onClick={() => setResponseTab("actual")}>Actual response</button><button type="button" role="tab" tabIndex={responseTab === "samples" ? 0 : -1} id="explorer-samples-tab" aria-selected={responseTab === "samples"} aria-controls="explorer-samples-panel" onClick={() => setResponseTab("samples")}>Contract examples</button></div>
        {responseTab === "actual" ? <div role="tabpanel" id="explorer-actual-panel" aria-labelledby="explorer-actual-tab">
          {requestNote && <div className="explorer-section"><p role="status" className="explorer-hint">{requestNote}</p></div>}
          {response ? <ResponseView response={response} /> : <div className="explorer-empty"><Terminal size={32} /><strong>{busy ? "Waiting for the server…" : "Your response will appear here"}</strong><p>Send a request to inspect its status, timing, headers and full response body.</p></div>}
          {isStream && (streamNote || frames.length > 0) && <div><div className="explorer-section"><h3>Event stream</h3><p role="status" className="explorer-hint">{streamNote} · {frames.length} retained frames</p></div>{frames.map((frame, index) => <div className="explorer-stream-frame" key={index}><small>{frame.event || "message"}{frame.id ? ` · ID ${frame.id}` : ""}</small><pre>{frame.raw || frame.data}</pre></div>)}</div>}
          {admission && <div className="explorer-admission"><div className="explorer-section"><div className="explorer-section-title"><h3>Admission result</h3><button type="button" className="explorer-secondary" onClick={tracking ? stopTracking : () => void track()}>{tracking ? "Stop tracking" : "Track admission"}</button></div><p className="explorer-hint">{admission.admissionId}</p><p className="explorer-hint">The original {response.status} response stays above. Tracking only reads the admission.</p>{trackingNote && <p role="status" className="explorer-hint">{trackingNote}</p>}</div>{tracked && <ResponseView response={tracked} />}</div>}
        </div> : <div role="tabpanel" id="explorer-samples-panel" aria-labelledby="explorer-samples-tab">{sample ? <><div className="explorer-sample-picker"><label className="field">Response example<select value={sample.key} onChange={e => setSampleKey(e.target.value)}>{samples.map(value => <option key={value.key} value={value.key}>{value.status} · {value.name} · {value.media}</option>)}</select></label><p className="explorer-hint">Contract sample, not a live response. {sample.description}</p></div><pre className="explorer-code">{formatValue(sample.value, sample.media)}</pre></> : <div className="explorer-empty"><strong>No response examples</strong><p>This operation’s schemas and response descriptions are in its documentation.</p></div>}</div>}
      </section>
    </div>
    <div className="explorer-notice">Requests run against this environment using the selected authentication. Writes change real resources. Sending is explicit; requests are never automatically retried. Copied cURL uses a token placeholder.</div>
    {confirmation && <Dialog title={confirmation.title} onClose={() => setConfirmation(undefined)}>
      <div className="panel-pad"><p>{confirmation.message}</p><div className="explorer-confirm-actions">
        <button type="button" className="explorer-secondary" onClick={() => setConfirmation(undefined)}>Cancel</button>
        <button type="button" className="primary" onClick={() => { const apply = confirmation.apply; setConfirmation(undefined); apply(); }}>{confirmation.action}</button>
      </div></div>
    </Dialog>}
    {docs && <ExplorerDocs key={operation.key} spec={spec} operation={operation} onClose={() => setDocs(false)} />}
    {resource && <ResourcePicker kind={resource} loadPage={resourceLoader} onClose={() => setResource(undefined)} onPick={id => { const parameter = operation.parameters.find(p => p.name === resource); if (parameter) update({ parameters: { ...draft.parameters, [parameterKey(parameter)]: id } }); setResource(undefined); }} />}
  </div>;
}
