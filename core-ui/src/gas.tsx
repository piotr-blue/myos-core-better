import { useCallback, useEffect, useRef, useState } from "react";
import { RefreshCw } from "lucide-react";
import { api } from "./api";
import { useLiveRefresh } from "./use-live-refresh";
import { PageHeader, DateTime } from "./components/viewer";
import { DataTable } from "./components/data-table";
import { gasActivity, gasSnapshot, formatGas, signedGas, causeDescription, type GasEvent, type GasSnapshot } from "./gas-history";
import "./gas.css";

const historyPath = (cursor = "") => "events?" + new URLSearchParams({ type: "ACCOUNT_GAS_CHANGED", order: "desc", limit: "50", ...(cursor ? { pageToken: cursor } : {}) });
export function GasPage() {
  const [balance, setBalance] = useState<GasSnapshot | null>(null), [events, setEvents] = useState<GasEvent[]>([]);
  const [cursor, setCursor] = useState(""), [error, setError] = useState("");
  const [loading, setLoading] = useState(true), [loadingOlder, setLoadingOlder] = useState(false), [newActivity, setNewActivity] = useState(false);
  const latest = useRef<AbortController | undefined>(undefined), older = useRef<AbortController | undefined>(undefined), browsingOlder = useRef(false);
  const refresh = useCallback(async (showLatest = false) => {
    latest.current?.abort();
    const controller = new AbortController(); latest.current = controller;
    if (showLatest) { older.current?.abort(); browsingOlder.current = false; setLoadingOlder(false); }
    const preserveHistory = browsingOlder.current;
    try {
      setError("");
      // Keep the pinned older history while new activity updates the live balance.
      const page = preserveHistory ? undefined : await api(historyPath(), { signal: controller.signal });
      const current = await api("account/gas", { signal: controller.signal });
      if (controller.signal.aborted) return;
      const snapshot = gasSnapshot(current);
      if (!snapshot) throw new Error("The gas balance response is incomplete. Please refresh.");
      setBalance(snapshot);
      if (page) { setEvents(page.items); setCursor(page.hasMore ? page.nextPageToken : ""); setNewActivity(false); }
      else setNewActivity(true);
    } catch (failure: any) {
      if (controller.signal.aborted) return;
      setError(failure.message || "Could not load gas activity.");
    } finally { if (!controller.signal.aborted) setLoading(false); }
  }, []);
  useEffect(() => {
    void refresh();
    return () => { latest.current?.abort(); older.current?.abort(); };
  }, [refresh]);
  useLiveRefresh("gas", undefined, refresh);
  const loadOlder = async () => {
    if (!cursor || loadingOlder) return;
    latest.current?.abort(); older.current?.abort(); browsingOlder.current = true;
    const controller = new AbortController(); older.current = controller; setLoadingOlder(true); setError("");
    try {
      const page = await api(historyPath(cursor), { signal: controller.signal });
      if (controller.signal.aborted) return;
      setEvents(current => {
        const seen = new Set(current.map(event => event.id));
        return [...current, ...page.items.filter((event: GasEvent) => !seen.has(event.id))];
      });
      setCursor(page.hasMore ? page.nextPageToken : "");
    } catch (failure: any) { if (!controller.signal.aborted) setError(failure.message || "Could not load older gas activity."); }
    finally { if (!controller.signal.aborted) setLoadingOlder(false); }
  };
  const rows = events.map(event => gasActivity(event));
  return <>
    <PageHeader title="Gas" actions={<button className="secondary" onClick={() => void refresh(true)}><RefreshCw size={16} /> Refresh</button>} />
    {error && <div className="error" role="alert">{error} {balance && "The last loaded figures are shown below."}</div>}
    {loading ? <p role="status">Loading gas balance and activity…</p> : <>
        {balance && <section className="gas-summary" aria-label="Current gas balance">
          <div className="gas-card gas-available"><span>Available gas</span><strong>{formatGas(balance.available)}</strong><small>Total balance {formatGas(balance.balance)} · {formatGas(balance.reserved)} reserved</small></div>
          <div className="gas-card"><span>Reserved</span><strong>{formatGas(balance.reserved)}</strong><small>Held for work in progress</small></div>
          <div className="gas-card"><span>Total spent</span><strong>{formatGas(balance.spent)}</strong><small>Gas charged for execution</small></div>
          <div className="gas-card"><span>Total credited</span><strong>{formatGas(balance.credited)}</strong><small>Gas added to this account</small></div>
        </section>}
        <section className="panel gas-history" aria-label="Gas activity">
          <div className="panel-heading"><div><h2 className="panel-title">Activity</h2><p>Newest first · all amounts are gas units</p></div><span className="gas-history-count">{events.length} loaded</span></div>
          {newActivity && <div className="gas-new" role="status">The balance has updated. Your older history is still in place. <button className="link" onClick={() => void refresh(true)}>Show latest activity</button></div>}
          <DataTable data={rows} emptyMessage="No gas activity yet. Credits and execution charges will appear here."
            columns={[
              { key: "created", label: "When", className: "gas-date", render: row => <DateTime value={row.created} /> },
              { key: "activity", label: "Activity", className: "gas-description", render: row => <>
                <strong>{row.title}</strong><p>{row.description}</p>
                {row.causes.length > 1 && <ul>{row.causes.map(cause => <li key={`${cause.kind}/${cause.id}`}>{causeDescription(cause)}</li>)}</ul>}
                <div className="gas-links">{[...new Set(row.causes.map(cause => cause.instance).filter(Boolean))].map(instance => <a key={instance} href={`#documents/${encodeURIComponent(instance!)}`}>Document {instance!.slice(0, 8)}…</a>)}<a href={`#events/${encodeURIComponent(row.id)}`}>View event</a></div>
              </> },
              { key: "change", label: "Balance change", className: "gas-number", render: row => <span className={row.balanceChange !== null && row.balanceChange > 0n ? "gas-positive" : row.balanceChange !== null && row.balanceChange < 0n ? "gas-negative" : ""}>{signedGas(row.balanceChange)}</span> },
              { key: "balance", label: "Balance after", className: "gas-number", render: row => formatGas(row.after?.balance) },
              { key: "available", label: "Available after", className: "gas-number", render: row => <>{formatGas(row.after?.available)}<small>{formatGas(row.after?.reserved)} reserved</small></> },
            ]} />
          <div className="gas-history-footer"><button className="secondary" disabled={!cursor || loadingOlder} onClick={() => void loadOlder()}>{loadingOlder ? "Loading…" : "Load older activity"}</button><span>Balances come from committed account records. History uses retained MyOS events.</span></div>
        </section>
      </>}
  </>;
}
