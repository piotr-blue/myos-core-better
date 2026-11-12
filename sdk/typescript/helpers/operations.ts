import type { AdmissionsApi, DocumentsApi, OperationRoute } from '../generated/index.js';
import { waitForAdmission } from './admissions.js';
import { waitForNativeInput } from './inputs.js';
import type { WaitOptions } from './polling.js';

export interface ReviewedOperation {
  sessionId: string;
  documentVersion: string;
  operation: string;
  route: OperationRoute;
  request: unknown;
  idempotencyKey: string;
  authority?: string;
}

/** Submit a caller-reviewed native operation. Never refresh a stale review or retry a changed write. */
export async function submitReviewedOperation(
  documents: Pick<DocumentsApi, 'invokeOperation' | 'getDocumentInputsEntryblueid'>,
  admissions: Pick<AdmissionsApi, 'getAdmission'>,
  review: ReviewedOperation,
  options: WaitOptions = {},
) {
  if (!review.documentVersion || !review.route.timeline || !review.route.channel || !review.operation || !review.idempotencyKey) {
    throw new Error('An exact document version, operation, native route and idempotency key are required');
  }
  const accepted = await documents.invokeOperation({
    sessionId: review.sessionId,
    idempotencyKey: review.idempotencyKey,
    xMyOSClient: 'console',
    invokeOperation: {
      inputMode: 'source', timeline: { blueId: review.route.timeline }, actor: review.route.actor,
      ...(review.authority ? { authority: review.authority } : {}),
      message: { type: 'Contracts/Operation Request', document: { blueId: review.documentVersion },
        channel: review.route.channel, operation: review.operation, request: review.request,
        requireExactDocumentVersion: true },
    },
  }, { signal: options.signal });
  const stored = await waitForAdmission(admissions, accepted, options);
  if (!stored.details?.entryRef?.blueId) throw new Error('Stored operation has no timeline entry reference');
  return waitForNativeInput(documents, review.sessionId, stored.details.entryRef.blueId, options);
}
