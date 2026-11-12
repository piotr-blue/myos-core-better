import type { Document, DocumentsApi } from '../generated/index.js';
import { waitFor, type WaitOptions } from './polling.js';

/** Wait for an initialized stable snapshot, not for a particular operation's effect. */
export async function waitForDocument(
  documents: Pick<DocumentsApi, 'getDocument'>,
  sessionId: string,
  options: WaitOptions = {},
) {
  const document = await waitFor(
    signal => documents.getDocument({ sessionId }, { signal }),
    value => {
      if (['ERROR', 'HOST_INCOMPLETE', 'PAUSED'].includes(value.processing.state)) {
        throw new Error(`Document ${sessionId}: ${JSON.stringify(value.processing)}`);
      }
      return value.documentId !== null && value.lastStable !== null;
    },
    options,
  );
  return document as Document & { documentId: string; lastStable: NonNullable<Document['lastStable']> };
}
