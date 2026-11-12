/** Decimal gas amounts never pass through JavaScript floating-point arithmetic. */
export type GasSnapshot = { credited: bigint; spent: bigint; reserved: bigint; available: bigint; balance: bigint };
export type GasCause = { kind: string; id: string; instance?: string; phase?: string; units?: string; debit?: string; usage?: string; returned?: string; disposition?: string; evidence?: string };
export type GasEvent = { id: string; created: string; sequence: number | bigint; object: Record<string, any> };
export function gasAmount(value: unknown): bigint | null {
  return typeof value === "string" && /^(0|[1-9][0-9]*)$/.test(value) ? BigInt(value) : null;
}
export function formatGas(value: bigint | string | null | undefined): string {
  const exact = typeof value === "bigint" ? value : gasAmount(value);
  return exact === null ? "—" : exact.toLocaleString("en-US");
}
export function signedGas(value: bigint | null): string {
  return value === null ? "—" : value === 0n ? "0" : `${value > 0n ? "+" : "−"}${formatGas(value < 0n ? -value : value)}`;
}
export function gasSnapshot(value: Record<string, any> | undefined): GasSnapshot | null {
  if (!value) return null;
  const credited = gasAmount(value.credited), spent = gasAmount(value.spent), reserved = gasAmount(value.reserved), available = gasAmount(value.available);
  if (credited === null || spent === null || reserved === null || available === null || credited < spent || credited - spent !== reserved + available) return null;
  return { credited, spent, reserved, available, balance: credited - spent };
}
export function causeDescription(cause: GasCause): string {
  switch (cause.kind) {
    case "CREDIT": return `${formatGas(cause.units)} gas added${cause.evidence ? ` · ${cause.evidence}` : "."}`;
    case "RESERVED": return `${formatGas(cause.units)} gas held for ${cause.phase === "INITIALIZE" ? "document initialization" : cause.phase === "RECEIVE" ? "receiving an event" : cause.phase === "REBASE" ? "document rebase" : "execution"}. This remains part of your balance.`;
    case "SETTLED": return cause.disposition === "NO_MATCH"
      ? `No matching work: no charge. ${formatGas(cause.returned)} gas returned to available.`
      : `${formatGas(cause.debit)} gas used${cause.disposition === "FAILED" ? " by a failed execution" : ""}; ${formatGas(cause.returned)} unused gas returned to available.`;
    case "RELEASED": return `${formatGas(cause.units)} reserved gas released without a committed charge.`;
    default: return "Recorded account gas change.";
  }
}
export function gasActivity(event: GasEvent) {
  const after = gasSnapshot(event.object);
  const before = gasSnapshot(event.object?.before);
  const causes: GasCause[] = Array.isArray(event.object?.causes) ? event.object.causes : [];
  const balanceChange = after && before ? after.balance - before.balance : null;
  const availableChange = after && before ? after.available - before.available : null;
  const reservedChange = after && before ? after.reserved - before.reserved : null;
  let title = "Balance recorded", description = "Change details are unavailable for this event.";
  if (after && before) {
    const credited = after.credited - before.credited, spent = after.spent - before.spent;
    if (after.credited === 0n && before.credited === 0n) {
      title = "Opening balance"; description = "Gas accounting started with zero gas.";
    } else if (credited > 0n && spent === 0n && reservedChange === 0n) {
      title = "Gas credited"; description = `${formatGas(credited)} gas added to the account.`;
    } else if (spent > 0n && credited === 0n) {
      title = "Execution charged"; description = `${formatGas(spent)} gas used.`;
      if (reservedChange !== null && -reservedChange >= spent) description += ` ${formatGas(-reservedChange - spent)} unused gas returned to available.`;
    } else if (reservedChange !== null && reservedChange > 0n && credited === 0n && spent === 0n) {
      title = "Gas reserved"; description = `${formatGas(reservedChange)} gas held for execution. This remains part of your balance.`;
    } else if (reservedChange !== null && reservedChange < 0n && credited === 0n && spent === 0n) {
      title = "Reservation released"; description = `${formatGas(-reservedChange)} gas returned to available with no charge.`;
    } else { title = "Gas updated"; description = "Account gas changed in this publication."; }
  }
  if (causes.length === 1) {
    const cause = causes[0];
    title = ({ CREDIT: "Gas credited", RESERVED: "Gas reserved", RELEASED: "Reservation released", SETTLED: cause.disposition === "NO_MATCH" ? "Execution not charged" : "Execution charged" })[cause.kind] || title;
    description = causeDescription(cause);
  }
  return { id: event.id, created: event.created, title, description, after, balanceChange, availableChange, reservedChange, causes };
}
