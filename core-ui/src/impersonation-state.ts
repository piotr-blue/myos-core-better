/** Shared-origin, tab-local delegation. Retain ended state to prevent cookie fallback. */
export const impersonationKey = "myos-impersonation";
export type Impersonation = { token: string; userId: string; email: string; expiresAt: number; sessionContext?: string; ended?: boolean };
// A cached document must never adopt the operator cookie or another delegation.
let documentSession: Impersonation | undefined;
function retireDocumentSession() {
  if (documentSession) documentSession = { ...documentSession, ended: true };
  return documentSession;
}
export function impersonation(): Impersonation | undefined {
  const raw = typeof sessionStorage === "undefined" ? null : sessionStorage.getItem(impersonationKey);
  if (!raw) return retireDocumentSession();
  try {
    const value = JSON.parse(raw);
    if (typeof value.token === "string" && /^core_s_[A-Za-z0-9_-]{32,100}$/.test(value.token) &&
        typeof value.userId === "string" && typeof value.email === "string" && Number.isSafeInteger(value.expiresAt)) {
      if (documentSession && (documentSession.token !== value.token || documentSession.ended)) return retireDocumentSession();
      documentSession = value;
      return value;
    }
  } catch { /* Invalid retained delegation must fail closed. */ }
  return retireDocumentSession() ?? (documentSession = { token: "", userId: "", email: "", expiresAt: 0, ended: true });
}
export function impersonationActive(value = impersonation()) {
  return Boolean(value && !value.ended && value.expiresAt > Date.now());
}
export function endLocalImpersonation() {
  const value = impersonation();
  if (value) {
    documentSession = { ...value, ended: true };
    // Do not overwrite a newer document's session when this one returns from BFCache.
    try {
      if (JSON.parse(sessionStorage.getItem(impersonationKey) || "null")?.token === value.token)
        sessionStorage.setItem(impersonationKey, JSON.stringify(documentSession));
    } catch { /* Malformed storage already fails closed. */ }
  }
  if (typeof window !== "undefined") window.dispatchEvent(new Event("myos-session-ended"));
}
export function captureImpersonation() {
  if (!location.hash.startsWith("#impersonate=")) return;
  const raw = location.hash.slice("#impersonate=".length);
  // Remove credentials before React, diagnostics or any request observes navigation.
  history.replaceState(null, "", location.pathname + "#documents");
  sessionStorage.removeItem("myos-core-account");
  sessionStorage.removeItem("myos-core-account-choice-required");
  sessionStorage.removeItem("myos:selected-account");
  sessionStorage.removeItem("myos-account-choice-required");
  try { sessionStorage.setItem(impersonationKey, decodeURIComponent(raw)); }
  catch { sessionStorage.setItem(impersonationKey, "invalid"); }
}

/** Called only during fresh document bootstrap, before any delegated UI mounts. */
export function captureAdminReturn() {
  if (location.hash !== "#admin") return false;
  const url = new URL(location.href);
  if (url.searchParams.get("returnToAdmin") !== "1" || url.hash !== "#admin") return false;
  // A hash-only transition must never clear a running page's delegation guard.
  if (impersonationActive()) return false;
  sessionStorage.removeItem(impersonationKey);
  documentSession = undefined; // Fresh return document only; never reset a mounted delegated page.
  for (const key of ["myos-core-account", "myos-core-account-choice-required", "myos:selected-account", "myos-account-choice-required"]) sessionStorage.removeItem(key);
  url.searchParams.delete("returnToAdmin");
  history.replaceState(null, "", url);
  return true;
}
