import { useEffect, useId, useRef, useState } from "react";
import { X } from "lucide-react";
import "./explorer-docs.css";
import { resolveLocal, formatValue, type ExplorerOperation, type OpenApiSpec } from "./explorer-contract";

type RecordValue = Record<string, any>;
const object = (value: unknown): RecordValue => value && typeof value === "object" && !Array.isArray(value) ? value as RecordValue : {};

/** Resolve a reference chain, but never eagerly walk recursive schema properties. */
export function resolveDocReference(spec: unknown, input: unknown): { value: RecordValue; error?: string } {
  try {
    const value = object(resolveLocal(spec as OpenApiSpec, input));
    return typeof value.$ref === "string" ? { value, error: `Circular reference: ${value.$ref}` } : { value };
  } catch (error) {
    return { value: object(input), error: error instanceof Error ? error.message : "Cannot resolve reference." };
  }
}
const pretty = (value: unknown) => formatValue(value);


function inlineText(text: string) {
  return text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g).map((part, index) => part.startsWith("`") ? <code key={index}>{part.slice(1, -1)}</code> : part.startsWith("**") ? <strong key={index}>{part.slice(2, -2)}</strong> : part);
}

/** A small presentation subset: all content remains React text, never HTML. */
export function DocDescription({ text }: { text: unknown }) {
  const cleaned = String(text || "").replace(/<!--[\s\S]*?-->/g, "").trim();
  return <div className="explorer-doc-description">{cleaned.split(/(```[^\n]*\n[\s\S]*?```)/g).map((part, index) => {
    if (part.startsWith("```")) return <pre key={index}><code>{part.replace(/^```[^\n]*\n/, "").replace(/```$/, "").trimEnd()}</code></pre>;
    return part.split(/\n\s*\n/).filter(block => block.trim()).map((block, blockIndex) => {
      const lines = block.trim().split("\n");
      const key = `${index}:${blockIndex}`;
      if (lines.every(line => /^\s*[-*] /.test(line))) return <ul key={key}>{lines.map((line, lineIndex) => <li key={lineIndex}>{inlineText(line.replace(/^\s*[-*] /, ""))}</li>)}</ul>;
      if (lines.every(line => /^\s*\d+\. /.test(line))) return <ol key={key}>{lines.map((line, lineIndex) => <li key={lineIndex}>{inlineText(line.replace(/^\s*\d+\. /, ""))}</li>)}</ol>;
      return <div key={key}>{lines.map((line, lineIndex) => /^#{1,6} /.test(line) ? <h4 key={lineIndex}>{inlineText(line.replace(/^#{1,6} /, ""))}</h4> : <p key={lineIndex}>{inlineText(line)}</p>)}</div>;
    });
  })}</div>;
}

export function OperationOverview({ spec, operation }: { spec: unknown; operation: ExplorerOperation }) {
  const op = operation.operation;
  const security = op.security ?? object(spec).security;
  const responses = Object.entries(operation.responses);
  const schemes = object(object(object(spec).components).securitySchemes);
  return <section><h3>Overview</h3>
    <p>{op.summary || "No summary is documented."}</p>
    {op.operationId && <p>Operation ID: <code>{op.operationId}</code></p>}
    {op.deprecated && <p>This operation is deprecated.</p>}
    <h4>Authentication</h4>
    {Array.isArray(security) && security.length ? <><p>Use one of these authentication options:</p><ul>{security.map((requirement: RecordValue, index: number) => <li key={index}>{Object.keys(requirement).length === 0 ? "No authentication is required." : Object.entries(requirement).map(([name, scopes], schemeIndex) => {
      const scheme = resolveDocReference(spec, schemes[name]);
      return <span key={name}>{schemeIndex > 0 && " and "}<strong>{name}</strong>{scheme.error ? ` — ${scheme.error}` : scheme.value.type === "http" ? ` (${scheme.value.scheme || "HTTP"})` : scheme.value.type === "apiKey" ? ` (${scheme.value.in}: ${scheme.value.name})` : scheme.value.type ? ` (${scheme.value.type})` : ""}{Array.isArray(scopes) && scopes.length > 0 && ` · scopes: ${scopes.join(", ")}`}</span>;
    })}</li>)}</ul></> : <p>No authentication is required.</p>}
    <h4>Parameters</h4>
    {operation.parameters.length ? <div className="explorer-doc-table-wrap"><table className="explorer-doc-table"><thead><tr><th>Name</th><th>Location</th><th>Type</th><th>Requirement</th></tr></thead><tbody>{operation.parameters.map(parameter => <tr key={`${parameter.in}:${parameter.name}`}><td><code>{parameter.name}</code></td><td>{parameter.in}</td><td>{Array.isArray(parameter.schema?.type) ? parameter.schema.type.join(" | ") : parameter.schema?.type || "any"}</td><td>{parameter.required ? "required" : "optional"}</td></tr>)}</tbody></table></div> : <p>No parameters are documented.</p>}
    <h4>Possible responses</h4>
    {responses.length ? <dl className="explorer-doc-responses">{responses.map(([code, raw]) => { const response = resolveDocReference(spec, raw); return <div key={code}><dt><code>{code}</code></dt><dd><DocDescription text={response.error || response.value.description || "No description is documented."} /></dd></div>; })}</dl> : <p>No responses are documented.</p>}
    {op.description && <details className="explorer-doc-details"><summary>Details</summary><DocDescription text={op.description} /></details>}
  </section>;
}

export function ResponseHeaders({ spec, headers }: { spec: unknown; headers: unknown }) {
  const entries = Object.entries(object(headers));
  if (!entries.length) return null;
  return <section className="explorer-doc-response-headers"><h4>Response headers</h4>{entries.map(([name, raw]) => {
    const header = resolveDocReference(spec, raw);
    return <div key={name}><h5><code>{name}</code>{header.value.required && " · required"}</h5>{header.error ? <p>{header.error}</p> : <><DocDescription text={header.value.description} />{header.value.schema !== undefined && <SchemaNode spec={spec} schema={header.value.schema} name={name} />}</>}</div>;
  })}</section>;
}

export function SchemaNode({ spec, schema, name = "Schema", required = false, initialOpen = false }: {
  spec: unknown; schema: unknown; name?: string; required?: boolean; initialOpen?: boolean;
}) {
  const [open, setOpen] = useState(initialOpen);
  const { value, error } = resolveDocReference(spec, schema);
  const properties = Object.entries(object(value.properties));
  const variants = ["oneOf", "anyOf", "allOf"].filter(key => Array.isArray(value[key]));
  const type = Array.isArray(value.type) ? value.type.join(" | ") : value.type || (properties.length ? "object" : variants.length ? "variant" : "any");
  return <details className="explorer-schema-node" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary><strong>{name}</strong> <span className="explorer-schema-type">{type}</span>{required && <span className="explorer-schema-required">required</span>}{(value.nullable || (Array.isArray(value.type) && value.type.includes("null"))) && <span>nullable</span>}</summary>
    {open && <div className="explorer-schema-content">
      {typeof object(schema).$ref === "string" && <code className="explorer-schema-ref">{object(schema).$ref}</code>}
      {error ? <p role="status">{error}</p> : <>
        {value.description && <DocDescription text={value.description} />}
        {value.format && <p>Format: <code>{String(value.format)}</code></p>}
        {Array.isArray(value.enum) && <p>Allowed values: <code>{value.enum.map((item: unknown) => pretty(item)).join(", ")}</code></p>}
        {Object.prototype.hasOwnProperty.call(value, "const") && <p>Value: <code>{pretty(value.const)}</code></p>}
        {properties.map(([key, child]) => <SchemaNode key={key} spec={spec} schema={child} name={key} required={Array.isArray(value.required) && value.required.includes(key)} />)}
        {value.items && <SchemaNode spec={spec} schema={value.items} name="Array items" />}
        {variants.map(kind => <div key={kind} className="explorer-schema-variants"><h4>{kind}</h4>{value[kind].map((child: unknown, index: number) => <SchemaNode key={index} spec={spec} schema={child} name={`Variant ${index + 1}`} />)}</div>)}
        {value.additionalProperties === true && <p>Additional properties are allowed.</p>}
        {value.additionalProperties === false && <p>Additional properties are not allowed.</p>}
        {value.additionalProperties && typeof value.additionalProperties === "object" && <SchemaNode spec={spec} schema={value.additionalProperties} name="Additional properties" />}
        {schema === false && <p>No values are allowed.</p>}
      </>}
    </div>}
  </details>;
}

function SchemaView({ spec, schema }: { spec: unknown; schema: unknown }) {
  const [raw, setRaw] = useState(false);
  return <div className="explorer-schema"><label className="explorer-doc-toggle"><input type="checkbox" checked={raw} onChange={event => setRaw(event.target.checked)} /> Raw schema</label>{raw ? <pre>{pretty(schema)}</pre> : <SchemaNode spec={spec} schema={schema} initialOpen />}</div>;
}

export function ContentView({ spec, content }: { spec: unknown; content: unknown }) {
  const entries = Object.entries(object(content));
  const [selected, setSelected] = useState("");
  const media = entries.find(([name]) => name === selected) || entries[0];
  const [exampleName, setExampleName] = useState("");
  if (!media) return <p>No body is documented.</p>;
  const [mediaType, detail] = media;
  const examples = Object.entries(object(detail.examples));
  const example = examples.find(([name]) => name === exampleName) || examples[0];
  const resolvedExample = example && resolveDocReference(spec, example[1]);
  return <>
    <label className="explorer-doc-field">Media type<select value={mediaType} onChange={event => { setSelected(event.target.value); setExampleName(""); }}>{entries.map(([name]) => <option key={name}>{name}</option>)}</select></label>
    {detail.schema !== undefined ? <SchemaView key={mediaType} spec={spec} schema={detail.schema} /> : <p>No schema is documented.</p>}
    {example ? <section className="explorer-doc-example"><h3>Documented examples</h3><label className="explorer-doc-field">Example<select value={example[0]} onChange={event => setExampleName(event.target.value)}>{examples.map(([name, value]) => <option key={name} value={name}>{resolveDocReference(spec, value).value.summary || name}</option>)}</select></label>
      {resolvedExample?.error ? <p>{resolvedExample.error}</p> : <>{resolvedExample?.value.summary && <p>{String(resolvedExample.value.summary)}</p>}{resolvedExample?.value.description && <DocDescription text={resolvedExample.value.description} />}{Object.prototype.hasOwnProperty.call(resolvedExample?.value || {}, "value") ? <pre>{formatValue(resolvedExample?.value.value, mediaType)}</pre> : <p>Example value is not embedded in this contract.</p>}</>}
    </section> : Object.prototype.hasOwnProperty.call(detail, "example") ? <section className="explorer-doc-example"><h3>Documented example</h3><pre>{formatValue(detail.example, mediaType)}</pre></section> : <p className="explorer-doc-muted">No example is documented.</p>}
  </>;
}

export function ExplorerDocs({ spec, operation, onClose }: {
  spec: unknown; operation: ExplorerOperation; onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const [section, setSection] = useState("Overview");
  const [status, setStatus] = useState("");
  const op = operation.operation;
  const responses = Object.entries(object(op.responses));
  const selectedResponse = responses.find(([code]) => code === status) || responses[0];
  const response = resolveDocReference(spec, selectedResponse?.[1]);
  const request = resolveDocReference(spec, op.requestBody);
  useEffect(() => {
    const element = dialog.current;
    const previous = document.activeElement;
    element?.showModal();
    return () => { element?.close(); if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);
  return <dialog ref={dialog} className="dialog explorer-docs" aria-labelledby={titleId} onCancel={event => { event.preventDefault(); onClose(); }}>
    <header className="dialog-header"><div><span className="explorer-doc-kicker">Operation documentation</span><h2 id={titleId}>{op.summary || op.operationId || "API operation"}</h2></div><button type="button" className="plain-icon" aria-label="Close documentation" onClick={onClose}><X size={20} /></button></header>
    <code className="explorer-doc-route">{operation.method.toUpperCase()} {operation.path}</code>
    <nav className="explorer-doc-tabs" aria-label="Documentation sections">{["Overview", "Request schema", "Responses"].map(name => <button type="button" key={name} aria-pressed={section === name} onClick={() => setSection(name)}>{name}</button>)}</nav>
    <div className="explorer-doc-body">
      {section === "Overview" && <OperationOverview spec={spec} operation={operation} />}
      {section === "Request schema" && <section><h3>Request schema</h3>{operation.parameters.length > 0 && <section><h4>Parameters</h4>{operation.parameters.map(parameter => <div key={`${parameter.in}:${parameter.name}`}><p>{parameter.name} · {parameter.in}{parameter.required ? " · required" : ""}</p>{parameter.description && <DocDescription text={parameter.description} />}<SchemaView spec={spec} schema={parameter.schema || {}} /></div>)}</section>}{request.error ? <p>{request.error}</p> : op.requestBody ? <>{request.value.description && <DocDescription text={request.value.description} />}<p>{request.value.required ? "Request body is required." : "Request body is optional."}</p><ContentView spec={spec} content={request.value.content} /></> : <p>This operation has no documented request body.</p>}</section>}
      {section === "Responses" && <section><h3>Responses</h3>{selectedResponse ? <><label className="explorer-doc-field">Response status<select value={selectedResponse[0]} onChange={event => setStatus(event.target.value)}>{responses.map(([code]) => <option key={code}>{code}</option>)}</select></label>{response.error ? <p>{response.error}</p> : <><DocDescription text={response.value.description} /><ResponseHeaders spec={spec} headers={response.value.headers} /><ContentView key={selectedResponse[0]} spec={spec} content={response.value.content} /></>}</> : <p>No responses are documented.</p>}</section>}
    </div>
  </dialog>;
}
