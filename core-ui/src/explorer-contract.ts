import { apiUrl, assetUrl } from "./portal-location";
import { stringify as yamlStringify } from "yaml";
import { parseBlue, stringifyBlue } from "./blue-values";

export type OpenApiSpec = Record<string, any>;
export type ExplorerOperation = {
  key: string; method: string; path: string; operationId: string;
  tag: string; summary: string; description: string;
  operation: any; parameters: any[]; requestBody?: any; responses: Record<string, any>;
};
export type ExplorerExample = { key: string; name: string; description: string; value: any };

export function resolveLocal(spec: OpenApiSpec, value: any): any {
  const seen = new Set<string>();
  while (value && typeof value === "object" && typeof value.$ref === "string") {
    const ref = value.$ref;
    if (!ref.startsWith("#/")) throw new Error("Only local contract references are supported.");
    if (seen.has(ref)) return value;
    seen.add(ref);
    const target = ref.slice(2).split("/").reduce((node, part) => node?.[part.replace(/~1/g, "/").replace(/~0/g, "~")], spec);
    if (target === undefined) throw new Error(`Missing contract reference: ${ref}`);
    const { $ref, ...siblings } = value;
    value = typeof target === "object" && target !== null ? { ...target, ...siblings } : target;
  }
  return value;
}

export function parameterKey(parameter: any): string { return `${parameter.in}:${parameter.name}`; }
export function getOperations(spec: OpenApiSpec): ExplorerOperation[] {
  const result: ExplorerOperation[] = [];
  for (const [path, pathValue] of Object.entries(spec.paths || {})) {
    const item = resolveLocal(spec, pathValue);
    for (const method of ["get", "head", "post", "put", "patch", "delete", "options", "trace"]) {
      if (!item[method]) continue;
      const operation = resolveLocal(spec, item[method]);
      const parameters = new Map<string, any>();
      for (const value of [...(item.parameters || []), ...(operation.parameters || [])]) {
        const param = resolveLocal(spec, value);
        parameters.set(parameterKey(param), { ...param, schema: resolveLocal(spec, param.schema) });
      }
      result.push({ key: `${method.toUpperCase()} ${path}`, method: method.toUpperCase(), path,
        operationId: operation.operationId || `${method}-${path}`, tag: operation.tags?.[0] || "Other",
        summary: operation.summary || path, description: operation.description || "",
        operation, parameters: [...parameters.values()], requestBody: resolveLocal(spec, operation.requestBody),
        responses: Object.fromEntries(Object.entries(operation.responses || {}).map(([code, value]) => [code, resolveLocal(spec, value)])) });
    }
  }
  return result;
}
export function getExamples(spec: OpenApiSpec, media: any): ExplorerExample[] {
  if (!media) return [];
  const result = Object.entries(media.examples || {}).map(([key, value]) => {
    const example = resolveLocal(spec, value);
    return { key, name: example.summary || key, description: example.description || "", value: example.value };
  }).filter(row => row.value !== undefined);
  if (Object.hasOwn(media, "example")) result.unshift({ key: "default", name: "Example", description: "", value: media.example });
  return result;
}
export function formatValue(value: any, media = "application/json"): string {
  return media.includes("yaml") ? yamlStringify(value, { lineWidth: 0 }) : stringifyBlue(value, 2);
}
export function swaggerHref(operation?: ExplorerOperation): string {
  return assetUrl("/swagger.html") + (operation ? `#/${encodeURIComponent(operation.tag)}/${encodeURIComponent(operation.operationId)}` : "");
}
export async function loadContract(signal?: AbortSignal): Promise<OpenApiSpec> {
  const response = await fetch(apiUrl("/api/blue/v1/openapi.json"), { signal, credentials: "include", headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error(`Cannot load API contract (${response.status}).`);
  const spec = parseBlue(await response.text());
  if (!spec?.openapi?.startsWith("3.") || !spec.paths) throw new Error("The server did not return an OpenAPI 3 contract.");
  return spec;
}
