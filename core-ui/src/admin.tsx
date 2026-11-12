import { AdminApi } from "@blue-labs/myos-sdk";
import { useEffect, useRef, useState } from "react";
import { ArrowDown, ArrowUp } from "lucide-react";
import { accountResult, coreConfiguration } from "./account-api";
import { impersonation } from "./impersonation-state";
import { PortalSignIn } from "./portal-auth";
import { portalOrigin, portalLocation } from "./portal-location";
import { DevLayout } from "./components/dev-layout";
import { authenticatedFetch } from "./transport";
import "./admin.css";
let adminSessionContext: string | undefined;
let adminSessionEpoch = 0;
const adminApi = () => new AdminApi(coreConfiguration(adminSessionContext ? { sessionContext: adminSessionContext } : {}));
const date = (value: number | null | undefined) => value == null
  ? <span title="The identity provider has not supplied this date." aria-label="Date unavailable">—</span>
  : new Date(value).toLocaleString();
export function AdminPanel({ returning = false }: { returning?: boolean }) {
  const [operator, setOperator] = useState<Awaited<ReturnType<AdminApi["getAdminSession"]>>>();
  const [error, setError] = useState("");
  const [unauthorized, setUnauthorized] = useState(false);
  const [section, setSection] = useState("users");
  const [selected, setSelected] = useState<string>();
  useEffect(() => {
    if (impersonation()) { setError("The Admin Panel is unavailable during impersonation."); return; }
    let active = true;
    const controller = new AbortController();
    const ended = () => {
      active = false; controller.abort(); adminSessionContext = undefined; adminSessionEpoch++;
      setOperator(undefined); setSelected(undefined); setSection("users"); setUnauthorized(true);
      if (returning) location.replace(`${portalOrigin()}/`);
    };
    const channel = new BroadcastChannel("myos-session");
    channel.onmessage = event => { if (event.data === "signed-out") ended(); };
    addEventListener("myos-session-ended", ended);
    addEventListener("myos-session-changed", ended);
    accountResult(adminApi().getAdminSession({}, { signal: controller.signal })).then(value => { if (active) { adminSessionContext = value.sessionContext; setOperator(value); } })
      .catch(failure => { if (active) { if (returning && [401, 403].includes(failure.status)) { location.replace(`${portalOrigin()}/`); return; } setUnauthorized(failure.status === 401); setError(failure.status === 403 ? "This account does not have access to the Admin Panel." : failure.message); } });
    return () => { active = false; adminSessionEpoch++; controller.abort(); channel.close(); removeEventListener("myos-session-ended", ended); removeEventListener("myos-session-changed", ended); };
  }, []);
  if (unauthorized) return <PortalSignIn returnTo="/#admin" />;
  if (!operator) return <main className="admin-page"><h1>MyOS Admin Panel</h1><p role={error ? "alert" : "status"}>{error || "Checking administrator access…"}</p><a href={`${portalOrigin()}/developers/app/`}>Developer Platform</a></main>;
  const logout = async () => {
    const response = await authenticatedFetch("/api/auth/logout", { method: "POST" }, { userId: operator.userId, sessionContext: operator.sessionContext }, false);
    if (!response.ok) throw new Error("Unable to sign out.");
    const result = await response.json();
    dispatchEvent(new Event("myos-session-ended"));
    const channel = new BroadcastChannel("myos-session"); channel.postMessage("signed-out"); channel.close();
    location.assign(result.logoutUrl || `${portalOrigin()}/`);
  };
  return <div className="admin-shell" data-dd-privacy="hidden"><DevLayout adminPanel page={section} navigate={value => { setSection(value); setSelected(undefined); }} logout={logout}>
    <div className="admin-page">{section === "notifications" ? <Notifications /> : selected ? <UserDetail id={selected} back={() => setSelected(undefined)} /> : <Users select={setSelected} />}</div>
  </DevLayout></div>;
}
function Users({ select }: { select: (id: string) => void }) {
  const [email, setEmail] = useState("");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<"createdAt" | "verifiedAt">("createdAt");
  const [direction, setDirection] = useState<"asc" | "desc">("desc");
  const [verification, setVerification] = useState("all");
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<Awaited<ReturnType<AdminApi["listAdminUsers"]>>>();
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  useEffect(() => { const timer = setTimeout(() => { setQuery(email.trim()); setOffset(0); }, 300); return () => clearTimeout(timer); }, [email]);
  useEffect(() => {
    if (query.length === 1) { setLoading(false); return; }
    const controller = new AbortController(); setLoading(true); setError("");
    accountResult(adminApi().listAdminUsers({ email: query, sort, direction, verified: verification === "all" ? undefined : verification === "verified", limit: 25, offset }, { signal: controller.signal }))
      .then(value => { if (!controller.signal.aborted) setPage(value); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [query, sort, direction, verification, offset]);
  return <><h1>Users</h1><p>Find MyOS users and inspect their verification and workspaces.</p><div className="admin-controls"><label>Email address<input type="search" placeholder="Search by email" value={email} onChange={event => setEmail(event.target.value)} /></label><label>Verification<select value={verification} onChange={event => { setVerification(event.target.value); setOffset(0); }}><option value="all">All</option><option value="verified">Verified</option><option value="unverified">Unverified</option></select></label><div className="admin-sort"><label>Sort by<select value={sort} onChange={event => { setSort(event.target.value as typeof sort); setOffset(0); }}><option value="createdAt">Registration date</option><option value="verifiedAt">Verification date</option></select></label><button type="button" className="admin-sort-direction" aria-label={direction === "asc" ? "Ascending order. Switch to descending" : "Descending order. Switch to ascending"} title={direction === "asc" ? "Oldest first" : "Newest first"} onClick={() => { setDirection(direction === "asc" ? "desc" : "asc"); setOffset(0); }}>{direction === "asc" ? <ArrowUp size={20} aria-hidden="true" /> : <ArrowDown size={20} aria-hidden="true" />}</button></div></div>
    {query.length === 1 && <p role="status">Enter at least 2 characters to search.</p>}{error && <p role="alert" className="error">{error}</p>}
    {loading && <p role="status">Loading users…</p>}
    {!loading && query.length !== 1 && page && <><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>Email</th><th>Verification</th><th>Registration date</th><th>Verification date</th><th>Actions</th></tr></thead><tbody>{page.items.map(user => <tr key={user.id}><td><button onClick={() => select(user.id)}>{user.email}</button><small>{[user.firstName, user.lastName].filter(Boolean).join(" ")}</small></td><td><span className={`admin-status ${user.verified ? "verified" : ""}`}>{user.verified ? "Verified" : "Unverified"}</span>{!user.enabled && <small>Account disabled</small>}</td><td>{date(user.createdAt)}</td><td>{date(user.verifiedAt)}</td><td><Impersonate user={user} /></td></tr>)}</tbody></table></div>{!page.items.length && <p>No users match these filters.</p>}<div className="admin-controls admin-pagination"><button disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - 25))}>Previous</button><span>{page.total ? `${offset + 1}–${offset + page.items.length} of ${page.total}` : "0 users"}</span><button disabled={offset + page.items.length >= page.total} onClick={() => setOffset(offset + 25)}>Next</button></div></>}
  </>;
}
function UserDetail({ id, back }: { id: string; back: () => void }) {
  const [detail, setDetail] = useState<Awaited<ReturnType<AdminApi["getAdminUser"]>>>();
  const [error, setError] = useState("");
  useEffect(() => { const controller = new AbortController(); accountResult(adminApi().getAdminUser({ id }, { signal: controller.signal })).then(setDetail).catch(failure => { if (!controller.signal.aborted) setError(failure.message); }); return () => controller.abort(); }, [id]);
  return <><button onClick={back}>← Users</button>{error && <p role="alert" className="error">{error}</p>}{!detail ? <p role="status">Loading user…</p> : <><div className="admin-user-header"><div><h1>{detail.user.email}</h1><p>{[detail.user.firstName, detail.user.lastName].filter(Boolean).join(" ")}</p></div><Impersonate user={detail.user} /></div><dl className="admin-details">{Object.entries({ Verification: detail.user.verified ? "Verified" : "Unverified", "Account status": detail.user.enabled ? "Active" : "Disabled", "Registration date": date(detail.user.createdAt), "Verification date": date(detail.user.verifiedAt) }).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl><h2>Workspaces</h2><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>Name</th><th>Type</th><th>Membership</th><th>Address</th><th>Created</th></tr></thead><tbody>{detail.workspaces.map(workspace => <tr key={workspace.accountId}><td>{String(workspace.profile?.displayName || workspace.accountId)}<small>{String(workspace.profile?.website || "")}</small></td><td>{workspace.kind === "BUSINESS" ? "Business" : "Personal"}</td><td>{workspace.role}{workspace.membershipStatus && workspace.membershipStatus !== "ACTIVE" && <small>Membership: {workspace.membershipStatus}</small>}{workspace.status !== "ACTIVE" && <small>Workspace: {workspace.status}</small>}</td><td>{address(workspace.profile?.address)}</td><td>{date(workspace.createdAt)}</td></tr>)}</tbody></table></div>{!detail.workspaces.length && <p>This user has no workspaces.</p>}</> }</>;
}
function address(value: unknown) { return typeof value === "string" ? value : value && typeof value === "object" ? Object.values(value).filter(Boolean).join(", ") : "—"; }
function Impersonate({ user }: { user: { userId?: string | null; verified: boolean; enabled: boolean; email: string } }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  const start = async () => {
    if (!user.userId || busy) return;
    const tab = window.open("about:blank", "_blank");
    if (!tab) { setError("Allow pop-ups to open an impersonation tab."); return; }
    tab.opener = null;
    tab.document.title = "Opening MyOS session…";
    setBusy(true); setError("");
    const slot = `myos-admin-impersonation-intent:${user.userId}`;
    let attemptedKey: string | undefined;
    try {
      const epoch = adminSessionEpoch;
      const idempotencyKey = sessionStorage.getItem(slot) || crypto.randomUUID();
      attemptedKey = idempotencyKey;
      sessionStorage.setItem(slot, idempotencyKey);
      const session = await accountResult(adminApi().createImpersonation({ userId: user.userId, idempotencyKey }));
      if (epoch !== adminSessionEpoch) throw new Error("The administrator session changed. Reopen the Admin Panel.");
      sessionStorage.removeItem(slot);
      const target = new URL(portalLocation.base, location.origin);
      target.hash = "impersonate=" + encodeURIComponent(JSON.stringify({ token: session.token, userId: session.userId, email: session.email, expiresAt: session.expiresAt }));
      tab.location.replace(target.href);
    } catch (failure: any) {
      tab.close();
      const expired = failure.status === 409 && String(failure.code).toLowerCase() === "impersonation_expired";
      if (expired && attemptedKey && sessionStorage.getItem(slot) === attemptedKey) sessionStorage.removeItem(slot);
      setError(expired ? "The previous impersonation session expired. Click Impersonate to start a new session." : failure.message);
    }
    finally { setBusy(false); }
  };
  return <><button className="admin-primary" disabled={busy || !user.userId || !user.verified || !user.enabled} onClick={() => void start()}>{busy ? "Opening…" : "Impersonate"}</button>{error && <small role="alert" className="error">{error}</small>}</>;
}
function Notifications() {
  const [settings, setSettings] = useState<Awaited<ReturnType<AdminApi["getAdminNotifications"]>>>();
  const [email, setEmail] = useState("");
  const emailInput = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(""), [saved, setSaved] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    accountResult(adminApi().getAdminNotifications({}, { signal: controller.signal })).then(setSettings)
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, []);
  useEffect(() => { if (!busy) emailInput.current?.focus(); }, [busy]);
  const save = async (recipients: string[], adding = false) => {
    if (!settings || busy) return;
    setBusy(true); setError(""); setSaved(false);
    try {
      const value = await accountResult(adminApi().updateAdminNotifications({ updateAdminNotifications: { recipients, expectedRevision: settings.revision } }));
      setSettings(value); if (adding) setEmail(""); setSaved(true);
    } catch (failure: any) { setError(failure.message); }
    finally { setBusy(false); }
  };
  const add = () => {
    if (!settings || busy) return;
    const recipient = email.trim().toLowerCase();
    if (settings.recipients.some(value => value.toLowerCase() === recipient)) { setError("This email is already on the list."); setSaved(false); return; }
    void save([...settings.recipients, recipient], true);
  };
  return <><h1>Email notifications</h1><p>Send an email whenever a user registers or verifies their account. Changes are saved automatically. Leave the list empty to disable notifications.</p>
    <section className="admin-settings" aria-label="Notification recipients">
      <form className="admin-recipient-add" onSubmit={event => { event.preventDefault(); add(); }}>
        <label>Email address<input ref={emailInput} type="email" required autoComplete="email" value={email} disabled={!settings || busy} onChange={event => { setEmail(event.target.value); setError(""); setSaved(false); }} placeholder="admin@example.com" /></label>
        <button className="admin-primary" disabled={!settings || busy || !email.trim() || settings.recipients.length >= 100}>Add</button>
      </form>
      {!settings && !error && <p role="status">Loading recipients…</p>}
      {settings && (settings.recipients.length ? <ul className="admin-recipients" aria-label="Recipients">{settings.recipients.map(recipient => <li key={recipient}><span>{recipient}</span><button type="button" disabled={busy} aria-label={`Remove ${recipient}`} onClick={() => void save(settings.recipients.filter(value => value !== recipient))}>Remove</button></li>)}</ul> : <p>No recipients. Email notifications are disabled.</p>)}
      {settings?.recipients.length === 100 && <p>The list can contain up to 100 recipients.</p>}
      {busy && <p role="status">Saving recipients…</p>}{saved && <p role="status">Notification recipients saved.</p>}
      {error && <p role="alert" className="error">{error}</p>}
    </section></>;
}
