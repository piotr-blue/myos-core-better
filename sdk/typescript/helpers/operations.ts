import type { AdmissionsApi, DocumentsApi, OperationRoute } from '../generated/index.js';
import { waitForAdmission } from './admissions.js';
import { waitForNativeInput } from './inputs.js';
import { createWait, type WaitOptions } from './polling.js';

export interface ReviewedOperation {
  sessionId: string;
  documentVersion: string;
  operation: string;
  route: OperationRoute;
  request: unknown;
  idempotencyKey: string;
  authority?: string;
}

/**
 * Submit a caller-reviewed native operation with one deadline for submission and both receipt waits.
 * Never refresh a stale review or retry a write. Stopping the client does not cancel server work;
 * recovery must reuse the identical review and idempotency key.
 */
export async function submitReviewedOperation(
  documents: Pick<DocumentsApi, 'invokeOperation' | 'getDocumentInputsEntryblueid'>,
  admissions: Pick<AdmissionsApi, 'getAdmission'>,
  review: ReviewedOperation,
  options: WaitOptions = {},
) {
  if (!review.sessionId || !review.documentVersion || !review.route.timeline || !review.route.channel || !review.operation || !review.idempotencyKey) {
    throw new Error('A session, exact document version, operation, native route and idempotency key are required');
  }
  // Validate before the first side effect. Every stage inherits this original deadline.
  const { signal } = createWait(options);
  const waiting = { ...options, signal };
  try {
    signal.throwIfAborted();
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
    }, { signal });
    signal.throwIfAborted();
    const stored = await waitForAdmission(admissions, accepted, waiting);
    if (!stored.details?.entryRef?.blueId) throw new Error('Stored operation has no timeline entry reference');
    return await waitForNativeInput(documents, review.sessionId, stored.details.entryRef.blueId, waiting);
  } catch (error) {
    if (signal.aborted) {
      throw new Error('Stopped waiting for the operation. The server command was not canceled. Recover with the identical review and idempotency key.', { cause: error });
    }
    throw error;
  }
}
