import type { DocumentsApi, NativeInputObservation } from '../generated/index.js';
import { waitFor, type WaitOptions } from './polling.js';

// Older providers publish a terminal assessment. The owner host records a disposition
// after channel admission, including FAILED when a signed operation trips a business guard.
const terminalOutcomes = new Set([
  'REJECTED', 'EXECUTION_REJECTED', 'NO_MATCH', 'FAILED', 'INAPPLICABLE',
  'IGNORED', 'GAS_LIMIT_EXCEEDED', 'TERMINATED',
]);

/**
 * Wait for evidence about this exact submitted timeline entry. A stable transition
 * proves processing, not a domain outcome such as payment completion. Inspect the
 * returned assessment/disposition and then the relevant business state.
 *
 * A stored OperationAdmission, READY worker, unrelated later input, or unfinished
 * native transition is not evidence that this input has finished. Stopping the
 * wait never cancels the server command.
 */
export async function waitForNativeInput(
  documents: Pick<DocumentsApi, 'getDocumentInputsEntryblueid'>,
  sessionId: string,
  entryBlueId: string,
  options: WaitOptions = {},
): Promise<NativeInputObservation> {
  if (!sessionId || !entryBlueId) throw new Error('Session and input entry identities are required');
  return waitFor(
    signal => documents.getDocumentInputsEntryblueid({ sessionId, entryBlueId }, { signal }),
    observation => {
      if (observation.sessionId !== sessionId || observation.entryBlueId !== entryBlueId) {
        throw new Error('Input observation does not match the requested session and entry');
      }
      const terminal = [observation.assessment, observation.disposition].some(value =>
        value !== null && typeof value === 'object' && 'outcome' in value &&
        terminalOutcomes.has(String(value.outcome)));
      if (terminal) return true;
      if (observation.localHistory.some(row => row.stable === true &&
        typeof row.document === 'string' && row.document.length > 0 &&
        typeof row.position === 'string' && row.position.length > 0 &&
        typeof row.receipt === 'string' && row.receipt.length > 0)) return true;
      if (['ERROR', 'HOST_INCOMPLETE', 'PAUSED'].includes(observation.processing.state)) {
        throw new Error(`Input ${entryBlueId} is waiting while document ${sessionId} is ${observation.processing.state}`);
      }
      return false;
    },
    options,
  );
}
