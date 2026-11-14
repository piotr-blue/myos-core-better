import type { Admission, AdmissionsApi } from '../generated/index.js';
import { ResponseError } from '../generated/index.js';
import { createWait, sleep, type WaitOptions } from './polling.js';

export class AdmissionFailed extends Error {
  constructor(public readonly admission: Admission) {
    super(`Admission ${admission.admissionId} FAILED: ${JSON.stringify(admission.problem)}`);
  }
}

/** Wait for the host receipt only. SUCCEEDED does not prove document application. */
export async function waitForAdmission<T extends Admission>(
  api: Pick<AdmissionsApi, 'getAdmission'>,
  accepted: T,
  options: WaitOptions & { onUpdate?: (admission: Admission) => void } = {},
): Promise<T> {

  const { signal, interval } = createWait(options);
  // The caller and onUpdate callback can retain mutable DTOs. Pin the accepted identity once.
  const { admissionId, kind } = accepted;
  let current: Admission = accepted;
  let previousStatus: string | undefined;
  let projected = false;

  try {
    while (true) {
      signal.throwIfAborted();
      if (current.admissionId !== admissionId || current.kind !== kind) {
        throw new Error('Admission observation does not match the accepted receipt');
      }
      if (current.status !== previousStatus) options.onUpdate?.(current);
      previousStatus = current.status;
      if (current.status === 'FAILED') throw new AdmissionFailed(current);
      if (current.status === 'SUCCEEDED') return current as T;
      if (current.status !== 'PENDING') throw new Error(`Unknown admission status: ${current.status}`);
      await sleep(interval, signal);
      try {
        current = await api.getAdmission({ admissionId }, { signal });
        projected = true;
      } catch (error) {
        // Acceptance precedes asynchronous projection; disappearance after observation is not lag.
        if (projected || !await initialProjectionAbsence(error)) throw error;
      }
    }
  } catch (error) {
    if (signal.aborted) {
      throw new Error(`Stopped waiting for admission ${admissionId}; last status ${current.status}. The server command was not canceled.`, { cause: error });
    }
    throw error;
  }

}

async function initialProjectionAbsence(error: unknown): Promise<boolean> {
  if (!(error instanceof ResponseError) || error.response.status !== 404) return false;
  try {
    const problem = await error.response.clone().json();
    return problem?.code === 'ADMISSION_NOT_FOUND';
  } catch {
    return false;
  }
}
