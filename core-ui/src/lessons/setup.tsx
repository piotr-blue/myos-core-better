import { useEffect, useRef, useState } from "react";
import type { AccountIdentity, UserSession } from "@blue-labs/myos-sdk";
import { CreateAccount } from "../account-selection";
import {
  createRun,
  mainRoles,
  mappingIssue,
  runnable,
  suggestAccounts,
} from "./accounts";
import { exampleValue } from "./guidance";
import { lessons } from "./model";
import { useExampleGuide } from "./guide";

export function ExampleSetup({
  id,
  autoStart = false,
  user,
  account,
  reload,
}: {
  id: string;
  autoStart?: boolean;
  user: UserSession;
  account?: AccountIdentity;
  reload: () => Promise<void>;
}) {
  const lesson = lessons.find((l) => l.id === id);
  const guide = useExampleGuide();
  const [mapping, setMapping] = useState<Record<string, string>>(() =>
    lesson ? suggestAccounts(lesson, user, account?.accountId) : {},
  );
  const [creating, setCreating] = useState<string>();
  const [error, setError] = useState("");
  const autoStarted = useRef(false);
  const accounts = user.accounts.filter(
    (a) => a.membership.status === "ACTIVE",
  );
  const mappingError = lesson ? mappingIssue(lesson, mapping) : "";
  function start() {
    if (!lesson) return;
    try {
      guide.start(createRun(lesson, user, mapping));
    } catch (e) {
      setError((e as Error).message);
    }
  }
  useEffect(() => {
    if (
      autoStart &&
      lesson &&
      mainRoles(lesson).length === 1 &&
      accounts.length &&
      !autoStarted.current &&
      guide.run?.exampleId !== id
    ) {
      autoStarted.current = true;
      start();
    }
  }, [lesson, accounts.length, autoStart]);
  if (!lesson)
    return (
      <section className="panel panel-pad">
        <h1>Example not found</h1>
        <a href="/examples/">Browse examples</a>
      </section>
    );
  return (
    <section className="example-setup">
      <a className="back-link" href={`/developers/examples/${lesson.id}`}>
        ← About this example
      </a>
      <span className="example-eyebrow">
        GUIDED EXAMPLE · {lesson.steps.length + 1} STEPS
      </span>
      <h1>{lesson.title}</h1>
      <p className="example-lead">
        {exampleValue[id]?.[0] || lesson.description}
      </p>
      <div className="example-summary">
        <h2>What you’ll learn</h2>
        <p>{exampleValue[id]?.[1]}</p>
        <p>
          Start the document, approve its terms and follow its actions. The
          guide opens the right account and screen, fills the request and
          submits it. Each step waits for a recorded result.
        </p>
      </div>
      {!runnable(lesson) ? (
        <p role="status">
          This composition is a source preview.{" "}
          <a href="/developers/app/#examples/08-participants-agents">
            Try the supported representative example.
          </a>
        </p>
      ) : (
        <>
          <h2>Who plays each part?</h2>
          <p>
            Choose accounts you manage. You can use one account for several
            roles, or create separate accounts to see how independent
            participants work together. Creating an account does not require
            another login.
          </p>
          <div className="example-mappings">
            {mainRoles(lesson).map((role) => (
              <div className="example-mapping" key={role.name}>
                <label htmlFor={`map-${role.name}`}>
                  <strong>{role.label}</strong>
                  <span>Participant in this example</span>
                </label>
                <span aria-hidden="true">→</span>
                <select
                  id={`map-${role.name}`}
                  value={mapping[role.name] || ""}
                  onChange={(e) =>
                    e.target.value === "__create"
                      ? setCreating(role.name)
                      : setMapping({ ...mapping, [role.name]: e.target.value })
                  }
                >
                  <option value="">Choose an account</option>
                  {accounts.map((a) => (
                    <option key={a.accountId} value={a.accountId}>
                      {a.profile.displayName} · {a.accountId.slice(0, 8)}
                    </option>
                  ))}
                  <option value="__create">＋ Create Account</option>
                </select>
                <button
                  className="secondary"
                  onClick={() => setCreating(role.name)}
                >
                  Create Account
                </button>
              </div>
            ))}
          </div>
          {lesson.roles.some((r) => r.kind === "representative") && (
            <p>
              Your signed-in user will act as the Buyer’s assistant, within the
              representative’s allowed operations.
            </p>
          )}
          {creating && (
            <section
              className="panel panel-pad"
              aria-label="Create an example account"
            >
              <h2>
                Create an account for{" "}
                {mainRoles(lesson).find((r) => r.name === creating)?.label}
              </h2>
              <CreateAccount
                user={user}
                additional={accounts.length > 0}
                onComplete={async (result) => {
                  setMapping((m) => ({ ...m, [creating]: result.accountId }));
                  await reload();
                  setCreating(undefined);
                }}
              />
              <button className="link" onClick={() => setCreating(undefined)}>
                Cancel
              </button>
            </section>
          )}
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          {mappingError && (
            <p role="status" className="error">
              {mappingError}
            </p>
          )}
          <button
            className="primary"
            disabled={
              !!creating ||
              !!mappingError ||
              mainRoles(lesson).some((r) => !mapping[r.name])
            }
            onClick={start}
          >
            Start example →
          </button>
          <p className="subtle">
            {guide.run
              ? "Starting stops the current lesson and opens this one. Earlier documents and results remain in your account."
              : "You control the pace. No further action runs until you click Go."}
          </p>
          {/Payment/.test(lesson.source) && (
            <p className="subtle">
              Test payment instructions only. No real money is moved.
            </p>
          )}
        </>
      )}
      <details>
        <summary>View the complete starting document</summary>
        <pre className="code">{lesson.source}</pre>
      </details>
    </section>
  );
}
