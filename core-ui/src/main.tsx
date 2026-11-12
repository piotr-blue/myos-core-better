import { AdminPanel } from "./admin";
import { captureImpersonation, captureAdminReturn, impersonation } from "./impersonation-state";
import { ImpersonationBoundary, endImpersonation } from "./impersonation";
import { useAccounts } from "./use-accounts";
import { observability } from "./observability";
import { PrivacyPreferences } from "./privacy-preferences";
import { listOwnApiKeys } from "./account-api";
import { AccountSelection } from "./account-selection";
import { WorkspaceSelector, WorkspaceSettings } from "./workspace-settings";
import { InvitationAcceptance, captureInvitation } from "./invitation";
import { accountContext, contextIdentity, chooseAccount, setAccountContext, userContext } from "./account-context";
import { CustomerPayment } from "./customer-payment";
import { assetUrl } from "./portal-location";
import { portalLocation } from "./portal-location";
import { PortalSignIn } from "./portal-auth";
import { stringify } from "yaml";
import { operationMessage } from "./operation-message";
import { ExampleGuide, useExampleGuide, useGuidedForm } from "./lessons/guide";
import { ExampleSetup } from "./lessons/setup";
import { createDocumentRequest, usesBootstrap } from "./create-document";
import { processRuntime } from "./process-context";
import { sourceField, stringifyBlue } from "./blue-values";
import { ImportTimeline } from "./timeline-access";
import { EventsPage, WebhooksPage } from "./events-webhooks";
import { Explorer } from "./explorer";
import { GasPage } from "./gas";
import React, { useState, useEffect, useCallback } from "react";
import { createRoot } from "react-dom/client";
import { contentPath } from "./content-paths";
import { ServiceRecoveryNotice } from "./components/service-recovery-notice";
import { WorkspaceLoading } from "./components/workspace-loading";
import {
  Plus,
  ArrowLeft,
  FileText,
  ListOrdered,
  KeyRound,
  Database,
  Copy,
  Check,
  Play,
  RefreshCw,
  Pause,
  ArrowUpRight,
  Terminal,
} from "lucide-react";
import { api, mutate, mutateYaml, allItems } from "./api";
import { startLive, liveRevision, waitForLiveChange } from "./live";
import { useLiveRefresh } from "./use-live-refresh";
import { DevLayout } from "./components/dev-layout";
import { DataTable } from "./components/data-table";
import { SectionHeader } from "./components/section-header";
import {
  CodeViewer,
  CopyValue,
  PageHeader,
  formatDate,
  LastChange,
} from "./components/viewer";
import "./style.css";
import { DateFilter, withinDates } from "./components/date-filter";
import { PackageProvider } from "./package-data";
import { Packages, TypeDetail, Reference } from "./packages";
import { ResourceList, DocumentDetail, TimelineDetail } from "./resource-pages";
const pretty = (v: any) => stringifyBlue(v, 2);
export function decodeRouteSegment(value?: string) {
  try { return value === undefined ? undefined : decodeURIComponent(value); }
  catch { return value; }
}
const short = (v: any) => (v ? `${String(v).slice(0, 14)}…` : "—");
function CopyButton({ value }: { value: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      className="copy"
      onClick={async () => {
        await navigator.clipboard.writeText(value);
        setDone(true);
        setTimeout(() => setDone(false), 1800);
      }}
    >
      {done ? <Check size={13} /> : <Copy size={13} />}{" "}
      {done ? "Copied" : "Copy"}
    </button>
  );
}
function Raw({ path, tick = 0 }: { path: string; tick?: number }) {
  const [value, setValue] = useState<any>("Loading…"),
    [error, setError] = useState("");
  useEffect(() => {
    let alive = true;
    setValue("Loading…");
    setError("");
    api(path)
      .then((content) => {
        if (alive) {
          setValue(content);
        }
      })
      .catch((e) => {
        if (alive) setError(e.message);
      });
    return () => {
      alive = false;
    };
  }, [path, tick]);
  return error ? (
    <div role="alert" className="error">
      {error}
    </div>
  ) : (
    <CodeViewer value={value} />
  );
}
function Heading({ title, subtitle, action }: any) {
  return (
    <div className="page-heading">
      <div>
        <h1 className="page-title">{title}</h1>
        <p className="page-description">{subtitle}</p>
      </div>
      {action}
    </div>
  );
}
function Empty({ icon: Icon, title, description, action }: any) {
  return (
    <div className="empty-state">
      <div className="empty-icon">
        <Icon size={25} strokeWidth={1.3} />
      </div>
      <h3>{title}</h3>
      <p>{description}</p>
      {action}
    </div>
  );
}
function Badge({ value }: any) {
  return <span className={`tag ${value}`}>{value || "Initializing"}</span>;
}
const sample = `name: Shared notebook\nentries: 0\nlastNote: ""\ncontracts:\n  owner:\n    type: Coordination/Timeline Channel\n    timeline:\n      timelineId: notebook\n    actor:\n      accountId: owner\n  addNote:\n    type: Contracts/Sequential Workflow Operation\n    channel: owner\n    steps:\n      - type: Contracts/Compute\n        do:\n          - $appendChange:\n              op: replace\n              path: /lastNote\n              val:\n                $binding: call/request/note\n          - $appendChange:\n              op: replace\n              path: /entries\n              val:\n                $add:\n                  - $document: /entries\n                  - 1\n`;
function App() {
  const { user: login, account: user, loading, switching, error: connection, reload: load, selectAccount } = useAccounts();
  const [page, setPage] = useState(location.hash.slice(1) || "documents");
  useEffect(() => {
    observability.setAuthenticated(Boolean(login), login?.sessionExpiresAt);
  }, [login]);
  useEffect(() => () => observability.setAuthenticated(false), []);
  useEffect(() => {
    observability.setPublicPage(false);
  }, [page]);
  useEffect(() => { window.scrollTo(0, 0); }, [page.split("?")[0]]);
  useEffect(() => {
    const onHash = () => { captureInvitation(); setPage(location.hash.slice(1) || "documents"); };
    const onHistory = () => {
      window.dispatchEvent(new Event("myos-app-navigation"));
      onHash();
    };
    addEventListener("hashchange", onHash);
    addEventListener("popstate", onHistory);
    return () => {
      removeEventListener("hashchange", onHash);
      removeEventListener("popstate", onHistory);
    };
  }, []);
  const navigate = (p: string, replace = false, source?: "guide") => {
    if ((location.hash.slice(1) || "documents") === p) return;
    if (!replace && source !== "guide") window.dispatchEvent(new Event("myos-app-navigation"));
    // Native history traversal emits popstate; our own updates must not look
    // like a user pressing Back and cancel the guide's internal navigation.
    history[replace ? "replaceState" : "pushState"](history.state, "", `#${p}`);
    setPage(p);
  };
  useEffect(() => { if (user) return startLive(() => {}); }, [user && contextIdentity(accountContext(user))]);
  useEffect(() => {
    const [route, query = ""] = page.split("?");
    if (user && ["signup", "login"].includes(route)) {
      const example = new URLSearchParams(query).get("example");
      navigate(example ? `examples/${encodeURIComponent(example)}` : "documents", true);
    }
  }, [user, page]);
  useEffect(() => {
    if (["home", "why-myos", "developers", "pricing", "examples"].includes(page.split("?")[0])) navigate("documents", true);
    if (page.startsWith("lessons/")) navigate(page.replace(/^lessons\//, "examples/"), true);
  }, [page]);
  if (loading) return <WorkspaceLoading />;
  if (connection) return <main className="main-content"><p role="alert" className="error">{connection}</p><button className="primary" onClick={() => void load()}>Retry</button></main>;
  if (!login) {
    const [path,query=""] = page.split("?");
    const returnTo=new URLSearchParams(query).get("example");
    const documentReturn = /^(documents|lessons|examples|customer-payment)\/[^/?#]+$/.test(path) ? `/${location.search}#${page}` : undefined;
    return <PortalSignIn key={path} redirect={page === "invitation" || page.startsWith("examples/")} returnTo={page === "invitation" ? "/#invitation" : returnTo?`/#examples/${encodeURIComponent(returnTo)}`:documentReturn ?? "/#documents"} />;
  }
  if (page === "invitation") return <InvitationAcceptance key={login.userId} user={login} />;
  const logout = async () => {
    if (impersonation()) { await endImpersonation(); return; }
    window.dispatchEvent(new Event("myos-signing-out"));
    const result = await api("/api/auth/logout", { method: "POST" }, user ? accountContext(user) : userContext(login));
    observability.setAuthenticated(false);
    if (portalLocation.web) { const channel = new BroadcastChannel("myos-session"); channel.postMessage("signed-out"); channel.close(); }
    sessionStorage.removeItem("myos-core-account"); setAccountContext();
    if (result.logoutUrl) window.location.assign(result.logoutUrl); else location.reload();
  };
  if (switching) return <ExampleGuide key={login.userId} user={login} selectAccount={selectAccount} navigate={navigate}><DevLayout page={page} navigate={navigate} userImageUrl={login.imageUrl}><p role="status">Switching participant workspace…</p></DevLayout></ExampleGuide>;
  if (!user && page.split(/[/?]/)[0] === "settings") return <DevLayout page={page} navigate={navigate} userImageUrl={login.imageUrl} logout={logout} workspaceSelector={<a href="#accounts">Choose a workspace</a>}>
    <WorkspaceSettings key={login.userId + login.sessionContext} user={login} page={page} reload={load} />
  </DevLayout>;
  if (!user && page.startsWith("examples/")) return <ExampleGuide key={login.userId} user={login} selectAccount={selectAccount} navigate={navigate}><DevLayout page={page} navigate={navigate} logout={logout}><ExampleSetup key={page.split("?")[0]} id={page.split(/[/?]/)[1]} autoStart={new URLSearchParams(page.split("?")[1]).get("run") === "1"} user={login} reload={load}/></DevLayout></ExampleGuide>;
  if (!user || page === "accounts") return <AccountSelection key={login.userId} user={login} />;
  const [route, search = ""] = page.split("?");
  const [root, encodedId] = route.split("/");
  const id = decodeRouteSegment(encodedId);
  return (
    <ExampleGuide key={login.userId} user={login} account={user} selectAccount={selectAccount} navigate={navigate}>
    <PackageProvider key={contextIdentity(accountContext(user))}>
      <DevLayout
        page={page}
        navigate={navigate}
        accountId={user.accountId}
        userImageUrl={login.imageUrl}
        admin={["OWNER", "ADMIN"].includes(user.membership.role)}
        workspaceSelector={<WorkspaceSelector user={login} account={user} />}
        logout={logout}
      >
        {root === "settings" || root === "access-settings" ? <WorkspaceSettings key={page} user={login} account={user} page={root === "access-settings" ? "settings/access" : page} reload={load} />
        : root === "examples" && id ? <ExampleSetup key={id} id={id} autoStart={new URLSearchParams(search).get("run") === "1"} user={login} account={user} reload={load} />
        : root === "customer-payment" && id ? <CustomerPayment key={`${id}:${search}`} id={id} actionId={new URLSearchParams(search).get("action") ?? ""} navigate={navigate} />
        : root === "documents" ? (
          id === "new" ? (
            <CreateDocument navigate={navigate} />
          ) : id ? (
            <DocumentDetail
              account={user}
              accountId={user.accountId}
              operations={Operations}
              search={search}
              key={id}
              id={id}
              navigate={navigate}
            />
          ) : (
            <ResourceList
              key="documents"
              kind="documents"
              navigate={navigate}
            />
          )
        ) : root === "timelines" ? (
          id === "import" ? <ImportTimeline navigate={navigate} account={user} /> : id && id !== "new" ? (
            <TimelineDetail
              key={id}
              id={id}
              navigate={navigate}
              accountId={user.accountId}
            />
          ) : (
            <ResourceList
              key="timelines"
              kind="timelines"
              navigate={navigate}
              create={id === "new"}
            />
          )
        ) : root === "gas" ? (
          <GasPage />
        ) : root === "events" ? (
          <EventsPage key={id || "events"} id={id} />
        ) : root === "webhooks" ? (
          <WebhooksPage key={id || "webhooks"} id={id} />
        ) : root === "packages" ? (
          <Packages id={id} />
        ) : root === "types" ? (
          <TypeDetail key={id} id={id} />
        ) : root === "references" ? (
          <Reference key={id} id={id} navigate={navigate} />
        ) : root === "api-keys" ? (
          <Keys />
        ) : root === "contents" ? (
          <Contents id={id} navigate={navigate} />
        ) : (
          <Explorer />
        )}
      </DevLayout>
    </PackageProvider>
    </ExampleGuide>
  );
}
function CreateDocument({ navigate }: any) {
  const [source, setSource] = useState(""),
    [mode, setMode] = useState("source"),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const guided = useGuidedForm("bootstrap", c => {setSource(c.source || "");setMode("source");});
  return (
    <>
      <PageHeader title="Start a document" back={() => navigate("documents")} />
      {error && (
        <div role="alert" className="error">
          {error}
        </div>
      )}
      <form ref={guided.form}
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError("");
          try {
            if (guided.command) { await guided.command.execute(); return; }
            const execution = usesBootstrap(source, mode) ? await processRuntime() : undefined;
            const request = createDocumentRequest(source, mode, execution?.runtime, execution?.executionSelection);
            const r = await mutate(request.path, request.body);
            navigate(`documents/${encodeURIComponent(r.sessionId)}`);
          } catch (e: any) {
            setError(e.message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <div className="panel">
          <div className="panel-heading">
            <span className="panel-title">Document source</span>
            <button
              type="button"
              className="link"
              disabled={!!guided.command}
              onClick={() => setSource(sample)}
            >
              Use notebook example
            </button>
          </div>
          <div className="panel-pad">
            <label className="field">
              <span className="subtle">YAML or JSON</span>
              <textarea
                className="editor"
                readOnly={!!guided.command}
                required
                spellCheck={false}
                value={source}
                onChange={(e) => setSource(e.target.value)}
                placeholder={"name: My document\ncontracts:\n  ..."}
              />
            </label>
            <label className="field">
              Input mode
              <select disabled={!!guided.command} value={mode} onChange={(e) => setMode(e.target.value)}>
                <option value="source">
                  Source — create missing timeline bindings
                </option>
                <option value="reference">Reference — existing BlueId</option>
                <option value="exact">
                  Exact — preserve supplied references
                </option>
              </select>
            </label>
            <div className="form-note">
              Source mode accepts type: Package / Type Name from installed
              packages, expands YAML anchors, and binds missing Timeline Channels.
              {" "}Activation-based clause documents use the current package set
              and prepare shared access for the exact account IDs in their parties.
              {" "}A guided example can help you choose accounts before starting.
              Exact mode preserves supplied
              references. Upload additional content in Contents before
              referencing it.
            </div>
          </div>
        </div>
        <div className="inline-actions">
          <button className="primary" disabled={busy}>
            <Play size={15} />
            {busy ? "Starting…" : "Start document"}
          </button>
          <button
            className="secondary"
            type="button"
            onClick={() => navigate("documents")}
          >
            Cancel
          </button>
        </div>
      </form>
    </>
  );
}
function Operations({ id, documentId, versionId, onChange }: any) {
  const guide = useExampleGuide();
  const [ops, setOps] = useState<any[]>([]),
    [ready, setReady] = useState(false),
    [error, setError] = useState("");
  const load = useCallback(() =>
    allItems(`documents/${encodeURIComponent(id)}/operations`)
      .then(setOps)
      .catch((e) => setError(e.message))
      .finally(() => setReady(true)), [id]);
  useEffect(() => {
    load();
  }, [id]);
  useLiveRefresh("documents", id, load);
  return (
    <div className="panel">
      {!ready ? (
        <div className="loading">Loading operations…</div>
      ) : error ? (
        <div className="error">{error}</div>
      ) : ops.length ? (
        ops.map((o) => (
          <Operation key={o.name} op={guide.command?.sessionId === id && guide.command.operation === o.name && !o.routes.some((r:any) => r.timeline === guide.command?.route?.timeline && r.channel === guide.command?.route?.channel) ? {...o,routes:[...o.routes,guide.command.route]} : o} id={id} documentId={documentId} versionId={versionId} onChange={onChange} />
        ))
      ) : (
        <Empty
          icon={Play}
          title="No operations available"
          description="This document may process ordinary timeline messages or be waiting for initialization."
        />
      )}
    </div>
  );
}
function Operation({ op, id, documentId, versionId, onChange }: any) {
  const [request, setRequest] = useState(
      op.name === "addNote" ? '{\n  "note": "Hello from MyOS"\n}' : "{}",
    ),
    [exactVersion, setExactVersion] = useState(true),
    [route, setRoute] = useState("0"),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState(""),
    [error, setError] = useState("");
  const guided = useGuidedForm("operation", c => {
    setRequest(c.request || "{}"); setExactVersion(c.exact ?? true);
    setRoute(String(op.routes.findIndex((r:any) => r.timeline === c.route?.timeline && r.channel === c.route?.channel)));
  }, id, op.name);
  return (
    <form
      ref={guided.form}
      className="operation"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError("");
        setMessage("");
        try {
          if (guided.command) {await guided.command.execute(versionId); onChange(); return;}
          const r = op.routes[Number(route)];
          const message = operationMessage(documentId, versionId, r?.channel, op.name, request, exactVersion);
          const result = await mutateYaml(`documents/${encodeURIComponent(id)}/operations`,
            stringify({timeline: {blueId: r?.timeline}, actor: r?.actor, inputMode: "source"}) +
            sourceField("message", message));
          setMessage(
            `Input accepted: ${result.entryRef.blueId}. Waiting for processing…`,
          );
          onChange();
          const deadline = Date.now() + 15000;
          while (true) {
            const revision = liveRevision();
            const outcome = await api(
              `documents/${encodeURIComponent(id)}/inputs/${encodeURIComponent(result.entryRef.blueId)}`,
            );
            if (outcome.disposition) {
              setError(
                `${outcome.disposition.outcome}: ${outcome.disposition.reason || "See the input evidence for details"}`,
              );
              setMessage(`Input retained: ${result.entryRef.blueId}`);
              break;
            }
            if (outcome.localHistory?.length) {
              setMessage(
                `Processed. Review the document and History for the result. Input: ${result.entryRef.blueId}`,
              );
              onChange();
              break;
            }
            if (Date.now() >= deadline) {
              setMessage(
                `Input accepted: ${result.entryRef.blueId}. Processing continues automatically; inspect Processing for progress.`,
              );
              break;
            }
            await waitForLiveChange(revision, "documents", id, deadline - Date.now());
          }
        } catch (e: any) {
          setError(e.message);
        } finally {
          setBusy(false);
        }
      }}
    >
      <SectionHeader title={op.name} subtitle={`Channel: ${op.channel}`} />
      {op.request && (
        <details>
          <summary>Request shape</summary>
          <pre className="code">{pretty(op.request)}</pre>
        </details>
      )}
      <label className="field" style={{ marginTop: 18 }}>
        Request (JSON or YAML)
        <textarea
          readOnly={!!guided.command}
          spellCheck={false}
          value={request}
          onChange={(e) => setRequest(e.target.value)}
          style={{ minHeight: 100 }}
        />
      </label>
      {op.routes?.length > 0 && (
        <label className="field">
          Use timeline
          <select disabled={!!guided.command} value={route} onChange={(e) => setRoute(e.target.value)}>
            {op.routes.map((r: any, i: number) => (
              <option value={i} key={i}>
                {r.timelineName || r.channel} · {short(r.timeline)}
              </option>
            ))}
          </select>
        </label>
      )}
      <label className="field">
        <span><input type="checkbox" disabled={!!guided.command} checked={exactVersion} onChange={e => setExactVersion(e.target.checked)} /> Require the displayed document version</span>
        <small>Keep this enabled for approvals. If the document changes before processing, review the new state and submit again.</small>
      </label>
      {error && (
        <div role="alert" className="error">
          {error}
        </div>
      )}
      {message && <div className="success">{message}</div>}
      <button className="primary" disabled={busy || !op.routes?.length || (exactVersion && !versionId)}>
        <Play size={14} />
        {busy ? "Submitting…" : "Invoke operation"}
      </button>
    </form>
  );
}
function Keys() {
  const [rows, setRows] = useState<any[]>([]),
    [name, setName] = useState(""),
    [secret, setSecret] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const load = () =>
    listOwnApiKeys()
      .then(setRows)
      .catch((e) => setError(e.message));
  useEffect(() => {
    load();
  }, []);
  return (
    <>
      <Heading
        title="API Keys"
        subtitle="Connect your application to the same workspace you use here."
      />
      {error && (
        <div role="alert" className="error">
          {error}
        </div>
      )}
      {secret && (
        <div className="success" data-dd-privacy="hidden">
          <strong>Your new API key</strong>
          <p>
            Copy and store this secret now. It won’t appear in your key list.
          </p>
          <div className="key-value">{secret}</div>
          <CopyButton value={secret} />
          <button
            className="link"
            style={{ marginLeft: 20 }}
            onClick={() => setSecret("")}
          >
            Dismiss
          </button>
        </div>
      )}
      <form
        className="panel panel-pad"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError("");
          try {
            const r = await mutate("api-keys", { name });
            setSecret(r.secret);
            setName("");
            load();
          } catch (e: any) {
            setError(e.message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <SectionHeader
          title="Create an API key"
          subtitle="Keys can use your documents and timelines. They cannot create other keys."
        />
        <label className="field" style={{ marginTop: 20 }}>
          Key name
          <input
            required
            placeholder="e.g. Development laptop"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <button className="primary" disabled={busy}>
          <Plus size={15} />
          {busy ? "Creating…" : "Create key"}
        </button>
      </form>
      <div className="panel">
        <DataTable
          columns={[
            { key: "name", label: "Name" },
            {
              key: "keyPrefix",
              label: "Key prefix",
              render: (r: any) => <span className="mono">{r.keyPrefix}…</span>,
            },
            {
              key: "status",
              label: "Status",
              render: (r: any) => (
                <Badge value={r.status === "revoked" ? "Revoked" : "Active"} />
              ),
            },
            {
              key: "actions",
              label: "",
              render: (r: any) =>
                r.status === "active" && (
                  <button
                    className="link"
                    onClick={async () => {
                      try {
                        await mutate(`api-keys/${encodeURIComponent(r.id)}`, {}, "DELETE");
                        load();
                      } catch (e: any) {
                        setError(e.message);
                      }
                    }}
                  >
                    Revoke
                  </button>
                ),
            },
          ]}
          data={rows}
          emptyMessage="No API keys yet."
        />
      </div>
      <div className="panel">
        <div className="panel-heading">
          <span className="panel-title">Make your first request</span>
          <CopyButton
            value={`curl '${portalLocation.api || location.origin}/api/blue/v1/documents' -H 'Authorization: Bearer YOUR_API_KEY'`}
          />
        </div>
        <pre className="code">{`curl '${portalLocation.api || location.origin}/api/blue/v1/documents' \\\n  -H 'Authorization: Bearer YOUR_API_KEY'`}</pre>
      </div>
    </>
  );
}
function Contents({
  id,
  navigate,
}: {
  id?: string;
  navigate: (path: string) => void;
}) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [undated, setUndated] = useState(false);
  const [source, setSource] = useState(""),
    [result, setResult] = useState(""),
    [rows, setRows] = useState<any[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [showNew, setShowNew] = useState(false),
    [showFilter, setShowFilter] = useState(false),
    [filter, setFilter] = useState(""),
    [limit, setLimit] = useState(10),
    [pageSize, setPageSize] = useState(10);
  const filtered = rows.filter(
    (r) =>
      `${r.name} ${r.blueId}`.toLowerCase().includes(filter.toLowerCase()) &&
      withinDates(r.lastChangedAt ?? r.createdAt, from, to, undated),
  );
  useEffect(() => setLimit(pageSize), [filter, from, to, undated]);
  const load = async () => {
    try {
      let cursor = "",
        items: any[] = [];
      do {
        const r = await api(
          `contents?limit=100${cursor ? "&pageToken=" + encodeURIComponent(cursor) : ""}`,
        );
        items.push(...r.items);
        cursor = r.nextPageToken || "";
      } while (cursor);
      setRows(items);
      setError("");
    } catch (e: any) {
      setError(e.message);
    }
  };
  useEffect(() => {
    if (!id) load();
  }, [id]);
  if (id)
    return (
      <>
        <PageHeader title="Content Details" back={() => navigate("contents")} />
        <section className="panel panel-pad">
          <h3>Details</h3>
          <span className="id-cell">
            <span>
              <strong>Blue ID:</strong> {id}
            </span>
            <CopyValue value={id} />
          </span>
        </section>
        <section className="panel panel-pad">
          <Raw path={contentPath(id)} />
        </section>
      </>
    );
  return (
    <>
      <PageHeader
        title="Contents"
        actions={
          <button
            className="outline-button"
            onClick={() => setShowNew(!showNew)}
          >
            Add new
          </button>
        }
      />
      {error && (
        <div role="alert" className="error">
          {error}
        </div>
      )}
      {result && (
        <div className="success">
          Content retained: <span className="mono">{result}</span>{" "}
          <CopyButton value={result} />
        </div>
      )}
      {showNew && (
        <form
          className="panel panel-pad"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError("");
            try {
              const r = await mutateYaml("contents", "inputMode: source\n" + sourceField("content", source));
              setResult(r.contentRef.blueId);
              load();
            } catch (e: any) {
              setError(e.message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label className="field">
            Content (JSON or YAML)
            <textarea
              required
              value={source}
              onChange={(e) => setSource(e.target.value)}
              placeholder="Paste content to retain…"
              spellCheck={false}
            />
          </label>
          <button className="primary" disabled={busy}>
            {busy ? "Retaining…" : "Retain content"}
          </button>
        </form>
      )}
      <div className="panel panel-pad">
        <div className="table-toolbar">
          <button
            className="filter-button"
            onClick={() => setShowFilter(!showFilter)}
          >
            Filter{filter || from || to ? " •" : ""}
          </button>
          <button className="plain-icon" aria-label="Refresh" onClick={load}>
            <RefreshCw size={18} />
          </button>
        </div>
        {showFilter && (
          <div className="filter-panel">
            <label className="field">
              Find content
              <input
                value={filter}
                onChange={(e) => {
                  setFilter(e.target.value);
                  setLimit(10);
                }}
                placeholder="Search name or Blue ID"
              />
            </label>
            <DateFilter
              dateKind="change"
              from={from}
              to={to}
              undated={undated}
              onChange={(a, b, c) => {
                setFrom(a);
                setTo(b);
                setUndated(c);
              }}
            />
            <button
              className="link"
              onClick={() => {
                setFilter("");
                setFrom("");
                setTo("");
                setUndated(false);
              }}
            >
              Clear
            </button>
          </div>
        )}
        <DataTable
          columns={[
            {
              key: "blueId",
              label: "Blue ID",
              render: (r: any) => (
                <span className="id-cell">
                  <button
                    className="link"
                    title={r.blueId}
                    onClick={() => navigate(`contents/${encodeURIComponent(r.blueId)}`)}
                  >
                    {r.blueId}
                  </button>
                  <CopyValue value={r.blueId} />
                </span>
              ),
            },
            { key: "name", label: "Name", className: "name-column" },
            {
              key: "lastChangedAt",
              label: "Last Change",
              className: "date-column",
              render: (r: any) => <LastChange value={r.lastChangedAt ?? r.createdAt} />,
            },
          ]}
          data={filtered.slice(0, limit)}
          emptyMessage="No uploaded content yet."
        />
        <div className="pagination">
          <button
            className="outline-button"
            disabled={limit >= filtered.length}
            onClick={() => setLimit(limit + pageSize)}
          >
            Load more
          </button>
          <label>
            Items per page:{" "}
            <select
              value={pageSize}
              onChange={(e) => {
                setPageSize(+e.target.value);
                setLimit(+e.target.value);
              }}
            >
              {[10, 20, 50, 100].map((n) => (
                <option key={n}>{n}</option>
              ))}
            </select>
          </label>
          <span className="subtle">{filtered.length} matching contents</span>
        </div>
      </div>
    </>
  );
}


const returningToAdmin = captureAdminReturn();
captureImpersonation();
captureInvitation();
function RootApp() {
  const [route, setRoute] = useState(location.hash);
  useEffect(() => { const update = () => setRoute(location.hash); addEventListener("hashchange", update); return () => removeEventListener("hashchange", update); }, []);
  return route === "#admin" ? <AdminPanel returning={returningToAdmin} /> : <ImpersonationBoundary><App /></ImpersonationBoundary>;
}
createRoot(document.getElementById("root")!, {
  onCaughtError: (error) => observability.reportError(error),
  onUncaughtError: (error) => observability.reportError(error),
}).render(<><ServiceRecoveryNotice /><RootApp /><PrivacyPreferences autoPrompt={false} /></>);
