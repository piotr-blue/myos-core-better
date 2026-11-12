import { useCallback, useEffect, useState } from "react";
import { api, mutate, allItems } from "./api";
import { useLiveRefresh } from "./use-live-refresh";
import { PageHeader, CodeViewer, LastChange } from "./components/viewer";
import { DataTable } from "./components/data-table";

const types = [
  "DOCUMENT_PROCESSING_ERROR",
  "DOCUMENT_EPOCH_ADVANCED",
  "DOCUMENT_PROCESSING_HISTORY_APPENDED",
  "DOCUMENT_CREATED",
  "DOCUMENT_REMOVED",
  "DOCUMENT_PROCESSING_PAUSED",
  "DOCUMENT_PROCESSING_RESUMED",
  "TIMELINE_CREATED",
  "TIMELINE_ENTRY_CREATED",
  "ADMISSION_STATUS_CHANGED",
  "CONTENT_CREATED",
  "ACCOUNT_GAS_CHANGED",
];
const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
export function EventsPage({ id }: { id?: string }) {
  const [rows, setRows] = useState<any[]>([]),
    [event, setEvent] = useState<any>();
  const [type, setType] = useState(""),
    [session, setSession] = useState("");
  const [cursor, setCursor] = useState(""),
    [error, setError] = useState("");
  const load = useCallback(
    async (next = "") => {
      try {
        setError("");
        if (id) {
          setEvent(await api(`events/${encodeURIComponent(id)}`));
          return;
        }
        const q = new URLSearchParams({ limit: "30", order: "desc" });
        if (type) q.set("type", type);
        if (session) q.set("sessionId", session);
        if (next) q.set("pageToken", next);
        const r = await api(`events?${q}`);
        setRows((old) => (next ? [...old, ...r.items] : r.items));
        setCursor(r.hasMore ? r.nextPageToken : "");
      } catch (e) {
        setError(message(e));
      }
    },
    [id, type, session],
  );
  useEffect(() => {
    void load();
  }, [load]);
  useLiveRefresh("events", id, load);
  return (
    <>
      <PageHeader
        title={id ? "Event details" : "MyOS Events"}
        back={
          id
            ? () => {
                location.hash = "events";
              }
            : undefined
        }
        actions={
          <button className="secondary" onClick={() => void load()}>
            Refresh
          </button>
        }
      />
      {error && (
        <div role="alert" className="error">
          {error}
        </div>
      )}
      {id ? (
        event && (
          <section className="panel panel-pad">
            <p>Committed event · {event.type}</p>
            {event.ref?.sessionId && (
              <p>
                <a href={`#documents/${encodeURIComponent(event.ref.sessionId)}`}>Open document</a>
              </p>
            )}
            {event.type === "ACCOUNT_GAS_CHANGED" && <p><a href="#gas">Open gas balance and activity</a></p>}
            <CodeViewer value={event} />
          </section>
        )
      ) : (
        <section className="panel panel-pad">
          <p>
            Durable events from document processing, timeline entries and
            admissions. Webhooks send this same event body.
          </p>
          <div className="filter-panel">
            <label className="field">
              Event type
              <select value={type} onChange={(e) => setType(e.target.value)}>
                <option value="">All types</option>
                {types.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            </label>
            <label className="field">
              Document session ID
              <input
                value={session}
                onChange={(e) => setSession(e.target.value)}
                placeholder="Filter by document"
              />
            </label>
          </div>
          <DataTable
            data={rows}
            columns={[
              {
                key: "type",
                label: "Type",
                render: (r) => <a href={`#events/${encodeURIComponent(r.id)}`}>{r.type}</a>,
              },
              { key: "sequence", label: "Sequence" },
              {
                key: "created",
                label: "Last Change",
                className: "date-column",
                render: (r) => <LastChange value={r.created} />,
              },
              {
                key: "ref",
                label: "Resource",
                render: (r) =>
                  r.ref.sessionId ? (
                    <a href={`#documents/${encodeURIComponent(r.ref.sessionId)}`}>
                      {r.ref.sessionId}
                    </a>
                  ) : r.ref.timelineId ? (
                    <a href={`#${r.type === "TIMELINE_ENTRY_CREATED" ? "references" : "timelines"}/${encodeURIComponent(r.ref.timelineId)}`}>
                      {r.ref.timelineId}
                    </a>
                  ) : r.ref.admissionId ? (
                    "Admission"
                  ) : r.type === "ACCOUNT_GAS_CHANGED" ? (
                    <a href="#gas">Account gas</a>
                  ) : (
                    "Content"
                  ),
              },
            ]}
          />
          <button className="secondary" disabled={!cursor} onClick={() => void load(cursor)}>
            Load older events
          </button>
        </section>
      )}
    </>
  );
}
export function WebhooksPage({ id }: { id?: string }) {
  const [rows, setRows] = useState<any[]>([]),
    [hook, setHook] = useState<any>(),
    [deliveries, setDeliveries] = useState<any[]>([]),
    [attempts, setAttempts] = useState<any>();
  const [name, setName] = useState(""),
    [url, setUrl] = useState(""),
    [eventType, setEventType] = useState("DOCUMENT_EPOCH_ADVANCED"),
    [session, setSession] = useState("");
  const [error, setError] = useState(""),
    [secret, setSecret] = useState(""),
    [busy, setBusy] = useState(false),
    [creating, setCreating] = useState(false),
    [status, setStatus] = useState(""),
    [cursor, setCursor] = useState("");
  const load = useCallback(async () => {
    try {
      setError("");
      if (id) {
        setHook(await api(`webhooks/${encodeURIComponent(id)}`));
        const d = await api(
          `webhooks/${encodeURIComponent(id)}/events?limit=30${status ? `&status=${status}` : ""}`,
        );
        setDeliveries(d.items);
        setCursor(d.nextPageToken || "");
      } else setRows(await allItems("webhooks"));
    } catch (e) {
      setError(message(e));
    }
  }, [id, status]);
  useEffect(() => {
    setSecret("");
    setAttempts(undefined);
    void load();
  }, [load]);
  async function action(fn: () => Promise<unknown>, reload = true) {
    setBusy(true);
    try {
      setError("");
      await fn();
      if (reload) await load();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageHeader
        title={id ? hook?.name || "Webhook" : "Webhooks"}
        back={
          id
            ? () => {
                location.hash = "webhooks";
              }
            : undefined
        }
        actions={
          <div className="inline-actions">
            <button className="secondary" onClick={() => void load()}>
              Refresh
            </button>
            {!id && (
              <button className="outline-button" onClick={() => setCreating(!creating)}>Add new</button>
            )}
          </div>
        }
      />
      {error && (
        <div role="alert" className="error">
          {error}
        </div>
      )}
      {secret && (
        <section className="panel panel-pad" data-dd-privacy="hidden">
          <h2>Save your signing secret</h2>
          <p>
            This is shown only when creating the webhook. Use it to verify the
            signature on the exact request bytes.
          </p>
          <code style={{ overflowWrap: "anywhere" }}>{secret}</code>
          <button
            className="secondary"
            onClick={() => navigator.clipboard.writeText(secret)}
          >
            Copy secret
          </button>
          <button className="secondary" onClick={() => setSecret("")}>
            Dismiss
          </button>
        </section>
      )}
      {!id && creating && (
        <form
          className="panel panel-pad"
          onSubmit={(e) => {
            e.preventDefault();
            void action(async () => {
              const r = await mutate("webhooks", {
                name,
                url,
                events: eventType ? [{ type: eventType }] : [],
                filters: { sessionIds: session ? [session] : [] },
              });
              setSecret(r.signingSecret);
              setCreating(false);
            });
          }}
        >
          <h2>Create webhook</h2>
          <label className="field">
            Name
            <input
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <label className="field">
            HTTPS endpoint
            <input
              type="url"
              required
              placeholder="https://your-service.example/webhooks"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
          </label>
          <label className="field">
            Event type
            <select
              value={eventType}
              onChange={(e) => setEventType(e.target.value)}
            >
              <option value="">All events</option>
              {types.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </label>
          <label className="field">
            Document session ID (optional)
            <input
              value={session}
              onChange={(e) => setSession(e.target.value)}
            />
          </label>
          <button className="primary" disabled={busy}>Create webhook</button>
        </form>
      )}
      {!id ? (
        <section className="panel panel-pad">
          <p>
            Receive committed events at your HTTPS endpoint. Delivery retries
            run independently of document processing. Deduplicate by event ID;
            verify every signature.
          </p>
          <DataTable
            data={rows}
            columns={[
              {
                key: "name",
                label: "Name",
                render: (r) => <a href={`#webhooks/${encodeURIComponent(r.id)}`}>{r.name}</a>,
              },
              { key: "url", label: "Endpoint" },
              { key: "status", label: "Status" },
              {
                key: "events",
                label: "Events",
                render: (r) => r.events.map((event: any) => event.type).join(", ") || "All events",
              },
            ]}
          />
        </section>
      ) : (
        hook && (
          <>
            <section className="panel panel-pad">
              <p>{hook.url}</p>
              <p>
                <strong>{hook.status}</strong> ·{" "}
                {hook.events.map((event: any) => event.type).join(", ") || "All events"}
              </p>
              <p>
                {hook.filters.sessionIds.length
                  ? `Documents: ${hook.filters.sessionIds.join(", ")}`
                  : "All matching resources"}
              </p>
              <div className="inline-actions">
                <button
                  className="secondary"
                  disabled={busy}
                  onClick={() =>
                    void action(() =>
                      mutate(
                        `webhooks/${encodeURIComponent(id)}`,
                        {
                          status:
                            hook.status === "ACTIVE" ? "INACTIVE" : "ACTIVE",
                        },
                        "POST",
                      ),
                    )
                  }
                >
                  {hook.status === "ACTIVE"
                    ? "Pause delivery"
                    : "Resume delivery"}
                </button>
                <button
                  className="secondary"
                  disabled={busy}
                  onClick={() => {
                    if (
                      confirm(
                        "Remove this webhook? Delivery history is retained.",
                      )
                    )
                      void action(async () => {
                        await mutate(`webhooks/${encodeURIComponent(id)}`, {}, "DELETE");
                        location.hash = "webhooks";
                      });
                  }}
                >
                  Remove
                </button>
              </div>
              <p>
                Pausing retains the backlog. Removing prevents new sends; an
                already running request may finish.
              </p>
            </section>
            <section className="panel panel-pad">
              <h2>Deliveries</h2>
              <label className="field">
                Status
                <select
                  value={status}
                  onChange={(e) => setStatus(e.target.value)}
                >
                  <option value="">All statuses</option>
                  {[
                    "PENDING",
                    "SENDING",
                    "RETRYING",
                    "SUCCEEDED",
                    "FAILED",
                    "CANCELED",
                  ].map((s) => (
                    <option key={s}>{s}</option>
                  ))}
                </select>
              </label>
              <DataTable
                data={deliveries}
                columns={[
                  {
                    key: "sequence",
                    label: "Event",
                    render: (r) => (
                      <a href={`#events/${encodeURIComponent(r.eventId)}`}>{r.eventId}</a>
                    ),
                  },
                  { key: "status", label: "Status" },
                  { key: "attempts", label: "Attempts" },
                  {
                    key: "responseStatus",
                    label: "HTTP status",
                    render: (r) => r.responseStatus ?? "—",
                  },
                  {
                    key: "actions",
                    label: "Actions",
                    render: (r) => (
                      <div className="inline-actions">
                        <button
                          className="secondary"
                          onClick={() =>
                            void action(async () =>
                              setAttempts(
                                await api(
                                  `webhooks/${encodeURIComponent(id)}/events/${encodeURIComponent(r.eventId)}/attempts`,
                                ),
                              ),
                            )
                          }
                        >
                          Details
                        </button>
                        {["FAILED", "CANCELED", "SUCCEEDED"].includes(
                          r.status,
                        ) && (
                          <button
                            className="secondary"
                            disabled={busy}
                            onClick={() =>
                              void action(() =>
                                mutate(
                                  `webhooks/${encodeURIComponent(id)}/events/${encodeURIComponent(r.eventId)}/retry`,
                                  {},
                                ),
                              )
                            }
                          >
                            Redeliver
                          </button>
                        )}
                      </div>
                    ),
                  },
                ]}
              />
              {cursor && (
                <button
                  className="secondary"
                  onClick={() =>
                    void action(async () => {
                      const d = await api(
                        `webhooks/${encodeURIComponent(id)}/events?limit=30&pageToken=${encodeURIComponent(cursor)}${status ? `&status=${status}` : ""}`,
                      );
                      setDeliveries((old) => [...old, ...d.items]);
                      setCursor(d.nextPageToken || "");
                    }, false)
                  }
                >
                  Load more
                </button>
              )}
            </section>
            {attempts && (
              <section className="panel panel-pad">
                <CodeViewer value={attempts} label="Delivery attempts" />
              </section>
            )}
          </>
        )
      )}
    </>
  );
}
