import { stringify } from "yaml";
import { sourceField } from "./blue-values";

export function operationMessage(documentId: string, versionId: string | undefined,
  channel: string, operation: string, request: string, exact: boolean): string {
  if (exact && !versionId) throw new Error("Wait for an initialized document before approving its version.");
  return stringify({
    type: "Contracts/Operation Request",
    document: { blueId: exact ? versionId : documentId },
    channel, operation,
    ...(exact ? { requireExactDocumentVersion: true } : {}),
  }) + sourceField("request", request);
}
