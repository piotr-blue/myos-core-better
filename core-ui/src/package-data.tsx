import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { apiResponse } from "./api";
import { affects, subscribeLive } from "./live";
import type { ReadState } from "./transport";
export type TypeInfo = {
  name: string;
  package: string;
  packageId: string;
  blueId: string;
};
const Context = createContext<{
  catalog: any;
  error: string;
  readState?: ReadState;
  reload: () => Promise<void>;
  types: Record<string, TypeInfo>;
}>({ catalog: null, error: "", reload: async () => {}, types: {} });
export function PackageProvider({ children }: { children: ReactNode }) {
  const [catalog, setCatalog] = useState<any>();
  const [error, setError] = useState("");
  const [readState, setReadState] = useState<ReadState>();
  const refresh = useRef<() => Promise<void>>(() => Promise.resolve());
  const reload = useCallback(() => refresh.current(), []);
  useEffect(() => {
    let active = true, pending: Promise<void> | undefined, generation = 0;
    let projectedSeen = false;
    const controller = new AbortController();
    const load = () => {
      generation++;
      if (pending) return pending;
      pending = (async () => {
        try {
          let requested: number;
          do {
            requested = generation;
            try {
              const result = await apiResponse("packages", { signal: controller.signal });
              // Resets request another read; they do not make a successful,
              // serialized current-account response unusable.
              if (active && !(projectedSeen && result.readState === "initial")) {
                projectedSeen ||= result.readState === "projected";
                setCatalog(result.data);
                setReadState(result.readState);
                setError("");
              }
            } catch (failure: any) {
              if (active && requested === generation) setError(failure.message);
            }
            // Invalidation during initial loading or a failed reload still needs a read.
          } while (active && requested !== generation);
        } finally {
          // Release the slot in the same microtask as the last generation check.
          pending = undefined;
        }
      })();
      return pending;
    };
    refresh.current = load;
    const unsubscribe = subscribeLive(change => {
      if (affects(change, "packages")) void load();
    });
    void load();
    return () => {
      active = false;
      controller.abort();
      refresh.current = () => Promise.resolve();
      unsubscribe();
    };
  }, []);
  const types = useMemo(
    () =>
      Object.fromEntries(
        (catalog?.items || []).flatMap((p: any) =>
          p.types.map((t: any) => [
            t.blueId,
            { ...t, package: p.name, packageId: p.id },
          ]),
        ),
      ),
    [catalog],
  );
  return (
    <Context.Provider value={{ catalog, error, readState, reload, types }}>
      {children}
    </Context.Provider>
  );
}
export const usePackages = () => useContext(Context);
