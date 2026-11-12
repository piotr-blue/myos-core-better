/** Options shared by SDK polling helpers; stopping a wait does not cancel a server command. */
export interface WaitOptions {
  timeoutMs?: number;
  pollIntervalMs?: number;
  signal?: AbortSignal;
}

export function createWait(options: WaitOptions) {
  const timeoutMs = options.timeoutMs ?? 60_000;
  const interval = options.pollIntervalMs ?? 250;
  if (![timeoutMs, interval].every(value => Number.isInteger(value) && value > 0 && value <= 2_147_483_647)) {
    throw new Error('Timeout and polling interval must be positive integers no greater than 2147483647');
  }
  const signal = AbortSignal.any([AbortSignal.timeout(timeoutMs), ...(options.signal ? [options.signal] : [])]);
  return { signal, interval };
}

export function sleep(interval: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    signal.throwIfAborted();
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, interval);
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal.reason);
    };
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

/** Poll immediately, then at intervals. Forward signal to reads to abort in-flight requests. */
export async function waitFor<T>(
  read: (signal: AbortSignal) => Promise<T>,
  complete: (value: T) => boolean,
  options: WaitOptions = {},
): Promise<T> {
  const { signal, interval } = createWait(options);
  try {
    while (true) {
      signal.throwIfAborted();
      const value = await read(signal);
      signal.throwIfAborted();
      if (complete(value)) return value;
      await sleep(interval, signal);
    }
  } catch (error) {
    if (signal.aborted) {
      throw new Error('Stopped waiting. Accepted commands were not canceled.', { cause: error });
    }
    throw error;
  }
}
