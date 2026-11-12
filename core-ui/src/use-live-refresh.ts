import { useEffect } from "react";
import { affects, subscribeLive } from "./live";

/** At most one refresh in flight; retain changes that arrive while it is running. */
export function useLiveRefresh(kind: string, id: string | undefined, refresh: () => unknown) {
  useEffect(() => {
    let stopped = false, running = false, dirty = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const run = async () => {
      timer = undefined;
      if (running) { dirty = true; return; }
      running = true;
      try { await refresh(); }
      finally {
        running = false;
        if (!stopped && dirty) { dirty = false; timer = setTimeout(run, id ? 50 : 750); }
      }
    };
    const unsubscribe = subscribeLive((change) => {
      if (!affects(change, kind, id)) return;
      if (running) dirty = true;
      else if (!timer) timer = setTimeout(run, id ? 50 : 750);
    });
    return () => { stopped = true; clearTimeout(timer); unsubscribe(); };
  }, [kind, id, refresh]);
}
