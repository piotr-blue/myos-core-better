import { AccountPreparationNotice } from "./account-preparation-notice";
import { useEffect, useState } from "react";
import { api, mutate } from "./api";
import { usePackages } from "./package-data";
import { DataTable } from "./components/data-table";
import { Dialog } from "./components/dialog";
import { LockKeyhole, RefreshCw } from "lucide-react";
import {
  BlueId,
  CodeViewer,
  CopyValue,
  decodeNativeContent,
  PageHeader,
} from "./components/viewer";
export function Reference({
  id,
  navigate,
}: {
  id: string;
  navigate: (route: string, replace?: boolean) => void;
}) {
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    api(`references/${encodeURIComponent(id)}`)
      .then((r) => {
        if (active && location.hash === `#references/${encodeURIComponent(id)}`)
          navigate(r.route, true);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [id]);
  return error ? (
    <div className="error" role="alert">
      {error} <a href={`#contents/${encodeURIComponent(id)}`}>Open content</a>
    </div>
  ) : (
    <div className="loading">Opening reference…</div>
  );
}
export function Packages({ id }: { id?: string }) {
  const { catalog, error: loadError, readState, reload } = usePackages();
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState("");
  useEffect(() => {
    setAdding(false);
    setError("");
  }, [id]);
  const act = async (p: any, remove = false) => {
    setBusy(p.id);
    setError("");
    try {
      await mutate(`packages/${encodeURIComponent(p.id)}`, {}, remove ? "DELETE" : "POST");
      await reload();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy("");
    }
  };
  const selected = [
    ...(catalog?.items || []),
    ...(catalog?.available || []),
  ].find((p) => p.id === (id || ""));
  const matches = (p: any) =>
    `${p.name} ${p.description} ${p.blueId || ""} ${p.types.map((t: any) => t.name).join(" ")}`
      .toLowerCase()
      .includes(query.toLowerCase());
  const action = (p: any) =>
    p.managed ? (
      <span className="subtle" title={p.removalReason}>
        <LockKeyhole size={14} /> Managed
      </span>
    ) : (
      <button
        className="outline-button"
        title={
          p.installed
            ? p.removalReason || "Remove from installed packages"
            : p.missingDependencies?.map((d: any) => d.name).join(", ")
        }
        disabled={!!busy || (p.installed ? !p.canRemove : !p.canInstall)}
        onClick={() => act(p, p.installed)}
      >
        {busy === p.id ? "Please wait…" : p.installed ? "Remove" : "Install"}
      </button>
    );
  const table = (rows: any[]) => (
    <DataTable
      data={rows.filter(matches)}
      columns={[
        {
          key: "name",
          label: "Package",
          render: (p: any) => (
            <a href={`#packages/${encodeURIComponent(p.id)}`}>{p.name}</a>
          ),
        },
        { key: "version", label: "Version", render: (p: any) => p.version || "Runtime" },
        { key: "types", label: "Types", render: (p: any) => p.types.length },
        {
          key: "hasSpecification",
          label: "Specification",
          render: (p: any) =>
            p.hasSpecification ? (p.managed ? "Runtime specification" : "Package specification") : "Declarative",
        },
        {
          key: "requiresCustomLogic",
          label: "Custom logic",
          render: (p: any) => (p.requiresCustomLogic ? "Required" : "None"),
        },
        { key: "actions", label: "Actions", render: action },
      ]}
    />
  );
  return (
    <>
      <PageHeader
        title={selected ? selected.name : "Packages"}
        back={
          id
            ? () => {
                location.hash = "packages";
              }
            : undefined
        }
        actions={
          !id ? (
            <button className="outline-button" onClick={() => setAdding(true)}>
              Add new
            </button>
          ) : selected ? (
            action(selected)
          ) : undefined
        }
      />
      <AccountPreparationNotice state={readState} />
      {(error || loadError) && (
        <div className="error" role="alert">
          {error || loadError}
        </div>
      )}
      {!catalog && <div className="loading">Loading packages…</div>}
      {catalog && id && !selected && (
        <div className="error">Package not found in this runtime.</div>
      )}
      {selected ? (
        <>
          <section className="panel panel-pad">
            <p>{selected.description}</p>
            <p>
              <strong>Specification:</strong>{" "}
              {selected.managed
                ? "Defines runtime behavior; managed by MyOS and cannot be removed."
                : selected.hasSpecification
                  ? "Package specification implemented with the installed BEX and Contracts runtime. No additional native processor."
                  : "No custom runtime specification. Uses the installed packages listed below."}
            </p>
            <p>
              <strong>Custom logic:</strong>{" "}
              {selected.requiresCustomLogic
                ? "Required — provided by the matching MyOS runtime."
                : "None — declarative types and workflows."}
            </p>
            {selected.blueId && (
              <p className="id-cell">
                <strong>Package Blue ID:</strong>
                <span className="mono">{selected.blueId}</span>
                <CopyValue value={selected.blueId} />
              </p>
            )}
            {selected.version && (
              <p>
                <strong>Version:</strong> {selected.version}
              </p>
            )}
            {selected.removalReason && !selected.managed && (
              <p className="notice">{selected.removalReason}</p>
            )}
            {!!selected.missingDependencies?.length && (
              <p className="notice">
                Install matching dependencies first:{" "}
                {selected.missingDependencies
                  .map((d: any) => d.name)
                  .join(", ")}
                .
              </p>
            )}
            <h3>Dependencies</h3>
            {selected.dependencies.length ? (
              <ul>
                {selected.dependencies.map((d: any) => {
                  const match = [...catalog.items, ...catalog.available].find(
                    (p: any) =>
                      (d.blueId && p.blueId === d.blueId) ||
                      (d.id && p.id === d.id) ||
                      p.name === d.name,
                  );
                  return (
                    <li key={d.name}>
                      {match ? (
                        <a href={`#packages/${encodeURIComponent(match.id)}`}>
                          {d.name}
                        </a>
                      ) : (
                        d.name
                      )}
                      {d.blueId && (
                        <span className="subtle"> · {d.blueId}</span>
                      )}
                    </li>
                  );
                })}
              </ul>
            ) : (
              <p className="subtle">No package dependencies.</p>
            )}
          </section>
          <section className="panel panel-pad">
            <h3>Types</h3>
            <DataTable
              data={selected.types}
              columns={[
                {
                  key: "name",
                  label: "Type",
                  render: (t: any) => (
                    <a href={`#types/${encodeURIComponent(t.blueId)}`}>{t.name}</a>
                  ),
                },
                {
                  key: "blueId",
                  label: "Blue ID",
                  render: (t: any) => (
                    <a className="mono" href={`#types/${encodeURIComponent(t.blueId)}`}>
                      {t.blueId}
                    </a>
                  ),
                },
              ]}
            />
          </section>
        </>
      ) : (
        catalog && (
          <section className="panel table-panel">
            <div className="table-toolbar">
              <label className="field">
                Find a package or type
                <input
                  placeholder="Name, type or Blue ID…"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                />
              </label>
              <button
                className="plain-icon"
                aria-label="Refresh"
                onClick={reload}
              >
                <RefreshCw size={18} />
              </button>
            </div>
            {table(catalog.items)}
          </section>
        )
      )}
      {adding && (
        <Dialog title="Add package" onClose={() => setAdding(false)}>
          <p>
            Install declarative packages after their exact dependencies. Runtime
            specifications are managed by MyOS.
          </p>
          {catalog && table(catalog.available)}
          <p className="subtle">
            Open a package to inspect its dependencies and types.
          </p>
        </Dialog>
      )}
    </>
  );
}
export function TypeDetail({ id }: { id: string }) {
  const [type, setType] = useState<any>();
  const [error, setError] = useState("");
  useEffect(() => {
    api(`types/${encodeURIComponent(id)}`)
      .then(setType)
      .catch((e) => setError(e.message));
  }, [id]);
  return (
    <>
      <PageHeader
        title={type?.alias || "Type definition"}
        back={() => {
          if (history.length > 1) history.back();
          else location.hash = "packages";
        }}
      />
      {error && (
        <div role="alert" className="error">
          {error}
        </div>
      )}
      {!type && !error && <div className="loading">Loading type…</div>}
      {type && (
        <>
          <section className="panel panel-pad">
            <h3>{type.package}</h3>
            <p className="id-cell">
              <strong>Blue ID:</strong> <span className="mono">{id}</span>
              <CopyValue value={id} />
            </p>
            {type.note && <p>{type.note}</p>}
          </section>
          {type.content && (
            <section className="panel panel-pad">
              <CodeViewer
                value={decodeNativeContent(type.content)}
                label="Type definition"
              />
            </section>
          )}
        </>
      )}
    </>
  );
}
