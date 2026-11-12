import { accountSignal, captureContext } from "./account-context";
import { authenticatedFetch } from "./transport";

export type Change = { reset?: boolean; changes?: Record<string, string[]> };
export type LiveState = "connecting" | "live" | "reconnecting";
const listeners = new Set<(change: Change) => void>();
let revision = 0;
let connected = false;
export const liveRevision = () => revision;
export function subscribeLive(listener: (change: Change) => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
export function affects(change: Change, kind: string, id?: string) {
  return !!change.reset || !!change.changes?.[kind]?.some((value) => !id || value === id ||
    (kind === "documents" && value.startsWith("graph-") && id.startsWith(`${value}.`)));
}
function dispatch(change: Change) {
  revision++;
  for (const listener of listeners) listener(change);
}
// Capture the revision before issuing a request: a fast commit must not be missed
// between receiving a pending response and registering the waiter.
export function waitForLiveChange(since: number, kind: string, id: string, maxWaitMs = 10000) {
  if (revision !== since) return Promise.resolve();
  return new Promise<void>((resolve) => {
    const done = () => { clearTimeout(timer); unsubscribe(); resolve(); };
    const unsubscribe = subscribeLive((change) => { if (affects(change, kind, id)) done(); });
    const timer = setTimeout(done, Math.min(maxWaitMs, connected ? 10000 : 500));
  });
}
export function startLive(onState: (state: LiveState) => void) {
  const context = captureContext();
  const controller = new AbortController();
  const scope = accountSignal();
  const stop = () => {
    connected = false;
    controller.abort();
    scope.removeEventListener("abort", stop);
  };
  // A workspace handoff ends this owner, even while the departing page is still alive.
  scope.addEventListener("abort", stop, { once: true });
  if (scope.aborted) stop();
  let failures = 0;
  const sleep = (ms: number) => new Promise<void>((resolve) => {
    const finish = () => { clearTimeout(timer); controller.signal.removeEventListener("abort", finish); resolve(); };
    const timer = setTimeout(finish, ms);
    controller.signal.addEventListener("abort", finish, { once: true });
  });
  void (async () => {
    while (!controller.signal.aborted) {
      try {
        onState(failures ? "reconnecting" : "connecting");
        const response = await authenticatedFetch("live", { signal: controller.signal, headers: { Accept: "text/event-stream" } }, context);
        if (!response.ok || !response.body) throw new Error("Live connection unavailable");
        const reader = response.body.getReader();
        const cancel = () => { void reader.cancel().catch(() => {}); };
        controller.signal.addEventListener("abort", cancel, { once: true });
        const decoder = new TextDecoder();
        let buffer = "";
        // Headers alone do not prove the proxy streams. Require the initial reset frame.
        let received = false, initialFrame = false;
        const watchdog = setInterval(() => { if (!received) void reader.cancel(); received = false; }, 20000);
        try {
          while (!controller.signal.aborted) {
            const { value, done } = await reader.read();
            if (done || controller.signal.aborted) break;
            received = true;
            buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
            if (buffer.length > 262144) throw new Error("Live frame too large");
            let end: number;
            while (!controller.signal.aborted && (end = buffer.indexOf("\n\n")) >= 0) {
              const frame = buffer.slice(0, end); buffer = buffer.slice(end + 2);
              const data = frame.split("\n").filter((line) => line.startsWith("data: ")).map((line) => line.slice(6)).join("\n");
              if (data) { initialFrame = true; connected = true; failures = 0; onState("live"); dispatch(JSON.parse(data)); }
            }
          }
        } finally {
          controller.signal.removeEventListener("abort", cancel);
          clearInterval(watchdog);
          await reader.cancel().catch(() => {});
        }
        connected = false;
        if (!initialFrame && !controller.signal.aborted) throw new Error("Live stream closed without a frame");
        if (!controller.signal.aborted) await sleep(100);
      } catch {
        connected = false;
        if (controller.signal.aborted) break;
        onState("reconnecting");
        // Disconnected fallback only; normal connected operation never polls.
        dispatch({ reset: true });
        await sleep(Math.min(10000, 500 * 2 ** Math.min(failures++, 5)));
      }
    }
  })();
  return stop;
}
