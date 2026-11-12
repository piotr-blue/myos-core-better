import { AdminApi } from "@blue-labs/myos-sdk";
import { useEffect, useState, type ReactNode } from "react";
import { accountResult, coreConfiguration } from "./account-api";
import { impersonation, impersonationActive, endLocalImpersonation, impersonationKey } from "./impersonation-state";
import { portalOrigin, portalLocation } from "./portal-location";
import "./admin.css";

export function impersonationApi() { return new AdminApi(coreConfiguration({ sessionContext: impersonation()?.sessionContext })); }
export async function endImpersonation() {
  if (!impersonation()?.sessionContext) throw new Error("Wait until impersonation access is verified.");
  await accountResult(impersonationApi().endImpersonation());
  endLocalImpersonation();
  returnToAdminPanel();
}
export function returnToAdminPanel() {
  const target = new URL(portalLocation.base, location.origin);
  target.searchParams.set("returnToAdmin", "1"); target.hash = "admin";
  // Keep the ended guard until a fresh document can safely check the operator session.
  location.replace(target.href);
}
export function ImpersonationBoundary({ children }: { children: ReactNode }) {
  const [session, setSession] = useState(impersonation);
  const [now, setNow] = useState(Date.now);
  const [verified, setVerified] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!session || !impersonationActive(session)) return;
    let active = true;
    accountResult(impersonationApi().getImpersonation()).then(value => {
      const retained = impersonation();
      if (!active || retained?.token !== session.token || !impersonationActive(retained)) return;
      if (value.userId !== session.userId) throw new Error("The impersonated user changed.");
      const next = { ...retained, email: value.email, expiresAt: value.expiresAt, sessionContext: value.sessionContext };
      sessionStorage.setItem(impersonationKey, JSON.stringify(next));
      setSession(next); setVerified(true);
    }).catch(failure => { if (active) { if (failure.status === 401) endLocalImpersonation(); setError(failure.message); } });
    return () => { active = false; };
  }, []);
  useEffect(() => {
    if (!session) return;
    const ended = () => { setSession(impersonation()); setVerified(false); };
    const restored = (event: PageTransitionEvent) => {
      if (event.persisted && !impersonationActive()) { setNow(Date.now()); ended(); }
    };
    addEventListener("pageshow", restored);
    addEventListener("myos-session-ended", ended);
    const timer = setInterval(() => {
      setNow(Date.now());
      if (!impersonationActive()) endLocalImpersonation();
    }, 1000);
    return () => { clearInterval(timer); removeEventListener("myos-session-ended", ended); removeEventListener("pageshow", restored); };
  }, [Boolean(session)]);
  if (!session) return <>{children}</>;
  const active = !session.ended && session.expiresAt > now;
  const remaining = Math.max(0, Math.ceil((session.expiresAt - now) / 1000));
  const countdown = `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, "0")}`;
  const finish = async () => {
    setBusy(true); setError("");
    try { await endImpersonation(); setSession(impersonation()); }
    catch (failure: any) { setError(failure.message); }
    finally { setBusy(false); }
  };
  return <><aside className="impersonation-banner" aria-label="Impersonation session" data-dd-privacy="hidden">
    <strong>Impersonating {session.email}</strong>
    <span>{active ? `Time remaining ${countdown} · Expires ${new Date(session.expiresAt).toLocaleTimeString()}` : "Session ended"}</span>
    {active && <><a href={`${portalOrigin()}/developers/app/#documents`}>Developer Platform</a><a href={`${portalOrigin()}/business/`}>MyOS Business</a>
      <button disabled={busy || !session.sessionContext} onClick={() => void finish()}>{busy ? "Ending…" : "End session"}</button></>}
    {!active && <button onClick={returnToAdminPanel}>Return to Admin Panel</button>}
  </aside>{error && <p className="error" role="alert">{error}</p>}
    {active && verified ? children : <main className="admin-page"><h1>{active ? "Opening impersonation session…" : "Impersonation session ended"}</h1><p>{active ? "Verifying access to this user." : "This tab can no longer access the user's account."}</p></main>}
  </>;
}
