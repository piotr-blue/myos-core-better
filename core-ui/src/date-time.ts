/** Compact local-calendar dates for resource lists; full timestamps remain in the tooltip. */
export function formatLastChange(value: unknown, now = new Date()): string {
  if (!value) return "—";
  const date = new Date(String(value));
  if (Number.isNaN(date.getTime())) return "—";
  const today = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
  return new Intl.DateTimeFormat("en-US", today
    ? { hour: "numeric", minute: "2-digit", hour12: true }
    : { month: "short", day: "numeric" }).format(date);
}
