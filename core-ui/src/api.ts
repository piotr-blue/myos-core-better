import { parseBlue, stringifyBlue } from "./blue-values";
import { accountEpoch, assertContext, captureContext, contextScope, type RequestContext } from "./account-context";
import { authenticatedFetch, responseReadState, type ReadState } from "./transport";
import { liveRevision, waitForLiveChange } from "./live";
import { replayable, withServiceRecovery } from "./service-recovery";
export async function api(path: string, options: RequestInit = {}, context = captureContext()) {
  return (await apiResponse(path, options, context)).data;
}
/** Response metadata stays separate from the canonical API representation. */
export async function apiResponse(path: string, options: RequestInit = {}, context = captureContext()): Promise<{ data: any; readState?: ReadState }> {
  const epoch = accountEpoch();
  const bearer = new Headers(options.headers).has("Authorization");
  const request = async (signal?: AbortSignal) => {
    const r = await authenticatedFetch(path, { ...options, signal: signal || options.signal }, context);
    let text: string;
    try { text = await r.text(); }
    finally {
      signal?.throwIfAborted();
      options.signal?.throwIfAborted();
      // Fetch resolves at headers; a delayed body must not cross account selection.
      if (!bearer) {
        if (epoch !== accountEpoch()) throw new DOMException("Workspace changed", "AbortError");
        if (!path.startsWith("/api/auth/") || ["/api/auth/logout", "/api/auth/refresh"].includes(path)) assertContext(context);
      }
    }
    let body: any;
    try {
      body = text ? parseBlue(text) : {};
    } catch {
      throw Object.assign(
        new Error(`Server returned ${r.status}. Please retry.`),
        { status: r.status },
      );
    }
    if (!r.ok)
      throw Object.assign(
        new Error(body?.detail || body?.message || `Request failed (${r.status})`),
        { status: r.status, code: body?.code },
      );
    return { data: body, readState: responseReadState(r) };
  };
  // Auth actions such as signup/resend can send email and have no durable key.
  // They must never be blindly replayed after an uncertain network response.
  return replayable(options) && !path.startsWith("/api/auth/")
    ? withServiceRecovery(request, { signal: options.signal })
    : request();
}
// Retain one key across transient failures and a browser refresh of an unfinished write.
async function pendingRequest(path: string, body: string, method: string, context?: RequestContext) {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(contextScope(context) + method + path + body),
  );
  const storageKey =
    "myos-pending-" +
    Array.from(new Uint8Array(digest))
      .map((n) => n.toString(16).padStart(2, "0"))
      .join("");
  const saved = sessionStorage.getItem(storageKey);
  const retained = saved ? JSON.parse(saved) : { key: crypto.randomUUID() };
  if (typeof retained.key !== "string" || (retained.admissionId && typeof retained.admissionId !== "string")) throw new Error("Invalid retained command intent.");
  sessionStorage.setItem(storageKey, JSON.stringify(retained));
  return { storageKey, ...retained } as { storageKey: string; key: string; admissionId?: string };
}

export type AdmissionProgress = { admissionId: string; status: string; details?: { sessionId?: string } | null; problem?: { detail?: string } };

export async function mutate(path: string, data: unknown, method = "POST", onAdmission?: (admission: AdmissionProgress) => void) {
  return mutateBody(path, stringifyBlue(data), method, "application/json", onAdmission);
}

/** Keep authoring numeric tokens intact while using ordinary YAML request fields. */
export async function mutateYaml(path: string, body: string, method = "POST") {
  parseBlue(body); // Syntax validation must not become the serialized request.
  return mutateBody(path, body, method, "application/yaml");
}

async function mutateBody(path: string, body: string, method: string, contentType: string, onAdmission?: (admission: AdmissionProgress) => void) {
  const context = captureContext();
  const { storageKey, key, admissionId } = await pendingRequest(path, body, method, context);
  const deadline = Date.now() + 15 * 60 * 1000;
  async function resilientRead(target: string, options: RequestInit = {}, awaitingAdmissionProjection = false) {
    for (let attempt = 0; ; attempt++) {
      try {
        return await api(target, options, context);
      } catch (error: any) {
        if (
          Date.now() > deadline ||
          (error.status && ![429, 502, 503, 504].includes(error.status) &&
            !(awaitingAdmissionProjection && error.status === 404 && error.code === "ADMISSION_NOT_FOUND"))
        )
          throw error;
        await new Promise((r) =>
          setTimeout(r, Math.min(5000, 500 * (attempt + 1))),
        );
      }
    }
  }
  let admissionObserved = false;
  async function readAdmission(id: string) {
    // A durable acceptance can arrive before its first PostgreSQL projection.
    // Once observed, disappearance is an error, including on a resumed command.
    const receipt = await resilientRead(`admissions/${encodeURIComponent(id)}`, {}, !admissionObserved);
    admissionObserved = true;
    return receipt;
  }
  let revision = liveRevision();
  let result = admissionId ? await readAdmission(admissionId) : await resilientRead(path, {
    method,
    body,
    headers: { "Idempotency-Key": key, "Content-Type": contentType },
  });
  if (result.admissionId) {
    sessionStorage.setItem(storageKey, JSON.stringify({ key, admissionId: result.admissionId }));
    onAdmission?.(result);
  }
  while (result.admissionId && result.status === "PENDING" && Date.now() < deadline) {
    await waitForLiveChange(revision, "admissions", result.admissionId);
    revision = liveRevision();
    result = await readAdmission(result.admissionId);
    onAdmission?.(result);
  }
  if (result.admissionId && result.status === "PENDING")
    throw new Error(
      `Processing continues. Admission: ${result.admissionId}. Retrying this request will use the same key.`,
    );
  sessionStorage.removeItem(storageKey);
  if (result.admissionId && result.status === "FAILED")
    throw new Error(result.problem?.detail || "Request could not be processed");
  return result.admissionId && result.status === "SUCCEEDED" ? result.details : result;
}

export async function allItems(path: string) {
  const context = captureContext();
  const items: any[] = [];
  let pageToken: string | null = null;
  do {
    const query = new URLSearchParams({ limit: "100" });
    if (pageToken) query.set("pageToken", pageToken);
    const page = await api(`${path}?${query}`, {}, context);
    items.push(...page.items);
    pageToken = page.hasMore === false ? null : page.nextPageToken;
  } while (pageToken);
  return items;
}
