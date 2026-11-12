import { useMemo, useState, useEffect, useRef, type ReactNode } from "react";
import { usePackages, type TypeInfo } from "../package-data";
import { stringify } from "yaml";
import { parseBlue, stringifyBlue } from "../blue-values";
import { minimize } from "../presentation";
import { formatLastChange } from "../date-time";
// Only for native package type responses; public content values are already decoded.
export function decodeNativeContent(value: any): any {
  if (typeof value?.text === "string" && (value?.format || value?.mode)) {
    try {
      return parseBlue(value.text);
    } catch {
      return value;
    }
  }
  return value;
}
import {
  Copy,
  Check,
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";
export function CopyValue({ value }: { value: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className="copy-icon"
      aria-label={`Copy ${value}`}
      title="Copy"
      onClick={async () => {
        await navigator.clipboard.writeText(value);
        setDone(true);
        setTimeout(() => setDone(false), 1500);
      }}
    >
      {done ? <Check size={16} /> : <Copy size={16} />}
    </button>
  );
}
export function PageHeader({
  title,
  back,
  actions,
}: {
  title: ReactNode;
  back?: () => void;
  actions?: ReactNode;
}) {
  return (
    <header className="page-heading">
      <div className="page-title-group">
        {back && (
          <button className="back-box" aria-label="Go back" onClick={back}>
            <ArrowLeft size={24} />
          </button>
        )}
        <h1 className="page-title">{title}</h1>
      </div>
      {actions}
    </header>
  );
}
export function BlueId({ value }: { value: string }) {
  return (
    <a className="blue-id-link" href={`#references/${encodeURIComponent(value)}`}>
      {value}
    </a>
  );
}
export function TypeName({ type }: { type: TypeInfo }) {
  return (
    <>
      <a href={`#packages/${encodeURIComponent(type.packageId)}`}>
        {type.package}
      </a>
      {" / "}
      <a href={`#types/${encodeURIComponent(type.blueId)}`}>{type.name}</a>
    </>
  );
}
function linkedText(text: string) {
  return text
    .split(
      /(?<![1-9A-HJ-NP-Za-km-z])([1-9A-HJ-NP-Za-km-z]{43,44}(?:#[0-9]+)?)(?![1-9A-HJ-NP-Za-km-z])/g,
    )
    .map((part, i) =>
      /^[1-9A-HJ-NP-Za-km-z]{43,44}(?:#[0-9]+)?$/.test(part) ? (
        <BlueId key={i} value={part} />
      ) : (
        part
      ),
    );
}
export function CodeViewer({
  value,
  label = "Content",
  compact = false,
}: {
  value: unknown;
  label?: string;
  compact?: boolean;
}) {
  const { types } = usePackages();
  const typeNames = useMemo(
    () =>
      Object.fromEntries(
        Object.entries(types).flatMap(([id, t]) => [
          [id, `${t.package} / ${t.name}`],
          [`${t.package}/${t.name}`, `${t.package} / ${t.name}`],
        ]),
      ),
    [types],
  );
  const byLabel = useMemo(
    () =>
      Object.fromEntries(
        Object.values(types).map((t) => [`${t.package} / ${t.name}`, t]),
      ),
    [types],
  );
  const [json, setJson] = useState(false);
  const [scroll, setScroll] = useState(0);
  const region = useRef<HTMLDivElement>(null);
  const formatted = useMemo(() => {
    const started = performance.now();
    const display = minimize(value, typeNames);
    const text =
      (json
        ? stringifyBlue(display, 2)
        : stringify(display, { lineWidth: 0 })) || "null";
    const lines = text.trimEnd().split("\n");
    return {
      text,
      lines,
      ms: performance.now() - started,
      width: Math.max(...lines.map((l) => l.length).slice(0, 50000)),
    };
  }, [value, json, typeNames]);
  useEffect(() => {
    setScroll(0);
    if (region.current) region.current.scrollTop = 0;
  }, [formatted]);
  const start = Math.max(0, Math.floor(scroll / 21) - 12);
  const end = Math.min(formatted.lines.length, start + 64);
  return (
    <div
      className="code-view"
      data-format-ms={formatted.ms.toFixed(2)}
      data-line-count={formatted.lines.length}
    >
      <div className={`viewer-toolbar ${compact ? "compact" : ""}`}>
        {!compact && <strong>{label}:</strong>}
        <div className="inline-actions">
          <button
            className="text-button"
            title="Copy the complete displayed content"
            onClick={() => navigator.clipboard.writeText(formatted.text)}
          >
            Copy
          </button>
          <button className="text-button" onClick={() => setJson(!json)}>
            Show {json ? "YAML" : "JSON"}
          </button>
        </div>
      </div>
      <div
        ref={region}
        className="numbered-code"
        role="region"
        aria-label={`${label} ${json ? "JSON" : "YAML"}`}
        tabIndex={0}
        onScroll={(e) => setScroll(e.currentTarget.scrollTop)}
      >
        <div
          style={{
            height: formatted.lines.length * 21,
            minWidth: `calc(${formatted.width}ch + 80px)`,
            position: "relative",
          }}
        >
          <div style={{ position: "absolute", top: start * 21, width: "100%" }}>
            {formatted.lines.slice(start, end).map((line, offset) => {
              const i = start + offset;
              const split = line.match(
                /^(\s*(?:- )?(?:"[^"\n]+"|[^:"\n]+):)(.*)$/,
              );
              const typeLabel = split?.[2]
                .trim()
                .replace(/,$/, "")
                .replace(/^"|"$/g, "");
              const type =
                split && /(?:type|itemType|keyType)"?:$/.test(split[1])
                  ? byLabel[typeLabel || ""]
                  : undefined;
              return (
                <div className="code-line" key={i}>
                  <span className="line-number" aria-hidden="true">
                    {i + 1}
                  </span>
                  <code>
                    {split ? (
                      <>
                        <span className="syntax-key">{split[1]}</span>
                        {type ? (
                          <>
                            {" "}
                            {json && '"'}
                            <TypeName type={type} />
                            {json && '"'}
                            {split[2].endsWith(",") && ","}
                          </>
                        ) : (
                          linkedText(split[2])
                        )}
                      </>
                    ) : (
                      linkedText(line || " ")
                    )}
                  </code>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
// Same navigation semantics as the Developers UI's epoch-navigation.tsx.
export function EpochNavigation({
  current,
  latest,
  onChange,
}: {
  current: number;
  latest: number;
  onChange: (n: number | null) => void;
}) {
  return (
    <div className="epoch-navigation">
      <button
        className="epoch-button"
        disabled={current === 0}
        onClick={() => onChange(current - 1)}
      >
        <ChevronLeft size={16} />
        Previous
      </button>
      <span>
        {current}{" "}
        <small>({current === latest ? "CURRENT" : "HISTORICAL"})</small>
      </span>
      {current < latest && (
        <>
          <button
            className="epoch-button"
            onClick={() => onChange(current + 1)}
          >
            <ChevronRight size={16} />
            Next
          </button>
          <button className="epoch-button" onClick={() => onChange(null)}>
            Current
          </button>
        </>
      )}
    </div>
  );
}
export const formatDate = (value: unknown) => {
  if (value === null || value === undefined || value === "") return "—";
  const date = new Date(typeof value === "number" ? value : String(value));
  return Number.isNaN(date.getTime())
    ? "—"
    : `${date.toLocaleDateString("en-GB")} · ${date.toLocaleTimeString("en-GB", { hour12: false })}`;
};

export function DateTime({ value }: { value: unknown }) {
  return <span className="date-time">{formatDate(value)}</span>;
}

export function LastChange({ value }: { value: unknown }) {
  return (
    <time className="date-time" dateTime={value ? String(value) : undefined} title={formatDate(value)}>
      {formatLastChange(value)}
    </time>
  );
}

export function processingLabel(state?: string) {
  if (state === "READY") return "Running";
  return state
    ? state.charAt(0) + state.slice(1).toLowerCase().replaceAll("_", " ")
    : "Initializing";
}
