import { useCallback, useEffect, useRef, useState } from "react";
import { apiResponse } from "./api";
import { emptyResourceState, ResourcePager } from "./resource-page";
import { useLiveRefresh } from "./use-live-refresh";

export function useResourcePage(kind: string, query: string) {
  const [state, setState] = useState(emptyResourceState);
  const pager = useRef<ResourcePager | undefined>(undefined);
  useEffect(() => {
    setState(emptyResourceState());
    const current = new ResourcePager(
      `${kind}?${query}`,
      async (path, signal) => {
        const { data, readState } = await apiResponse(path, { signal });
        return { ...data, readState };
      },
      setState,
    );
    pager.current = current;
    // Debounce filter edits without delaying the first request until every page is read.
    const timer = setTimeout(() => {
      void current.first();
    }, 150);
    return () => {
      clearTimeout(timer);
      current.dispose();
      if (pager.current === current) pager.current = undefined;
    };
  }, [kind, query]);
  const refresh = useCallback(() => pager.current?.first(), [kind, query]);
  const more = useCallback(() => pager.current?.more(), [kind, query]);
  useLiveRefresh(kind, undefined, refresh);
  return { ...state, refresh, more };
}
