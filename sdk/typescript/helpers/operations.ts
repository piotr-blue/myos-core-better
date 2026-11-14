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
  const { sessionId, documentVersion, operation, route, request, idempotencyKey, authority } = review;
  if (!sessionId || !documentVersion || !route.timeline || !route.channel || !operation || !idempotencyKey) {
    throw new Error('A session, exact document version, operation, native route and idempotency key are required');
  }
  // Validate before the first side effect. Every stage inherits this original deadline.
  const { signal } = createWait(options);
  const waiting = { ...options, signal };
  try {
    signal.throwIfAborted();
    const accepted = await documents.invokeOperation({
      sessionId,
      idempotencyKey,
      xMyOSClient: 'console',
      invokeOperation: {
        inputMode: 'source', timeline: { blueId: route.timeline }, actor: route.actor,
        ...(authority ? { authority } : {}),
        message: { type: 'Contracts/Operation Request', document: { blueId: documentVersion },
          channel: route.channel, operation, request,
          requireExactDocumentVersion: true },
      },
    }, { signal });
    signal.throwIfAborted();
    const stored = await waitForAdmission(admissions, accepted, waiting);
    if (!stored.details?.entryRef?.blueId) throw new Error('Stored operation has no timeline entry reference');
    return await waitForNativeInput(documents, sessionId, stored.details.entryRef.blueId, waiting);
  } catch (error) {
    if (signal.aborted) {
      throw new Error('Stopped waiting for the operation. The server command was not canceled. Recover with the identical review and idempotency key.', { cause: error });
    }
    throw error;
  }
}
