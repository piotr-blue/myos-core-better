import { isMap, parseDocument } from "yaml";
import type { RuntimeIdentities } from "@blue-labs/myos-sdk";
import { processPackages } from "./process-packages";

/** Authored Activation documents need account resolution and authenticated timelines. */
export function usesBootstrap(source: string, mode: string) {
  if (mode !== "source") return false;
  const parsed = parseDocument(source, { intAsBigInt: true });
  const clauses = parsed.get("clauses", true);
  return !parsed.errors.length && isMap(clauses) && clauses.items.some(({ value }) =>
    isMap(value) && String(value.get("type")).replace(/\s*\/\s*/g, "/") === "Activation/Activation Clause");
}

export function createDocumentRequest(source: string, mode: string, runtime?: RuntimeIdentities, executionSelection?: RuntimeIdentities) {
  if (usesBootstrap(source, mode)) {
    return { path: "documents/bootstrap", body: {
      document: source,
      packages: processPackages(runtime),
      ...(executionSelection ? { executionSelection } : {}),
    } };
  }
  return { path: "documents", body: {
    document: mode === "reference" ? { blueId: source.trim() } : source,
    inputMode: mode === "exact" ? "exact" : "source",
  } };
}
