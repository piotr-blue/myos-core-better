import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import type { AccountIdentity, UserSession } from "@blue-labs/myos-sdk";
import { actingAccount } from "./accounts";
import { LessonClient } from "./client";
import { LessonHistory } from "./history";
import {
  lessons,
  lessonForRun,
  loadRun,
  resumesBootstrap,
  saveRun,
  stepCopy,
  type LessonRun,
} from "./model";
import { stringifyBlue } from "../blue-values";
import "./style.css";
import "./guide.css";

type Command = {
  token: string;
  kind: "bootstrap" | "operation";
  sessionId?: string;
  operation?: string;
  source?: string;
  request?: string;
  route?: any;
  exact?: boolean;
  execute: (displayedVersion?: string) => Promise<void>;
};
type Guide = {
  run?: LessonRun;
  busy: boolean;
  command?: Command;
  start: (run: LessonRun) => void;
  register: (token: string, form: HTMLFormElement | null) => void;
};
const GuideContext = createContext<Guide>({
  busy: false,
  start() {},
  register() {},
});
export const useExampleGuide = () => useContext(GuideContext);

/** Real form registration: the guide fills this form, then uses its normal submit event. */
export function useGuidedForm(
  kind: Command["kind"],
  apply: (command: Command) => void,
  sessionId?: string,
  operation?: string,
) {
  const guide = useExampleGuide(),
    form = useRef<HTMLFormElement>(null);
  const command =
    guide.command?.kind === kind &&
    (kind === "bootstrap" ||
      (guide.command.sessionId === sessionId &&
        guide.command.operation === operation))
      ? guide.command
      : undefined;
  useEffect(() => {
    if (!command) return;
    apply(command);
    guide.register(command.token, form.current);
    return () => guide.register(command.token, null);
  }, [command?.token]);
  return { command, form };
}

const activeKey = (userId: string) => `myos-active-example:${userId}`;
const switchKey = (userId: string) => `myos-example-switch:${userId}`;
function restored(userId: string) {
  const [route, query = ""] = location.hash.slice(1).split("?");
  const runId = new URLSearchParams(query).get("run");
  if (runId && runId !== "1") {
    const lesson = lessons.find((l) => l.id === route.split("/")[1]);
    if (lesson) return loadRun(userId, runId, lesson);
  }
  const saved = sessionStorage.getItem(activeKey(userId));
  if (!saved) return;
  const { id, exampleId } = JSON.parse(saved),
    lesson = lessons.find((l) => l.id === exampleId);
  if (lesson) return loadRun(userId, id, lesson);
}

