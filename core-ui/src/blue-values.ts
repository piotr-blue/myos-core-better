import { parse, parseDocument } from "yaml";

// Keep ordinary metadata convenient for React while preserving native Integer
// values that JavaScript numbers cannot represent exactly.
export function parseBlue(text: string): any {
  const safeNumbers = (value: any): any => {
    if (typeof value === "bigint")
      return value >= BigInt(Number.MIN_SAFE_INTEGER) && value <= BigInt(Number.MAX_SAFE_INTEGER)
        ? Number(value) : value;
    if (Array.isArray(value)) return value.map(safeNumbers);
    if (value && typeof value === "object")
      return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, safeNumbers(item)]));
    return value;
  };
  return safeNumbers(parse(text, { intAsBigInt: true }));
}

export function stringifyBlue(value: any, indent = 0): string {
  const encode = (item: any, depth = 0): string => {
    const join = (values: string[], open: string, close: string) => {
      if (!indent || !values.length) return open + values.join(",") + close;
      const padding = " ".repeat((depth + 1) * indent);
      return open + "\n" + padding + values.join(",\n" + padding) + "\n" + " ".repeat(depth * indent) + close;
    };
    if (typeof item === "bigint") return String(item);
    if (Array.isArray(item)) return join(item.map(v => v === undefined ? "null" : encode(v, depth + 1)), "[", "]");
    if (item && typeof item === "object") return join(Object.entries(item).filter(([,v]) => v !== undefined).map(([k,v]) => `${JSON.stringify(k)}:${indent ? " " : ""}${encode(v, depth + 1)}`), "{", "}");
    return JSON.stringify(item);
  };
  return encode(value);
}

/** Nest one source value without reserializing or rounding its numeric tokens. */
export function sourceField(name: string, source: string): string {
  const document = parseDocument(source);
  if (document.errors.length) throw document.errors[0];
  // Exclude stream directives/markers, but retain root tags and anchors before
  // the value. Using contents.range would silently discard those properties.
  const [start, end] = document.range || [0, source.length];
  const value = source.slice(start, end).replace(/^---(?:[ \t]*(?:#[^\n]*)?\r?\n|[ \t]+|$)/, "");
  return name + ":\n" + value.split("\n").map(line => "  " + line).join("\n") + "\n";
}
