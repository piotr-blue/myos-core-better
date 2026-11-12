import { AccountPreparationNotice } from "./account-preparation-notice";
import type { AccountIdentity } from "@blue-labs/myos-sdk";
import { ProcessOverview, ProcessTerms, processView } from "./process-views";
import { useResourcePage } from "./use-resource-page";
import { stringify } from "yaml";
import { sourceField, stringifyBlue } from "./blue-values";
import { readDocument, readPosition, readTimelineEntries } from "./resource-reads";
import { DateFilter } from "./components/date-filter";
import { EpochEvents } from "./epoch-events";
import { useState, useEffect, useCallback } from "react";
import {
  RefreshCw,
  Pause,
  Play,
  Plus,
  ChevronDown,
  ChevronUp,
  ArrowUpRight,
  CirclePause,
  Trash2,
  Copy,
} from "lucide-react";
import { api, mutate, mutateYaml, allItems } from "./api";
import { contentPath } from "./content-paths";
import { affects, subscribeLive } from "./live";
import { useLiveRefresh } from "./use-live-refresh";
import { Dialog } from "./components/dialog";
import { DataTable } from "./components/data-table";
import {
  PageHeader,
  CopyValue,
  CodeViewer,
  BlueId,
  EpochNavigation,
  formatDate,
  DateTime,
  LastChange,
  processingLabel,
} from "./components/viewer";
import { TimelineAccess } from "./timeline-access";
import { BootstrapDocument } from "./bootstrap-document";
type Props = { id: string; navigate: (p: string) => void };
const lastChange = (row: any) => row.lastChangedAt ?? row.createdAt;
function ErrorBox({ error }: { error: string }) {
  return error ? (
    <div role="alert" className="error">
      {error}
    </div>
  ) : null;
}
function Refresh({ onClick }: { onClick: () => void }) {
  return (
    <button
      className="plain-icon"
      title="Refresh"
      aria-label="Refresh"
      onClick={onClick}
    >
      <RefreshCw size={18} />
    </button>
  );
}
export function ResourceList({ kind, navigate, create = false }: any) {
  const [error, setError] = useState("");
  const [filter, setFilter] = useState("");
  const [showFilter, setShowFilter] = useState(false);
  const [statusFilter, setStatusFilter] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [undated, setUndated] = useState(false);
  const [pageSize, setPageSize] = useState(10);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [selection, setSelection] = useState<string[]>([]);
  const [copied, setCopied] = useState(false);
  const docs = kind === "documents";
  const resourceId = (row: any) => docs ? row.sessionId : row.timelineId;
  const query = new URLSearchParams({ limit: String(pageSize) });
  if (filter) query.set("q", filter);
  if (statusFilter && docs) query.set("status", statusFilter);
  if (from) query.set("createdFrom", from);
  if (to) query.set("createdTo", to);
  if (undated) query.set("includeUndated", "true");
  const { rows: visible, total, next, ready, loading, readState, error: readError, refresh: load, more } = useResourcePage(kind, query.toString());
  useEffect(() => setSelection([]), [kind, filter, statusFilter, from, to, undated, pageSize]);
  return (
    <>
      <PageHeader
        title={docs ? "Documents" : "Timelines"}
        actions={
          <div className="inline-actions">
          {docs && <BootstrapDocument navigate={navigate}/>}
          {!docs && <button className="outline-button" onClick={()=>navigate('timelines/import')}>Import</button>}
          <button
            className="outline-button"
            onClick={() => navigate(`${kind}/new`)}
          >
            Add new
          </button>
          </div>
        }
      />
      {docs && <AccountPreparationNotice state={readState} />}
      <ErrorBox error={error || readError} />
      {create && (
        <Dialog title="New timeline" onClose={() => navigate("timelines")}>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              setBusy(true);
              setError("");
              try {
                const r = await mutate("timelines", { name });
                navigate(`timelines/${encodeURIComponent(r.timelineId)}`);
              } catch (e: any) {
                setError(e.message);
              } finally {
                setBusy(false);
              }
            }}
          >
            <label className="field">
              Name
              <input
                required
                autoFocus
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Timeline name"
              />
            </label>
            <ErrorBox error={error} />
            <div className="dialog-footer">
              <button
                type="button"
                className="secondary"
                onClick={() => navigate("timelines")}
              >
                Cancel
              </button>
              <button className="primary" disabled={busy}>
                {busy ? "Creating…" : "Create timeline"}
              </button>
            </div>
          </form>
        </Dialog>
      )}
      <section className="panel table-panel">
        <div className="table-toolbar">
          <div className="inline-actions">
            <button
              className="filter-button"
              aria-expanded={showFilter}
              onClick={() => setShowFilter(!showFilter)}
            >
              Filter{filter || statusFilter || from || to ? " •" : ""}
            </button>
            {selection.length > 0 && (
              <>
                <span className="selection-count">
                  {selection.length} selected
                </span>
                <button
                  className="secondary"
                  onClick={async () => {
                    await navigator.clipboard.writeText(selection.join("\n"));
                    setCopied(true);
                    setTimeout(() => setCopied(false), 1500);
                  }}
                >
                  <Copy size={16} />
                  {copied ? "Copied" : "Copy IDs"}
                </button>
              </>
            )}
          </div>
          <Refresh onClick={load} />
        </div>
        {showFilter && (
          <div className="filter-panel">
            <label className="field">
              Name or ID
              <input
                autoFocus
                value={filter}
                onChange={(e) => {
                  setFilter(e.target.value);
                  setSelection([]);
                }}
                placeholder="Search…"
              />
            </label>
            {docs && (
              <label className="field">
                Status
                <select
                  value={statusFilter}
                  onChange={(e) => {
                    setStatusFilter(e.target.value);
                  }}
                >
                  <option value="">All statuses</option>
                  <option value="READY">Running</option>
                  <option value="PROCESSING">Processing</option>
                  <option value="WAITING">Waiting</option>
                  <option value="PAUSED">Paused</option>
                  <option value="ERROR">Error</option>
                  <option value="HOST_INCOMPLETE">Host incomplete</option>
                </select>
              </label>
            )}
            <DateFilter
              dateKind="creation"
              from={from}
              to={to}
              undated={undated}
              onChange={(f, t, u) => {
                setFrom(f);
                setTo(t);
                setUndated(u);
              }}
            />
            <button
              className="text-button"
              onClick={() => {
                setFilter("");
                setStatusFilter("");
                setFrom("");
                setTo("");
                setUndated(false);
                setSelection([]);
              }}
            >
              Clear
            </button>
          </div>
        )}
        {!ready ? (
          <div className="loading">Loading…</div>
        ) : (
          <DataTable
            data={visible}
            columns={[
              {
                key: "select",
                className: "select-column",
                label: (
                  <input
                    type="checkbox"
                    aria-label={`Select all ${kind}`}
                    checked={
                      visible.length > 0 &&
                      visible.every((r) => selection.includes(resourceId(r)))
                    }
                    onChange={(e) =>
                      setSelection(
                        e.target.checked ? visible.map((r) => resourceId(r)) : [],
                      )
                    }
                  />
                ),
                render: (r: any) => (
                  <input
                    type="checkbox"
                    aria-label={`Select ${r.name || resourceId(r)}`}
                    checked={selection.includes(resourceId(r))}
                    onChange={(e) =>
                      setSelection(
                        e.target.checked
                          ? [...selection, resourceId(r)]
                          : selection.filter((id) => id !== resourceId(r)),
                      )
                    }
                  />
                ),
              },
              {
                key: "id",
                label: docs ? "Session ID" : "Timeline ID",
                className: "id-column",
                render: (r: any) => (
                  <span className="id-cell">
                    <a className="link" title={resourceId(r)} href={`#${kind}/${encodeURIComponent(resourceId(r))}`}>
                      {resourceId(r)}
                    </a>
                    <CopyValue value={resourceId(r)} />
                  </span>
                ),
              },
              {
                key: "name",
                label: "Name",
                className: "name-column",
                render: (r: any) => (
                  <button
                    className="text-button"
                    onClick={() => navigate(`${kind}/${encodeURIComponent(resourceId(r))}`)}
                  >
                    {r.name}
                  </button>
                ),
              },
              ...(docs
                ? [
                    {
                      key: "processing",
                      label: "Processing status",
                      className: "status-column",
                      render: (r: any) => processingLabel(r.processing?.state),
                    },
                  ]
                : [{ key: "owned", label: "Owned", render: () => "Yes" }]),
              {
                key: "lastChangedAt",
                label: "Last Change",
                className: "date-column",
                render: (r: any) => <LastChange value={lastChange(r)} />,
              },
            ]}
          />
        )}
        <div className="pagination">
          <button
            className="outline-button"
            disabled={loading || !next}
            onClick={() => { void more(); }}
          >
            {loading ? "Loading…" : "Load more"}
          </button>
          <label>
            Items per page:{" "}
            <select
              value={pageSize}
              onChange={(e) => {
                setPageSize(+e.target.value);
                setSelection([]);
              }}
            >
              {[10, 20, 50, 100].map((n) => (
                <option key={n}>{n}</option>
              ))}
            </select>
          </label>
          <span className="subtle">
            {visible.length} of {total}
          </span>
        </div>
      </section>
    </>
  );
}
function DeleteDocumentDialog({
  document,
  onClose,
  onDeleted,
}: {
  document: any;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  return (
    <Dialog title="Delete document" onClose={onClose}>
      <p>
        Remove <strong>{document.name}</strong> from your documents? Processing continues.
        Adding this document again restores access to its current state and history.
      </p>
      <p className="mono">{document.sessionId}</p>
      <ErrorBox error={error} />
      <div className="dialog-footer">
        <button className="secondary" disabled={busy} onClick={onClose}>
          Cancel
        </button>
        <button
          className="primary danger"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            try {
              await mutate(
                `documents/${encodeURIComponent(document.sessionId)}`,
                {},
                "DELETE",
              );
              onDeleted();
            } catch (e: any) {
              setError(e.message);
            } finally {
              setBusy(false);
            }
          }}
        >
          {busy ? "Deleting…" : "Delete"}
        </button>
      </div>
    </Dialog>
  );
}
export function DocumentDetail({
  id,
  navigate,
  operations: Operations, account, search = "",
}: Props & { operations: any; accountId: string; account?: AccountIdentity; search?: string }) {
  const params = new URLSearchParams(search);
  const requestedTab = params.get("view");
  const chosenTab = ["Overview", "Terms", "State", "Source", "Operations", "Timelines", "Timers", "Links", "History", "Processing"]
    .find(tab => tab === requestedTab);
  const requestedEpoch = params.get("epoch");
  const selected = requestedEpoch !== null && /^\d+$/.test(requestedEpoch) && Number.isSafeInteger(Number(requestedEpoch))
    ? Number(requestedEpoch) : null;
  const showView = (view: string | undefined, epoch: number | null = selected) => {
    const next = new URLSearchParams(search);
    if (view) next.set("view", view); else next.delete("view");
    if (epoch !== null) next.set("epoch", String(epoch)); else next.delete("epoch");
    navigate(`documents/${encodeURIComponent(id)}${next.size ? `?${next}` : ""}`);
  };
  const [doc, setDoc] = useState<any>(),
    [error, setError] = useState(""),
    [epoch, setEpoch] = useState<any>(),
    [children, setChildren] = useState<any[]>([]),
    [history, setHistory] = useState<any[]>([]),
    [timers, setTimers] = useState<any[]>([]),
    [query, setQuery] = useState(""),
    [deleting, setDeleting] = useState(false),
    [controlling, setControlling] = useState(false);
  const load = useCallback(async () => {
    try {
      setDoc(await readDocument(id));
      setError("");
    } catch (e: any) {
      setError(e.message);
    }
  }, [id]);
  useEffect(() => {
    let alive = true;
    let version = "", stableVersion = "", instance = id;
    const stableKey = (value: any) => stringifyBlue([value.documentId, value.lastStable?.documentRef?.blueId, value.lastStable?.epoch]);
    let running = false, dirty = false;
    const refresh = async () => {
      if (running) { dirty = true; return; }
      running = true;
      try {
        const status = await api(`documents/${encodeURIComponent(id)}`);
        instance = status.instanceId;
        const next = stringifyBlue(status);
        if (alive && next !== version) {
          if (stableKey(status) !== stableVersion) {
            const detail = await readDocument(id, status);
            if (alive) { setDoc(detail); stableVersion = stableKey(detail); }
          } else {
            // Worker status changes do not require reloading an unchanged document.
            setDoc((current: any) => current && stableKey(current) === stableKey(status)
              ? { ...current, ...status, lastStable: current.lastStable } : current);
          }
          if (alive) version = next;
        }
        if (alive) setError("");
      } catch (e: any) {
        if (alive) setError(e.message);
      } finally {
        running = false;
        if (alive && dirty) { dirty = false; void refresh(); }
      }
    };
    const unsubscribe = subscribeLive(change => { if (affects(change, "documents", id) || affects(change, "documents", instance)) void refresh(); });
    void refresh();
    return () => { alive = false; unsubscribe(); };
  }, [id]);
  useEffect(() => {
    let alive = true;
    if (selected !== null)
      api(`documents/${encodeURIComponent(id)}/epochs/${selected}`)
        .then(readPosition)
        .then(value => { if (alive) setEpoch(value); })
        .catch(e => { if (alive) setError(e.message); });
    else setEpoch(undefined);
    return () => { alive = false; };
  }, [id, selected]);
  useEffect(() => {
    if (chosenTab === "Timers")
      allItems(`documents/${encodeURIComponent(id)}/timers`)
        .then(setTimers)
        .catch((e) => setError(e.message));
    if (chosenTab === "Links")
      allItems(`documents/${encodeURIComponent(id)}/relationships`)
        .then(setChildren)
        .catch((e) => setError(e.message));
    if (chosenTab === "History") {
      (async () => {
        let cursor = "",
          items: any[] = [];
        do {
          const r = await api(
            `documents/${encodeURIComponent(id)}/history?limit=100${cursor ? "&pageToken=" + encodeURIComponent(cursor) : ""}`,
          );
          items.push(...r.items);
          cursor = r.nextPageToken || "";
        } while (cursor);
        setHistory(items);
      })().catch((e) => setError(e.message));
    }
  }, [id, chosenTab, doc?.lastStable?.documentRef?.blueId]);
  const control = async (action: string) => {
    setControlling(true);
    try {
      const gasEvaluation = doc?.processing?.reason?.details?.gasEvaluation;
      await mutate(`documents/${encodeURIComponent(id)}/${action}`,
        action === "retry" && gasEvaluation !== undefined
          ? {gasEvaluation} : {});
      await load();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setControlling(false);
    }
  };
  if (!doc)
    return (
      <>
        <ErrorBox error={error} />
        <div className="loading">Opening document…</div>
      </>
    );
  const paused =
    doc.control === "PAUSED" || doc.processing?.state === "PAUSED";
  const processingReason = doc.processing?.reason;
  const processingDetails = processingReason?.details;
  const processingError = ["ERROR", "HOST_INCOMPLETE"].includes(
    doc.processing?.state,
  );
  const stable = doc.lastStable,
    current = selected ?? stable?.epoch ?? 0;
  const value =
    selected === null
      ? stable?.document
      : epoch?.epoch === selected
        ? epoch?.document
        : undefined;
  const tab = chosenTab ?? (processView(value) ? "Overview" : "State");
  return (
    <div data-document-session={id}>
      <PageHeader
        title={doc.name || "Document"}
        back={() => navigate("documents")}
        actions={
          <div className="inline-actions">
            <button
              className={`plain-icon ${paused ? "" : "pause-action"}`}
              title={paused ? "Resume processing" : "Pause processing"}
              aria-label={paused ? "Resume processing" : "Pause processing"}
              disabled={controlling}
              onClick={() => control(paused ? "resume" : "pause")}
            >
              {paused ? <Play size={16} /> : <CirclePause size={16} />}
            </button>
            {doc.canUnlink && (
              <button
                className="plain-icon danger"
                title="Delete document"
                aria-label="Delete document"
                onClick={() => setDeleting(true)}
              >
                <Trash2 size={16} />
              </button>
            )}
            <Refresh onClick={load} />
          </div>
        }
      />
      <ErrorBox error={error} />
      {deleting && (
        <DeleteDocumentDialog
          document={doc}
          onClose={() => setDeleting(false)}
          onDeleted={() => navigate("documents")}
        />
      )}
      <dl className="panel metadata-grid">
        <div>
          <dt>Session ID</dt>
          <dd className="id-cell">
            <span>{id}</span>
            <CopyValue value={id} />
          </dd>
        </div>
        <div>
          <dt title="Stable identity before initialization; use this to target timeline operations">Document ID</dt>
          <dd className="id-cell">
            {doc.documentId ? <><BlueId value={doc.documentId} /><CopyValue value={doc.documentId} /></> : "Initializing…"}
          </dd>
        </div>
        <div>
          <dt>State BlueId</dt>
          <dd className="id-cell">
            <span>
              {(selected === null ? stable?.documentRef?.blueId : epoch?.documentRef?.blueId) ? (
                <BlueId
                  value={selected === null ? stable.documentRef.blueId : epoch.documentRef.blueId}
                />
              ) : (
                "Loading…"
              )}
            </span>
            {(selected === null ? stable?.documentRef?.blueId : epoch?.documentRef?.blueId) && (
              <CopyValue
                value={selected === null ? stable.documentRef.blueId : epoch.documentRef.blueId}
              />
            )}
          </dd>
        </div>
        <div>
          <dt>Processing status</dt>
          <dd>{paused ? "Paused" : processingLabel(doc.processing?.state)}</dd>
        </div>
        <div>
          <dt>Created</dt>
          <dd>
            <DateTime value={doc.createdAt} />
          </dd>
        </div>
        <div>
          <dt>Updated</dt>
          <dd>
            <DateTime value={doc.lastChangedAt} />
          </dd>
        </div>
        <div>
          <dt>Epoch</dt>
          <dd>
            {stable ? (
              <EpochNavigation
                current={current}
                latest={stable.epoch}
                onChange={epoch => showView(chosenTab, epoch)}
              />
            ) : (
              "Waiting for initial processing"
            )}
          </dd>
        </div>
      </dl>
      {processingReason && !processingError && processingReason.code !== "WAITING_TIMER" && (
        <div className="notice" role="status">
          <strong>{paused ? "Paused" : processingReason.code === 'TIMELINE_UNAVAILABLE' ? 'Timeline unavailable' : 'Waiting'}</strong>
          <p>
            {processingReason.code === "WAITING_ACCOUNT_GAS"
              ? `Waiting for gas: ${processingDetails?.required} required, ${processingDetails?.available} available. Processing resumes automatically after funding.`
              : processingReason.message || "Waiting for processing requirements to be met."}
          </p>
          {processingDetails?.timeline && <a href={`#timelines/${encodeURIComponent(processingDetails.timeline)}`}>Open timeline and retry</a>}
          {processingDetails?.documents
            ?.filter((child: string) => child !== id)
            .map((child: string) => (
              <p key={child}>
                <a href={`#documents/${encodeURIComponent(child)}`}>Open paused document {child}</a>
              </p>
            ))}
          {paused && (
            <button className="link" onClick={() => control("resume")}>
              Resume processing
            </button>
          )}
        </div>
      )}
      {processingReason && processingError && (
        <div className="error" role="alert">
          <CodeViewer value={processingReason} label="Processing error" />
          {doc.processing.retryable && (
            <button className="link" onClick={() => control("retry")}>
              {processingDetails?.gasEvaluation !== undefined ? "Retry processing (gas may be charged again)" : "Retry processing"}
            </button>
          )}
        </div>
      )}
      <section className="panel detail-panel">
        <div className="tabs" role="tablist" aria-label="Document views">
          {[
            ...(processView(value) ? ["Overview", "Terms"] : []),
            "State",
            "Source",
            "Operations",
            "Timelines",
            "Timers",
            "Links",
            "History",
            "Processing",
          ].map((t) => (
            <button
              key={t}
              role="tab"
              aria-selected={tab === t}
              className={`tab ${tab === t ? "active" : ""}`}
              onClick={() => { if (tab !== t) showView(t); }}
            >
              {t}
            </button>
          ))}
        </div>
        {tab === "Overview" ? (
          <ProcessOverview value={value} sessionId={id} />
        ) : tab === "Terms" ? (
          <ProcessTerms value={value} />
        ) : tab === "Source" ? (
          <CodeViewer
            value={doc.source ?? stable?.document}
            label={doc.source ? "Source" : "Document"}
            compact
          />
        ) : tab === "State" ? (
          <CodeViewer
            value={value}
            label={`Document · Epoch ${current}`}
            compact
          />
        ) : tab === "Operations" ? (
          <Operations id={id} documentId={doc.documentId} versionId={doc.lastStable?.documentRef?.blueId} onChange={load} />
        ) : tab === "Timelines" ? (
          <DataTable
            data={doc.channels || []}
            columns={[
              { key: "channel", label: "Channel" },
              {
                key: "timeline",
                label: "Timeline",
                render: (r: any) => (
                  <button
                    className="link id-link"
                    onClick={() => navigate(`references/${encodeURIComponent(r.timeline)}`)}
                  >
                    {r.timeline}
                  </button>
                ),
              },
              { key: "actor", label: "Actor", className: "id-link" },
            ]}
          />
        ) : tab === "Timers" ? (
          <>
            <h3>Registered timers</h3>
            <p className="subtle">{timers.length ? `${timers.length} scheduled ${timers.length === 1 ? "timer" : "timers"}. Each runs once when due, after earlier changes are processed.` : "No registered timers."}</p>
            <DataTable
              data={timers}
              columns={[
                { key: "key", label: "Key", render: (r: any) => r.key || "Unnamed" },
                { key: "channel", label: "Channel" },
                { key: "timestamp", label: "Deadline", render: (r: any) => <span title={`${r.timestamp} microseconds`}>{formatDate(Number(r.timestamp) / 1000)}</span> },
                { key: "entry", label: "Timer entry", render: (r: any) => <BlueId value={r.entry} /> },
              ]}
            />
          </>
        ) : tab === "Links" ? (
          <>
            <label className="field">
              Find a relationship
              <input
                placeholder="Search by path or document reference"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
            </label>
            <p className="subtle">{children.length} direct relationships. Source supplies state to target.</p>
            <DataTable
              data={children.filter((edge) =>
                [edge.path, edge.source.documentRef?.blueId, edge.target.documentRef?.blueId]
                  .some(value => String(value || "").toLowerCase().includes(query.toLowerCase())))}
              columns={[
                { key: "direction", label: "Direction" },
                { key: "path", label: "Path" },
                ...["source", "target"].map(key => ({
                  key, label: key === "source" ? "Source" : "Target",
                  render: (edge: any) => edge[key].sessionId ? (
                    <button className="link" onClick={() => navigate(`documents/${encodeURIComponent(edge[key].sessionId)}`)}>
                      {edge[key].documentRef?.blueId || edge[key].sessionId}
                    </button>
                  ) : edge[key].documentRef ? <BlueId value={edge[key].documentRef.blueId} /> : "Unavailable",
                })),
                { key: "generation", label: "Generation" },
                { key: "state", label: "State" },
              ]}
            />
          </>
        ) : tab === "History" ? (
          <>
            <p>{history.length} retained transitions</p>
            <DataTable
              data={[...history].reverse()}
              columns={[
                {
                  key: "epoch",
                  label: "Epoch",
                  render: (h: any) => h.stable ? (
                    <button
                      className="link"
                      onClick={() => showView("State", Number(h.epoch))}
                    >
                      Epoch {h.epoch}
                    </button>
                  ) : "Transition",
                },
                {
                  key: "kind",
                  label: "Action",
                  render: (h: any) =>
                    h.kind || h.action || h.phase || "Publication",
                },
                { key: "documentRef", label: "State BlueId", render: (h: any) => <BlueId value={h.documentRef?.blueId} /> },
              ]}
            />
          </>
        ) : (
          <CodeViewer value={doc.processing} label="Processing" />
        )}
      </section>
      <EpochEvents id={id} epoch={selected ?? stable?.epoch} />
    </div>
  );
}
function EntryCard({
  entry,
  messageOnly,
  expanded,
}: {
  entry: any;
  messageOnly: boolean;
  expanded: boolean;
}) {
  const [open, setOpen] = useState(expanded),
    [content, setContent] = useState<any>(),
    [error, setError] = useState("");
  useEffect(() => setOpen(expanded), [expanded]);
  useEffect(() => {
    let active = true;
    if (open) {
      setContent(undefined);
      const message = entry.entry?.message;
      const id = messageOnly ? message?.blueId : entry.blueId;
      if (messageOnly && message !== undefined && !id) {
        setContent(message);
        setError("");
        return () => { active = false; };
      }
      api(contentPath(id || entry.blueId))
        .then((value) => {
          if (active) {
            setContent(messageOnly && !id ? value?.message : value);
            setError("");
          }
        })
        .catch((e) => {
          if (active) setError(e.message);
        });
    }
    return () => {
      active = false;
    };
  }, [open, messageOnly, entry.blueId]);
  return (
    <article className="entry-card">
      <div className="entry-heading">
        <div>
          <div className="entry-title-row">
            <span className="id-cell">
              <a href={`#contents/${encodeURIComponent(entry.blueId)}`}>{entry.blueId}</a>
              <CopyValue value={entry.blueId || ""} />
            </span>
            <small>COORDINATION/TIMELINE ENTRY</small>
          </div>
          <span className="subtle">
            {entry.entry?.timestamp ? formatDate(Number(entry.entry.timestamp?.value ?? entry.entry.timestamp) / 1000) : ""}
          </span>
        </div>
        <div className="inline-actions">
          <a
            className="plain-icon"
            aria-label="Open entry content"
            href={`#contents/${encodeURIComponent(entry.blueId)}`}
          >
            <ArrowUpRight size={16} />
          </a>
          <button
            className="plain-icon"
            aria-expanded={open}
            aria-label={open ? "Collapse entry" : "Expand entry"}
            onClick={() => setOpen(!open)}
          >
            {open ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
          </button>
        </div>
      </div>
      {open && (
        <>
          <ErrorBox error={error} />
          {content === undefined ? (
            !error && <div className="loading">Loading entry…</div>
          ) : (
            <CodeViewer
              value={content}
              label={messageOnly ? "Message" : "Timeline Entry"}
            />
          )}
        </>
      )}
    </article>
  );
}
export function TimelineDetail({
  id,
  navigate,
  accountId,
}: Props & { accountId: string }) {
  const [tab,setTab]=useState('entries');
  const [timeline, setTimeline] = useState<any>(),
    [entries, setEntries] = useState<any[]>([]),
    [cursor, setCursor] = useState<string | null>(null),
    [error, setError] = useState(""),
    [showAppend, setShowAppend] = useState(false),
    [documentId, setDocumentId] = useState(""),
    [message, setMessage] = useState('{\n  "message": "Hello, timeline"\n}'),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState(""),
    [expanded, setExpanded] = useState(false),
    [messageOnly, setMessageOnly] = useState(false),
    [filter, setFilter] = useState(""),
    [showFilter, setShowFilter] = useState(false),
    [actor, setActor] = useState("");
  const load = useCallback(async () => {
    try {
      const [t, e] = await Promise.all([
        api(`timelines/${encodeURIComponent(id)}`),
        readTimelineEntries(id),
      ]);
      setTimeline(t);
      setEntries(e.items);
      setCursor(e.nextPageToken);
      setError("");
    } catch (e: any) {
      setError(e.message);
    }
  }, [id]);
  useEffect(() => {
    load();
  }, [load]);
  useLiveRefresh("timelines", timeline?.timelineRef?.blueId || timeline?.blueId || id, load);
  return (
    <>
      <PageHeader
        title="Timeline Details"
        back={() => navigate("timelines")}
        actions={
          timeline?.owned !== false &&
          <button
            className="outline-button"
            onClick={() => setShowAppend(!showAppend)}
          >
            <Plus size={16} />
            Append entry
          </button>
        }
      />
      <ErrorBox error={error} />
      {notice && <div className="success">{notice}</div>}
      <section className="panel panel-pad timeline-metadata">
        <h3>Details</h3>
        <p>
          <b>ID:</b>{" "}
          <span className="id-cell">
            {timeline?.timelineId || id}
            <CopyValue value={timeline?.timelineId || id} />
          </span>
        </p>
        <p>
          <b>Name:</b> {timeline?.name || "Loading…"}
        </p>
        <p>
          <b>{timeline?.owned === false ? 'Provider endpoint' : 'Owner'}:</b> {timeline?.owned === false ? timeline.endpoint : accountId}
        </p>
        <p>
          <b>Owned:</b> {timeline?.owned === false ? 'No — read only' : 'Yes'}
        </p>
        <p>
          <b>Created:</b> {formatDate(timeline?.createdAt)}
        </p>
        <details className="timeline-extra">
          <summary>Timeline identity and completeness</summary>
          <CodeViewer value={timeline} />
        </details>
      </section>
      {timeline?.endpoint && <div className="panel panel-pad">
        <p><b>Provider:</b> {timeline.endpoint}</p>
        <p><b>Reader status:</b> {timeline.status || 'Available'}{timeline.lastFetchedAt && ` · Last checked ${formatDate(timeline.lastFetchedAt)}`}</p>
        {timeline.issue && <p role="alert">{timeline.issue.message}</p>}
        <button className="outline-button" onClick={async()=>{try{await mutate(`timelines/${encodeURIComponent(id)}/retry`,{});load()}catch(e:any){setError(e.message)}}}>{timeline.status === 'RETRY_REQUIRED' ? 'Retry' : 'Check now'}</button>
      </div>}
      {timeline?.endpoint && timeline.owned !== false && <nav className="panel panel-pad tabs" role="tablist" aria-label="Timeline sections">
        <button role="tab" aria-selected={tab==='entries'} className={`tab ${tab==='entries'?'active':''}`} onClick={()=>setTab('entries')}>Entries</button>
        <button role="tab" aria-selected={tab==='access'} className={`tab ${tab==='access'?'active':''}`} onClick={()=>setTab('access')}>Access</button>
      </nav>}
      {tab==='access' ? <TimelineAccess id={id}/> : <>
      {showAppend && (
        <form
          className="panel panel-pad"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            try {
              const r = await mutateYaml(`timelines/${encodeURIComponent(id)}/entries`,
                stringify({ inputMode: "source",
                  ...(documentId.trim() ? {documentId: documentId.trim()} : {}),
                  ...(actor ? {actor} : {}),
                }) + sourceField("message", message));
              setNotice(`Entry accepted: ${r.entryRef.blueId}`);
              load();
            } catch (e: any) {
              setError(e.message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <h3>Append an entry</h3>
          <label className="field">
            Document ID (required for operation requests)
            <input value={documentId} onChange={(e) => setDocumentId(e.target.value)} placeholder="Pre-initialization BlueId from the document's initialization marker" />
          </label>
          <label className="field">
            Message (JSON or YAML)
            <textarea
              required
              value={message}
              onChange={(e) => setMessage(e.target.value)}
            />
          </label>
          {timeline?.actors?.length > 1 && (
            <label className="field">
              Actor
              <select
                required
                value={actor}
                onChange={(e) => setActor(e.target.value)}
              >
                <option value="">Choose an actor</option>
                {timeline.actors.map((a: string) => (
                  <option key={a}>{a}</option>
                ))}
              </select>
            </label>
          )}
          <button className="primary" disabled={busy}>
            Append message
          </button>
        </form>
      )}
      <section className="panel panel-pad">
        <div className="table-toolbar">
          <h3>Timeline Entries</h3>
          <div className="inline-actions">
            <button
              className="filter-button"
              onClick={() => setShowFilter(!showFilter)}
            >
              Filter
            </button>
            <button
              className="text-button"
              onClick={() => setExpanded(!expanded)}
            >
              {expanded ? "Collapse all" : "Expand all"}
            </button>
            <button
              className="text-button"
              onClick={() => setMessageOnly(!messageOnly)}
            >
              {messageOnly ? "Show full entry" : "Show message only"}
            </button>
            <Refresh onClick={load} />
          </div>
        </div>
        {showFilter && (
          <label className="field">
            Find entries
            <input
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="Search entry ID or actor"
            />
          </label>
        )}
        {entries
          .filter((e) =>
            stringifyBlue(e).toLowerCase().includes(filter.toLowerCase()),
          )
          .map((e, i) => (
            <EntryCard
              key={e.blueId || i}
              entry={e}
              expanded={expanded}
              messageOnly={messageOnly}
            />
          ))}
        {!entries.length && <p className="subtle">No entries yet.</p>}
        {cursor && (
          <button
            className="outline-button"
            onClick={async () => {
              try {
                const r = await readTimelineEntries(id, cursor);
                setEntries([...entries, ...r.items]);
                setCursor(r.nextPageToken);
              } catch (e: any) {
                setError(e.message);
              }
            }}
          >
            Load more
          </button>
        )}
      </section>
      </>}
    </>
  );
}