export function ExampleGuide({
  user,
  account,
  selectAccount,
  navigate,
  children,
}: {
  user: UserSession;
  account?: AccountIdentity;
  selectAccount: (
    accountId: string,
    userId: string,
    generation: number,
    signal?: AbortSignal,
  ) => Promise<void>;
  navigate: (path: string, replace?: boolean, source?: "guide") => void;
  children: ReactNode;
}) {
  const [initial] = useState(() => {
    try {
      return { run: restored(user.userId), error: "" };
    } catch (e) {
      return { run: undefined, error: (e as Error).message };
    }
  });
  const [run, setRun] = useState<LessonRun | undefined>(initial.run);
  const [busy, setBusy] = useState(false),
    [status, setStatus] = useState(""),
    [error, setError] = useState(initial.error);
  const [command, setCommand] = useState<Command>(),
    [details, setDetails] = useState(false);
  const [auto, setAuto] = useState(false),
    [minimized, setMinimized] = useState(false);
  const latest = useRef({ account, user, navigate });
  latest.current = { account, user, navigate };
  const active = useRef<AbortController | undefined>(undefined);
  const queued = useRef<LessonRun | undefined>(undefined);
  const running = useRef(false),
    alive = useRef(true),
    form = useRef<{ token: string; element: HTMLFormElement } | undefined>(
      undefined,
    );
  useEffect(() => {
    alive.current = true;
    if (run)
      sessionStorage.setItem(
        activeKey(user.userId),
        JSON.stringify({ id: run.id, exampleId: run.exampleId }),
      );
    const continuation = sessionStorage.getItem(switchKey(user.userId));
    sessionStorage.removeItem(switchKey(user.userId));
    if (continuation && run) {
      try {
        const next = JSON.parse(continuation);
        if (
          next.runId === run.id &&
          next.step === run.step &&
          next.accountId === account?.accountId &&
          next.generation === account.membership.generation &&
          Date.now() - next.at < 30000
        ) {
          setStatus(
            `Switched to ${account.profile.displayName}. Continuing this step…`,
          );
          setAuto(true);
        }
      } catch {
        /* A damaged continuation never authorizes an action. */
      }
    }
    return () => {
      alive.current = false;
      active.current?.abort();
    };
  }, [user.userId]);
  const lesson = run ? lessonForRun(run) : undefined;
  function start(next: LessonRun) {
    // A launch link is a one-time instruction, never a history replay action.
    const [route, query = ""] = location.hash.slice(1).split("?");
    const params = new URLSearchParams(query);
    if (route === `examples/${next.exampleId}` && params.has("run")) {
      params.delete("run");
      latest.current.navigate(route + (params.size ? `?${params}` : ""), true, "guide");
    }
    if (run && (running.current || run.id !== next.id)) stop();
    if (running.current) {
      queued.current = next;
      return;
    }
    saveRun(next);
    sessionStorage.setItem(
      activeKey(user.userId),
      JSON.stringify({ id: next.id, exampleId: next.exampleId }),
    );
    setRun(next);
    setError("");
    setMinimized(false);
    setAuto(true);
  }
  function stop() {
    queued.current = undefined;
    active.current?.abort(new DOMException("Lesson stopped", "AbortError"));
    setAuto(false);
    setCommand(undefined);
    setError("");
    document
      .querySelectorAll(".example-highlight")
      .forEach((e) => e.classList.remove("example-highlight"));
    sessionStorage.removeItem(switchKey(user.userId));
    if (run) {
      run.stopped = true;
      saveRun(run);
      setRun({ ...run });
    }
    setStatus(
      "Lesson stopped. An action already submitted may still finish. Resume checks its result safely.",
    );
  }
  useEffect(() => {
    window.addEventListener("myos-signing-out", stop);
    window.addEventListener("myos-app-navigation", stop);
    const leaving = () => {
      if (running.current) stop();
    };
    window.addEventListener("pagehide", leaving);
    return () => {
      window.removeEventListener("myos-signing-out", stop);
      window.removeEventListener("myos-app-navigation", stop);
      window.removeEventListener("pagehide", leaving);
    };
  }, [run]);
  async function go() {
    if (!run || !lesson || running.current) return;
    const controller = new AbortController();
    active.current = controller;
    const signal = controller.signal;
    run.stopped = false;
    saveRun(run);
    const check = () => {
      signal.throwIfAborted();
      if (!alive.current) throw new DOMException("Guide closed", "AbortError");
    };
    const delay = async (ms: number) => {
      check();
      await new Promise<void>((resolve, reject) => {
        const abort = () => {
          clearTimeout(timer);
          reject(signal.reason);
        };
        const timer = setTimeout(() => {
          signal.removeEventListener("abort", abort);
          resolve();
        }, ms);
        signal.addEventListener("abort", abort, { once: true });
      });
      check();
    };
    const until = async (condition: () => boolean, message: string) => {
      const deadline = Date.now() + 15000;
      check();
      while (!condition()) {
        if (Date.now() > deadline) throw new Error(message);
        await delay(50);
      }
    };
    running.current = true;
    setBusy(true);
    setError("");
    try {
      const targetAccount = actingAccount(run, lesson);
      const membership = latest.current.user.accounts.find(
        (a) =>
          a.accountId === targetAccount && a.membership.status === "ACTIVE",
      );
      if (!membership)
        throw new Error(
          "This participant’s account is no longer available. Restore its membership to resume this run.",
        );
      if (latest.current.account?.accountId !== targetAccount) {
        setStatus(`Switching account to ${membership.profile.displayName}…`);
        document
          .querySelector(".workspace-controls")
          ?.classList.add("example-highlight");
        await selectAccount(
          targetAccount,
          user.userId,
          membership.membership.generation,
          signal,
        );
        await until(
          () =>
            latest.current.account?.accountId === targetAccount &&
            latest.current.account.userId === user.userId &&
            latest.current.account.membership.generation ===
              membership.membership.generation,
          "The participant workspace did not open.",
        );
      }
      check();
      const openDocument = (id: string, replace = false) => {
        check();
        const path = `documents/${encodeURIComponent(id)}`;
        // Keep the current view when a result belongs to the displayed document.
        if (location.hash.slice(1).split("?")[0] !== path)
          latest.current.navigate(path, replace, "guide");
      };
      const client = new LessonClient((text) => {
        if (!signal.aborted) setStatus(text);
      }, signal);
      // A retained admission is only observed, never sent again or rebuilt from a newer version.
      if (run.pending?.admission || run.pending?.payload) {
        setStatus("Resuming observation of the submitted action…");
        if (resumesBootstrap(run)) await client.start(run, lesson);
        else
          await client.runStep(run, lesson, openDocument);
      } else {
        let spec: Omit<Command, "token" | "execute">;
        let prepared: Awaited<ReturnType<LessonClient["prepare"]>> | undefined;
        if (resumesBootstrap(run)) {
          await client.prepareBootstrap(run, lesson);
          check();
          latest.current.navigate("documents/new", false, "guide");
          spec = { kind: "bootstrap", source: run.source };
          setStatus("Filling the starting document. Next: Start document.");
        } else {
          prepared = await client.prepare(run, lesson);
          check();
          openDocument(prepared.sessionId);
          if (prepared.step.clock) {
            setStatus("Observing signed timer events…");
            await client.runStep(run, lesson, () => {});
            setRun({ ...run });
            setStatus("Timer events recorded.");
            return;
          }
          spec = {
            kind: "operation",
            sessionId: prepared.sessionId,
            operation: prepared.step.operation,
            request: stringifyBlue(prepared.step.request || {}, 2),
            route: prepared.route,
            exact: prepared.step.requireExactDocumentVersion ?? true,
          };
          await until(
            () =>
              !!Array.from(
                document.querySelectorAll<HTMLButtonElement>(
                  `[data-document-session="${CSS.escape(prepared!.sessionId)}"] [role="tab"]`,
                ),
              ).find((t) => t.textContent === "Operations"),
            "The document’s Operations view did not open.",
          );
          const tab = Array.from(
            document.querySelectorAll<HTMLButtonElement>(
              `[data-document-session="${CSS.escape(prepared!.sessionId)}"] [role="tab"]`,
            ),
          ).find((t) => t.textContent === "Operations")!;
          tab.classList.add("example-highlight");
          latest.current.navigate(
            `documents/${encodeURIComponent(prepared.sessionId)}?view=Operations`,
            false,
            "guide",
          );
          await delay(100);
          tab.classList.remove("example-highlight");
          if (prepared.step.accessCheck) {
            setStatus(
              "Inspecting the available operations. No refund request will be submitted.",
            );
            await delay(250);
            await client.runStep(run, lesson, () => {}, prepared);
            setStatus(
              "Confirmed: the hotel cannot refund the restaurant payment. No request was submitted.",
            );
            return;
          }
          setStatus(`Filling ${spec.operation}. Next: Invoke operation.`);
        }
        const token = crypto.randomUUID();
        let resolve!: () => void, reject!: (e: unknown) => void;
        const finished = new Promise<void>((yes, no) => {
          resolve = yes;
          reject = no;
        });
        // Attach a rejection handler immediately; observation below still receives the error.
        void finished.catch(() => {});
        let submitted = false;
        setCommand({
          ...spec,
          token,
          execute: async (displayedVersion) => {
            if (submitted || signal.aborted) return;
            submitted = true;
            try {
              if (spec.kind === "bootstrap") await client.start(run, lesson);
              else {
                if (!displayedVersion || !prepared)
                  throw new Error("Wait for the document version to load.");
                // Account access reports can advance the version during the teaching pause.
                // Resolve the authorized operation again at submission, retaining the exact
                // scripted request (including its approval revision) and version precondition.
                setStatus(
                  "Checking the current operation and submitting the prepared request…",
                );
                const current = await client.prepare(
                  run,
                  lesson,
                  prepared.sessionId,
                );
                if (
                  current.route?.timeline !== prepared.route?.timeline ||
                  current.route?.actor !== prepared.route?.actor
                )
                  throw new Error(
                    "The authorized route changed. Review the participant and click Go again.",
                  );
                if (prepared.step.requestDocument &&
                    stringifyBlue(current.step.request) !== stringifyBlue(prepared.step.request))
                  throw new Error(
                    "The source document changed. Review its current version and click Go again.",
                  );
                await client.runStep(run, lesson, () => {}, current);
              }
              resolve();
            } catch (e) {
              reject(e);
            }
          },
        });
        await until(
          () => form.current?.token === token,
          "The guided form is unavailable. Return to the example and retry.",
        );
        const element = form.current!.element;
        element.classList.add("example-highlight");
        element.scrollIntoView({ block: "center", behavior: "smooth" });
        await delay(250);
        if (
          !element.isConnected ||
          latest.current.account?.accountId !== targetAccount
        )
          throw new Error(
            "The screen or account changed before submission. Click Go to resume safely.",
          );
        if (!element.reportValidity())
          throw new Error("Review the highlighted form before continuing.");
        element.requestSubmit();
        await finished;
        check();
        element.classList.remove("example-highlight");
      }
      check();
      if (run.root)
        openDocument(
          run.results.at(-1)?.sessionId || run.root,
          location.hash === "#documents/new",
        );
      setStatus("Result confirmed. Ready for the next step.");
    } catch (e) {
      if (alive.current && !signal.aborted) setError((e as Error).message);
    } finally {
      if (active.current !== controller) return;
      active.current = undefined;
      running.current = false;
      if (alive.current) {
        setRun({ ...run });
        setCommand(undefined);
        setBusy(false);
      }
      document
        .querySelectorAll(".example-highlight")
        .forEach((e) => e.classList.remove("example-highlight"));
      const nextRun = queued.current;
      queued.current = undefined;
      if (nextRun && alive.current) start(nextRun);
    }
  }
  useEffect(() => {
    if (auto && run) {
      setAuto(false);
      void go();
    }
  }, [auto, run]);
  const done = !!run?.root && run.step === lesson?.steps.length && !run.pending;
  const starting = !!run && resumesBootstrap(run);
  const next = lesson?.steps[run?.step || 0],
    copy = next && stepCopy(next);
  const actor =
    run &&
    lesson &&
    user.accounts.find((a) => a.accountId === actingAccount(run, lesson))
      ?.profile.displayName;
  return (
    <GuideContext.Provider
      value={{
        run,
        busy,
        command,
        start,
        register: (token, element) => {
          if (element) form.current = { token, element };
          else if (form.current?.token === token) form.current = undefined;
        },
      }}
    >
      {children}
      {run && lesson ? (
        <aside
          className={`example-guide ${minimized ? "is-minimized" : ""}`}
          aria-label="Example guide"
          data-testid="example-guide"
        >
          <header>
            <span className="example-eyebrow">
              {done
                ? "EXAMPLE COMPLETE"
                : `STEP ${starting ? 1 : run.step + 2} OF ${lesson.steps.length + 1}`}{" "}
              · {lesson.title}
            </span>
            <div className="example-guide-controls">
              {!done && (
                <button className="link" onClick={stop} disabled={run.stopped}>
                  Stop lesson
                </button>
              )}
              {run.stopped && (
                <button
                  className="link"
                  onClick={() => {
                    active.current?.abort();
                    active.current = undefined;
                    queued.current = undefined;
                    running.current = false;
                    setBusy(false);
                    sessionStorage.removeItem(activeKey(user.userId));
                    setRun(undefined);
                  }}
                >
                  Close guide
                </button>
              )}
              <button
                className="link"
                onClick={() => setMinimized(!minimized)}
                aria-label={minimized ? "Expand guide" : "Minimize guide"}
              >
                {minimized ? "Expand" : "Minimize"}
              </button>
            </div>
          </header>
          {!minimized && (
            <>
              <div className="example-guide-body">
                <div>
                  <h2>
                    {done
                      ? "You’ve completed the process."
                      : starting
                        ? "Start the shared document"
                        : copy?.title}
                  </h2>
                  <p>
                    {done
                      ? "Every step has a recorded result. Your documents remain available in Documents."
                      : starting
                        ? "Open Documents, fill the source and start it with your selected accounts."
                        : copy?.description}
                  </p>
                  {!done && (
                    <p className="example-actor">
                      Acting account: <strong>{actor || "Unavailable"}</strong>
                      {next?.channel && !starting
                        ? ` · ${next.source || next.channel}`
                        : ""}
                    </p>
                  )}
                </div>
                <button
                  className="primary"
                  disabled={busy}
                  onClick={
                    done
                      ? () => {
                          sessionStorage.removeItem(activeKey(user.userId));
                          setRun(undefined);
                        }
                      : () => void go()
                  }
                >
                  {done
                    ? "Finish"
                    : run.stopped
                      ? "Resume lesson"
                      : busy
                        ? "Working…"
                        : run.pending
                          ? "Resume"
                          : "Go →"}
                </button>
              </div>
              <div aria-live="polite" className="example-progress">
                {done
                  ? run.results.at(-1)?.status === "EXPECTED_UNAVAILABLE"
                    ? "All steps completed. The final step confirmed no authorized action was available. No request was submitted."
                    : "All steps completed. You can continue exploring your documents."
                  : run.stopped
                    ? "Lesson stopped. An action already submitted may still finish. Resume checks its result safely."
                    : status}
              </div>
              {error && (
                <p role="alert" className="error">
                  {error}
                </p>
              )}
              <footer>
                <span>
                  {run.results.length} of {lesson.steps.length + 1} steps
                  confirmed
                </span>
                <button className="link" onClick={() => setDetails(!details)}>
                  {details ? "Hide results" : "Results & timings"}
                </button>
              </footer>
              {details && <LessonHistory run={run} />}
            </>
          )}
        </aside>
      ) : error ? (
        <p role="alert" className="error">
          {error}
        </p>
      ) : null}
    </GuideContext.Provider>
  );
}
