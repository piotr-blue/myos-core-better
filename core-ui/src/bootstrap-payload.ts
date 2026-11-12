import { isMap, isSeq, parseDocument } from "yaml";
import { parseBlue } from "./blue-values";

// The bootstrap envelope is metadata; the document is native authoring input.
// Send its original source to the server instead of rounding decimal tokens in JS.
export function parseBootstrapPayload(source: string) {
  const syntax = parseDocument(source);
  if (syntax.errors.length) throw syntax.errors[0];
  const payload = parseBlue(source);
  if (!payload || typeof payload !== "object" || Array.isArray(payload))
    throw new Error("Provide a bootstrap object.");
  const input = payload.document;
  if (typeof input === "string") return payload;
  // A singleton reference is an API selector, not authored document source.
  if (input && typeof input === "object" && !Array.isArray(input) &&
      Object.keys(input).length === 1 && typeof input.blueId === "string") return payload;
  const document = syntax.get("document", true);
  if ((isMap(document) || isSeq(document)) && document.range) {
    const [start, end] = document.range;
    const lineStart = source.lastIndexOf("\n", start - 1) + 1;
    const prefix = source.slice(lineStart, start);
    payload.document = source.slice(/^\s*$/.test(prefix) ? lineStart : start, end);
  }
  return payload;
}


export function bootstrapTemplate(accountId: string) {
  return {
    inputMode: 'source',
    participantMappings: [
      {channelName: 'alice', accountId},
      {channelName: 'bob', accountId: 'REPLACE_BOB_ACCOUNT_ID'},
    ],
    document: {
      name: 'Shared counter',
      description: 'Alice increments and Bob decrements the same counter. Both participants observe the resulting state.',
      count: 0,
      contracts: {
        alice: {type: 'Coordination/Timeline Channel'},
        bob: {type: 'Coordination/Timeline Channel'},
        increment: {type: 'Contracts/Sequential Workflow Operation', channel: 'alice', steps: [
          {type: 'Contracts/Compute', do: [
            {$appendChange: {op: 'replace', path: '/count', val: {$add: [{$document: '/count'}, 1]}}},
          ]},
        ]},
        decrement: {type: 'Contracts/Sequential Workflow Operation', channel: 'bob', steps: [
          {type: 'Contracts/Compute', do: [
            {$appendChange: {op: 'replace', path: '/count', val: {$add: [{$document: '/count'}, -1]}}},
          ]},
        ]},
      },
    },
  };
}
