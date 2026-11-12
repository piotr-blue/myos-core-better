export function withinDates(
  value: unknown,
  from: string,
  to: string,
  undated: boolean,
) {
  if (!from && !to) return true;
  if (!value) return undated;
  const date = new Date(String(value));
  if (Number.isNaN(date.getTime())) return undated;
  const day = date.toISOString().slice(0, 10);
  return (!from || day >= from) && (!to || day <= to);
}
export function DateFilter({
  from,
  to,
  undated,
  onChange,
  dateKind = "creation",
}: {
  from: string;
  to: string;
  undated: boolean;
  onChange: (from: string, to: string, undated: boolean) => void;
  dateKind?: "creation" | "change";
}) {
  return (
    <>
      <label className="field">
        {dateKind === "change" ? "Last change" : "Created"} from (UTC)
        <input
          type="date"
          value={from}
          max={to || undefined}
          onChange={(e) => onChange(e.target.value, to, undated)}
        />
      </label>
      <label className="field">
        {dateKind === "change" ? "Last change" : "Created"} through (UTC)
        <input
          type="date"
          value={to}
          min={from || undefined}
          onChange={(e) => onChange(from, e.target.value, undated)}
        />
      </label>
      <label className="date-undated">
        <input
          type="checkbox"
          checked={undated}
          onChange={(e) => onChange(from, to, e.target.checked)}
        />{" "}
        Include unknown {dateKind} dates
      </label>
    </>
  );
}
