import { impersonation, impersonationActive, endLocalImpersonation } from "./impersonation-state";
import { apiUrl } from "./portal-location";
import { accountSignal, accountEpoch, captureContext, contextHeaders, contextIdentity, assertContext } from "./account-context";
import { withServiceRecovery } from "./service-recovery";
export type ReadState = "initial" | "projected";
export function responseReadState(response: Response): ReadState | undefined {
  const value = response.headers.get("X-MyOS-Read-State");
  return value === "initial" || value === "projected" ? value : undefined;
}
const refreshingSessions = new Map<string, Promise<boolean>>();
function waitForSession(refresh: Promise<boolean>, signal?: AbortSignal | null) {
  if (!signal) return refresh;
  return new Promise<boolean>((resolve, reject) => {
    const aborted = () => reject(signal.reason);
    signal.addEventListener("abort", aborted, { once: true });
    refresh.then(resolve, reject).finally(() => signal.removeEventListener("abort", aborted));
    if (signal.aborted) aborted();
  });
}
export async function authenticatedFetch(path: string, options: RequestInit = {}, context = captureContext(), validateCaptured = true, refreshContext = context?.sessionContext ? context : captureContext(), automaticRecovery = true) {
  const epoch = accountEpoch();
  const headers = new Headers({
    ...(options.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
    "X-MyOS-Client": "console",
  });
  new Headers(options.headers).forEach((value, name) => headers.set(name, value));
  const bearer = headers.has("Authorization");
  const delegated = !bearer ? impersonation() : undefined;
  if (delegated) headers.set("Authorization", `Bearer ${delegated.token}`);
  const scopeSignal = accountSignal();
  const signal = bearer ? options.signal : options.signal
    ? AbortSignal.any([options.signal, scopeSignal]) : scopeSignal;
  const auth = path.startsWith("/api/auth/");
  const guarded = !auth || ["/api/auth/logout", "/api/auth/refresh"].includes(path);
  if (!bearer && guarded) Object.entries(contextHeaders(context)).forEach(([name, value]) => {
    if (!headers.has(name)) headers.set(name, value);
  });
  const once = () => {
    if (delegated && !impersonationActive()) return Promise.resolve(new Response(JSON.stringify({ title: "impersonation_ended", detail: "This impersonation session has ended." }), { status: 401, headers: { "Content-Type": "application/json" } }));
    if (!bearer && epoch !== accountEpoch()) throw new DOMException("Workspace changed", "AbortError");
    if (!bearer && guarded && validateCaptured) assertContext(context);
    return fetch(apiUrl(path.startsWith("/api/") ? path : `/api/blue/v1/${path}`), {
      ...options, signal, credentials: delegated ? "omit" : options.credentials ?? (bearer ? "omit" : "include"), headers, redirect: "error",
    });
  };
  const send = async () => {
    const read = ["GET", "HEAD"].includes((options.method || "GET").toUpperCase());
    for (let attempt = 0; ; attempt++) {
      signal?.throwIfAborted();
      const response = await once();
      signal?.throwIfAborted();
      if (!bearer && epoch !== accountEpoch()) throw new DOMException("Workspace changed", "AbortError");
      // Reads can cross a workspace handoff. Mutations already keep their durable
      // idempotency key and retry in mutate(); never replay an unkeyed write here.
      if (!automaticRecovery || !read || attempt === 3 || ![429, 502, 503, 504].includes(response.status))
        return response;
      const retryAfter = Number(response.headers.get("Retry-After") || 1);
      const delay = Number.isFinite(retryAfter) ? Math.min(5, Math.max(0, retryAfter)) : 1;
      await response.body?.cancel();
      await new Promise((resolve) => setTimeout(resolve, delay * 1000));
      signal?.throwIfAborted();
    }
  };
  let r = await send();
  // An authentication rejection happens before the route is admitted. Reuse the
  // original request and idempotency key after refreshing the console cookie.
  if (
    r.status === 401 &&
    automaticRecovery &&
    !delegated &&
    !path.startsWith("/api/auth/") &&
    !new Headers(options.headers).has("Authorization")
  ) {
    const refreshIdentity = contextIdentity(refreshContext);
    let refreshingSession = refreshingSessions.get(refreshIdentity);
    if (!refreshingSession) {
      refreshingSession = withServiceRecovery(async (signal) => {
        if (validateCaptured) assertContext(refreshContext);
        const response = await fetch(apiUrl("/api/auth/refresh"), {
          credentials: "include",
          method: "POST",
          signal: AbortSignal.any([signal, scopeSignal]),
          headers: {
            "Content-Type": "application/json",
            "X-MyOS-Client": "console",
            ...contextHeaders(refreshContext),
          },
          body: "{}",
        });
        if ([502, 503, 504].includes(response.status))
          throw Object.assign(new Error("Session refresh is temporarily unavailable"), { status: response.status });
        return response.ok;
      }).finally(() => {
        refreshingSessions.delete(refreshIdentity);
      });
      refreshingSessions.set(refreshIdentity, refreshingSession);
    }
    if (await waitForSession(refreshingSession, signal)) r = await send();
  }
  if (r.status === 401 && delegated) endLocalImpersonation();
  if (typeof window !== "undefined" && !auth && !bearer && epoch === accountEpoch()) {
    if (r.status === 401) window.dispatchEvent(new Event("myos-session-ended"));
    if ([403, 409].includes(r.status)) {
      const problem = await r.clone().json().catch(() => ({}));
      if (epoch === accountEpoch() && ["session_changed", "membership_required", "membership_inactive", "account_inactive", "account_unavailable", "user_inactive"].includes((problem.title || problem.code || "").toLowerCase()))
        window.dispatchEvent(new Event("myos-session-changed"));
    }
  }
  return r;
}

/** API Explorer keeps its explicit single-attempt semantics and captured identity. */
export function authenticatedFetchOnce(path: string, options: RequestInit, context = captureContext()) {
  return authenticatedFetch(path, options, context, true, context, false);
}
