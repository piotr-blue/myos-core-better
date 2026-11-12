import { authenticatedFetchOnce } from "./transport";
import { captureContext, contextHeaders, type RequestContext } from "./account-context";
import { apiUrl, portalLocation } from "./portal-location";
import type { ExplorerOperation, OpenApiSpec } from "./explorer-contract";
export type ExplorerAuth = { mode: "session" | "bearer"; token?: string };
export type RequestDraft = {
  parameters: Record<string, string>; body: string; sendBody: boolean;
  mediaType: string; accept: string; auth: ExplorerAuth;
  headers?: Record<string, string>;
};
export type PreparedRequest = {
  method: string; url: string; headers: Record<string, string>;
  context?: RequestContext;
  body?: string; credentials: "include" | "omit";
};
export type ExplorerResponse = {
  status: number; statusText: string; headers: Record<string, string>;
  rawBody: string; elapsedMs: number; bytes: number; method: string; url: string;
};
export type SseFrame = { event: string; data: string; id?: string; raw: string };

import { parseDocument } from "yaml";
import { parseBlue } from "./blue-values";
import { parameterKey } from "./explorer-contract";

const prefix = "/api/blue/v1/";
const reserved = new Set(["authorization", "cookie", "host", "origin", "content-length", "content-type", "accept", "x-myos-client", "x-myos-account", "x-myos-session-context"]);
function ensure(condition: unknown, message: string): asserts condition { if (!condition) throw new Error(message); }
function protectedHeader(name: string) {
  const lower = name.toLowerCase();
  return reserved.has(lower) || lower.includes("authorization");
}
function safePath(path: string) {
  ensure(path.startsWith(prefix) && !/[?#\\\r\n]/.test(path), "Use an API operation from this application's contract.");
  for (const part of path.split("/")) {
    let decoded: string;
    try { decoded = decodeURIComponent(part); } catch { throw new Error("Use an API operation from this application's contract."); }
    ensure(decoded !== "." && decoded !== ".." && !decoded.includes("\\") && !decoded.includes("/"), "Relative path segments are not allowed.");
  }
}
function validateParameter(parameter: any, value: string) {
  const schema = parameter.schema || {};
  const types = Array.isArray(schema.type) ? schema.type : [schema.type];
  if (types.includes("integer")) ensure(/^-?\d+$/.test(value), `${parameter.name} must be an integer.`);
  if (types.includes("number") || types.includes("integer")) {
    const number = Number(value);
    ensure(Number.isFinite(number), `${parameter.name} must be a finite number.`);
    if (schema.minimum !== undefined) ensure(number >= schema.minimum, `${parameter.name} must be at least ${schema.minimum}.`);
    if (schema.maximum !== undefined) ensure(number <= schema.maximum, `${parameter.name} must be at most ${schema.maximum}.`);
  }
  if (types.includes("boolean")) ensure(["true", "false"].includes(value), `${parameter.name} must be true or false.`);
  if (schema.enum) ensure(schema.enum.some((item: any) => String(item) === value), `Choose an allowed ${parameter.name}.`);
  if (schema.minLength !== undefined) ensure(value.length >= schema.minLength, `${parameter.name} is too short.`);
  if (schema.maxLength !== undefined) ensure(value.length <= schema.maxLength, `${parameter.name} is too long.`);
}
export function buildRequest(operation: ExplorerOperation, draft: RequestDraft): PreparedRequest {
  safePath(operation.path);
  let path = operation.path;
  const query = new URLSearchParams();
  const headers: Record<string, string> = { Accept: draft.accept || "application/json" };
  const putHeader = (name: string, value: string) => {
    const existing = Object.keys(headers).find(key => key.toLowerCase() === name.toLowerCase());
    if (existing) delete headers[existing];
    headers[name] = value;
  };
  for (const parameter of operation.parameters) {
    if (parameter.in === "header" && ["x-myos-account", "x-myos-session-context"].includes(parameter.name.toLowerCase())) continue;
    const key = parameterKey(parameter);
    const value = draft.parameters[key] ?? "";
    if (parameter.in === "header" && parameter.name.toLowerCase() === "idempotency-key" && value === "") continue;
    ensure(!parameter.required || value !== "", `Provide ${parameter.name}.`);
    if (value === "") continue;
    validateParameter(parameter, value);
    if (parameter.in === "path") {
      ensure(![".", ".."].includes(value) && !/[\\/]/.test(value), "Relative path segments are not allowed.");
      path = path.replaceAll(`{${parameter.name}}`, encodeURIComponent(value));
    } else if (parameter.in === "query") query.append(parameter.name, value);
    else if (parameter.in === "header") {
      ensure(!protectedHeader(parameter.name), `Configure ${parameter.name} using the request controls.`);
      putHeader(parameter.name, value);
    }
  }
  ensure(!/[{}]/.test(path), "Fill all path parameters before sending.");
  safePath(path);
  for (const [name, value] of Object.entries(draft.headers || {})) {
    ensure(!protectedHeader(name), `Configure ${name} using the request controls.`);
    putHeader(name, value);
  }
  if (draft.auth.mode === "bearer") {
    ensure(draft.auth.token?.trim(), "Provide a Bearer token or use your signed-in session.");
    headers.Authorization = `Bearer ${draft.auth.token.trim()}`;
  } else Object.assign(headers, { "X-MyOS-Client": "console", ...contextHeaders(captureContext()) });
  if (!["GET", "HEAD", "OPTIONS"].includes(operation.method)) {
    const key = Object.keys(headers).find(name => name.toLowerCase() === "idempotency-key");
    if (!key) headers["Idempotency-Key"] = crypto.randomUUID();
    else ensure(headers[key].trim().length > 0 && headers[key].length <= 255, "Idempotency-Key must contain 1–255 characters.");
  }
  let body: string | undefined;
  ensure(!operation.requestBody?.required || draft.sendBody, "Provide a request body.");
  if (draft.sendBody) {
    ensure(!["GET", "HEAD"].includes(operation.method) && !!operation.requestBody, "This operation has no request body.");
    ensure(Object.hasOwn(operation.requestBody.content || {}, draft.mediaType), "Choose a supported request format.");
    ensure(draft.body.trim(), "Provide a request body, or turn off the optional body.");
    try {
      if (draft.mediaType.includes("json")) JSON.parse(draft.body);
      else if (draft.mediaType.includes("yaml")) {
        const document = parseDocument(draft.body, { uniqueKeys: true });
        ensure(!document.errors.length, document.errors[0]?.message || "Invalid YAML.");
      }
    } catch (error: any) { throw new Error(`Invalid request body: ${error.message}`); }
    body = draft.body; // Validation must never round or rewrite transport text.
    headers["Content-Type"] = draft.mediaType;
  }
  new Headers(headers); // Reject invalid names and CR/LF values before sending.
  return { method: operation.method, url: path + (query.size ? `?${query}` : ""), headers, body,
    ...(draft.auth.mode === "session" ? { context: captureContext() } : {}),
    credentials: draft.auth.mode === "bearer" ? "omit" : "include" };
}
function send(request: PreparedRequest, signal?: AbortSignal) {
  safePath(request.url.split("?")[0]);
  if (request.credentials === "include") return authenticatedFetchOnce(request.url, { method: request.method, headers: request.headers, body: request.body, signal }, request.context);
  return fetch(apiUrl(request.url), { method: request.method, headers: request.headers, body: request.body,
    credentials: request.credentials, redirect: "error", signal });
}
function result(request: PreparedRequest, response: Response, started: number, rawBody: string): ExplorerResponse {
  return { status: response.status, statusText: response.statusText, headers: Object.fromEntries(response.headers.entries()),
    rawBody, elapsedMs: Math.round(performance.now() - started), bytes: new TextEncoder().encode(rawBody).length,
    method: request.method, url: apiUrl(request.url) };
}
export async function executeRequest(request: PreparedRequest, signal?: AbortSignal): Promise<ExplorerResponse> {
  const started = performance.now();
  const response = await send(request, signal);
  return result(request, response, started, await response.text());
}
export function copyCurl(request: PreparedRequest, origin = portalLocation.api || globalThis.location?.origin || "http://localhost"): string {
  const quote = (value: string) => "'" + value.replaceAll("'", "'\"'\"'") + "'";
  const parts = [`curl --request ${request.method} ${quote(origin + request.url)}`,
    '-H "Authorization: Bearer ${MYOS_TOKEN}"'];
  for (const [name, value] of Object.entries(request.headers)) {
    if (name.toLowerCase().includes("authorization") || ["cookie", "x-myos-client", "x-myos-account", "x-myos-session-context"].includes(name.toLowerCase())) continue;
    // Custom secret-like headers must not leak into copied examples.
    const sensitive = /secret|token|api[-_]?key/i.test(name) && name.toLowerCase() !== "idempotency-key";
    parts.push(sensitive ? `-H ${quote(name + ": <redacted>")}` : `-H ${quote(name + ": " + value)}`);
  }
  if (request.body !== undefined) parts.push(`--data-raw ${quote(request.body)}`);
  if (request.method === "HEAD") parts[0] = `curl --head ${quote(origin + request.url)}`;
  return parts.join(" \\\n  ");
}
function responseMedia(response: Pick<ExplorerResponse, "headers">) {
  const key = Object.keys(response.headers).find(name => name.toLowerCase() === "content-type");
  return key ? response.headers[key].split(";")[0]?.trim().toLowerCase() || "" : "";
}
function localRef(spec: OpenApiSpec, ref: string): any {
  if (!ref.startsWith("#/")) return undefined;
  return ref.slice(2).split("/").reduce((node, part) => node?.[part.replace(/~1/g, "/").replace(/~0/g, "~")], spec);
}
function admissionVariantRefs(spec: OpenApiSpec) {
  return new Set((spec.components?.schemas?.Admission?.oneOf || []).map((schema: any) => schema?.$ref).filter((ref: any): ref is string => typeof ref === "string"));
}
function admissionSchema(spec: OpenApiSpec, schema: any, seen = new Set<string>()): boolean {
  if (!schema || typeof schema !== "object") return false;
  if (typeof schema.$ref === "string") {
    if (schema.$ref === "#/components/schemas/Admission" || admissionVariantRefs(spec).has(schema.$ref)) return true;
    if (seen.has(schema.$ref)) return false;
    seen.add(schema.$ref);
    return admissionSchema(spec, localRef(spec, schema.$ref), seen);
  }
  return ["oneOf", "anyOf", "allOf"].some(key => Array.isArray(schema[key]) && schema[key].some((item: any) => admissionSchema(spec, item, seen)));
}
function responseDeclaresAdmission(spec: OpenApiSpec, operation: Pick<ExplorerOperation, "responses">, response: Pick<ExplorerResponse, "status" | "headers">) {
  if (response.status !== 200 && response.status !== 202) return false;
  const media = responseMedia(response);
  if (media !== "application/json" && media !== "application/yaml") return false;
  const content = operation.responses[String(response.status)]?.content?.[media];
  return admissionSchema(spec, content?.schema);
}
export function admissionFrom(spec: OpenApiSpec, operation: Pick<ExplorerOperation, "responses">, response: Pick<ExplorerResponse, "status" | "headers" | "rawBody">): { admissionId: string; status: string } | undefined {
  if (!responseDeclaresAdmission(spec, operation, response)) return;
  try {
    const value = parseBlue(response.rawBody);
    if (typeof value?.admissionId === "string" && value.admissionId && ["PENDING", "SUCCEEDED", "FAILED"].includes(value.status))
      return { admissionId: value.admissionId, status: value.status };
  } catch { /* Invalid representations are not Admissions. */ }
}
export function admissionRequest(original: PreparedRequest, id: string): PreparedRequest {
  const headers = Object.fromEntries(Object.entries(original.headers).filter(([name]) => !["content-type", "idempotency-key"].includes(name.toLowerCase())));
  headers.Accept = "application/json";
  return { ...original, method: "GET", url: `${prefix}admissions/${encodeURIComponent(id)}`, headers, body: undefined };
}
/** Format JSON tokens without turning exact numeric tokens into JavaScript numbers. */
export function formatResponse(raw: string, media = "application/json"): string {
  if (!media.includes("json") || !raw.trim()) return raw;
  try { JSON.parse(raw); } catch { return raw; }
  let output = "", depth = 0, quoted = false, escaped = false;
  const newline = () => { output += "\n" + "  ".repeat(depth); };
  for (let i = 0; i < raw.length; i++) {
    const char = raw[i];
    if (quoted) { output += char; if (escaped) escaped = false; else if (char === "\\") escaped = true; else if (char === '"') quoted = false; continue; }
    if (char === '"') { quoted = true; output += char; }
    else if (char === "{" || char === "[") {
      output += char; depth++;
      const tail = raw.slice(i + 1).trimStart();
      if (!(char === "{" && tail[0] === "}" || char === "[" && tail[0] === "]")) newline();
    } else if (char === "}" || char === "]") {
      depth--;
      if (!/[{[]$/.test(output)) newline();
      output += char;
    } else if (char === ",") { output += char; newline(); }
    else if (char === ":") output += ": ";
    else if (!/\s/.test(char)) output += char;
  }
  return output;
}
export async function executeStream(request: PreparedRequest, signal: AbortSignal | undefined,
  onResponse: (response: ExplorerResponse) => void, onFrame: (frame: SseFrame) => void): Promise<void> {
  const started = performance.now();
  const response = await send(request, signal);
  if (!response.ok || !response.headers.get("content-type")?.includes("text/event-stream")) {
    onResponse(result(request, response, started, await response.text())); return;
  }
  onResponse(result(request, response, started, ""));
  if (!response.body) return;
  const reader = response.body.getReader(), decoder = new TextDecoder();
  let buffer = "", lines: string[] = [], eventBytes = 0, totalBytes = 0;
  const dispatch = () => {
    const data: string[] = []; let event = "message", id: string | undefined;
    for (const line of lines) {
      if (line.startsWith(":")) continue;
      const colon = line.indexOf(":"), name = colon < 0 ? line : line.slice(0, colon);
      const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
      if (name === "data") data.push(value); else if (name === "event") event = value || "message";
      else if (name === "id" && !value.includes("\0")) id = value;
    }
    if (data.length) onFrame({ event, data: data.join("\n"), id, raw: lines.join("\n") + "\n\n" });
    lines = []; eventBytes = 0;
  };
  try {
    while (true) {
      const next = await reader.read(); if (next.done) break;
      totalBytes += next.value.byteLength; buffer += decoder.decode(next.value, { stream: true });
      onResponse({ ...result(request, response, started, ""), bytes: totalBytes });
      // Bound malformed streams that never finish a line/event.
      ensure(buffer.length + eventBytes <= 1048576, "Stream frame exceeds the 1 MiB display limit.");
      while (true) {
        const end = buffer.search(/[\r\n]/); if (end < 0 || buffer[end] === "\r" && end === buffer.length - 1) break;
        const line = buffer.slice(0, end), length = buffer[end] === "\r" && buffer[end + 1] === "\n" ? 2 : 1;
        buffer = buffer.slice(end + length);
        if (line === "") dispatch(); else { lines.push(line); eventBytes += line.length; }
      }
    }
    buffer += decoder.decode();
    if (/^\r\n?$|^\n$/.test(buffer)) { buffer = ""; dispatch(); }
    onResponse({ ...result(request, response, started, ""), bytes: totalBytes });
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
