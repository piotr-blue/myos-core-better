const listeners = new Set<() => void>();
let waiting = 0;
export const serviceWaiting = () => waiting > 0;
export function subscribeServiceWaiting(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
function changeWaiting(delta: number) {
  waiting += delta;
  for (const listener of listeners) listener();
}
export function replayable(options: RequestInit) {
  const method = (options.method || "GET").toUpperCase();
  return method === "GET" || method === "HEAD" ||
    (new Headers(options.headers).has("Idempotency-Key") && typeof options.body === "string");
}
function pause(ms: number, signal?: AbortSignal | null) {
  return new Promise<void>((resolve, reject) => {
    const done = () => { clearTimeout(timer); signal?.removeEventListener("abort", aborted); resolve(); };
    const aborted = () => { clearTimeout(timer); signal?.removeEventListener("abort", aborted); reject(signal?.reason); };
    const timer = setTimeout(done, ms);
    signal?.addEventListener("abort", aborted, { once: true });
    if (signal?.aborted) aborted();
  });
}

// Retries only requests the caller can safely replay. Bodies and idempotency keys
// stay outside this loop. A prolonged outage remains a visible, bounded failure.
export async function withServiceRecovery<T>(
  operation: (signal: AbortSignal) => Promise<T>,
  { signal, timeoutMs = 300000 }: { signal?: AbortSignal | null; timeoutMs?: number } = {},
) {
  const deadline = Date.now() + timeoutMs;
  let announced = false;
  try {
    for (let attempt = 0; ; attempt++) {
      signal?.throwIfAborted();
      const controller = new AbortController();
      const abort = () => controller.abort(signal?.reason);
      signal?.addEventListener("abort", abort, { once: true });
      let timedOut = false;
      const timer = setTimeout(() => { timedOut = true; controller.abort(); }, Math.max(1, Math.min(20000, deadline - Date.now())));
      let failure: unknown;
      try {
        return await operation(controller.signal);
      } catch (error: any) {
        signal?.throwIfAborted();
        if (!timedOut && !(error instanceof TypeError) && ![502, 503, 504].includes(error?.status)) throw error;
        failure = error;
      } finally {
        clearTimeout(timer);
        signal?.removeEventListener("abort", abort);
      }
      if (Date.now() >= deadline) throw failure;
      if (!announced) { announced = true; changeWaiting(1); }
      await pause(Math.min(deadline - Date.now(), 1000 * 2 ** Math.min(attempt, 2)), signal);
      if (Date.now() >= deadline) throw failure;
    }
  } finally {
    if (announced) changeWaiting(-1);
  }
}
