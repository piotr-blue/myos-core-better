import { isMap, isSeq, parseDocument } from "yaml";

export type ClauseView = { key: string; type: string; source: string; summary: string; bindings: string[] };
export type DocumentView = { path: string; title: string; type: string; source: string; clauses: ClauseView[] };
export function exampleHref(id: string, document = "/", clause?: string, view = "house") {
  const query = new URLSearchParams({ document, view });
  if (clause) query.set("clause", clause);
  return `#examples/${encodeURIComponent(id)}?${query}`;
}
const descriptions: Record<string, string> = {
  Activation: "Defines the parties and whose approval is required before the relationship becomes active.",
  Connections: "Connects a reusable action to the configured operation, event or document change.",
  Payment: "Creates and controls a Payment Instruction. Requests and provider-confirmed outcomes remain separate.",
  MyOS: "Connects the payment flow to MyOS accounts. The configured parties retain their own payment permissions.",
  Documents: "Creates connected documents from the configured template and observes their outcomes.",
  Ledger: "Records obligations, adjustments and settlement entries. An accrued balance is not money held or paid.",
  State: "Defines allowed states and transitions, including who can request each change.",
  Termination: "Controls winding down and termination, including any conditions that must be satisfied first.",
  Features: "Adds the actions defined below and binds them to explicit parties, events or workflows.",
};
function text(node: any, key: string) { const value = node.get(key); return typeof value === "string" ? value : ""; }
function snippet(source: string, node: any) {
  if (!node?.range) return "";
  const [start, end] = node.range;
  const indent = source.slice(source.lastIndexOf("\n", start - 1) + 1, start).match(/^ */)?.[0].length || 0;
  return source.slice(start, end).split("\n").map((line, i) => i ? line.slice(Math.min(indent, line.match(/^ */)![0].length)) : line).join("\n").trimEnd();
}
/** Inspect syntax nodes directly: displayed/copyable YAML retains exact numeric lexemes. */
export function inspectExample(source: string): DocumentView[] {
  const parsed = parseDocument(source, { intAsBigInt: true });
  if (parsed.errors.length) throw new Error(parsed.errors[0].message);
  if (!isMap(parsed.contents)) throw new Error("The document must be a YAML mapping.");
  const documents: DocumentView[] = [];
  const escape = (key: string) => key.replace(/~/g, "~0").replace(/\//g, "~1");
  function visit(node: any, path: string) {
    if (isMap(node)) {
      const clauses = node.get("clauses", true);
      if (path === "" || isMap(clauses)) {
        documents.push({ path: path || "/", title: text(node, "name") || path.split("/").pop() || "Document", type: text(node, "type"), source: path ? snippet(source, node) : source, clauses: isMap(clauses) ? clauses.items.map(pair => {
          const value = pair.value; const type = isMap(value) ? text(value, "type") : "";
          const bindings = isMap(value) ? value.get("bindings", true) : undefined;
          return { key: String(pair.key), type, source: snippet(source, value), summary: descriptions[type.split("/")[0]] || "The configuration below defines this block’s behavior and its connections to the document.", bindings: isMap(bindings) ? bindings.items.map(p => String(p.key)) : [] };
        }) : [] });
      }
      for (const pair of node.items) visit(pair.value, path + "/" + escape(String(pair.key)));
    } else if (isSeq(node)) node.items.forEach((value, index) => visit(value, path + "/" + index));
  }
  visit(parsed.contents, "");
  return documents;
}
