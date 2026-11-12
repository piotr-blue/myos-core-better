import {
  AdmissionsApi,
  DocumentsApi,
  waitFor,
  waitForAdmission,
  waitForDocument,
} from "@blue-labs/myos-sdk";
import { coreConfiguration, accountResult, accountApi } from "../account-api";
import { assertContext, captureContext, accountContext } from "../account-context";
import { stringifyBlue } from "../blue-values";
import { processRuntime } from "../process-context";
import { processEdition, sameSelection } from "../process-packages";
import { prepareExampleDocument } from "../example-document";
import {
  observedOutcome,
  definitionOf,
  lessonForRun,
  lessonForRuntime,
  pointer,
  saveRun,
  type Lesson,
  type LessonRun,
  type LessonStep,
  type Measurement,
} from "./model";

// Bound every guided wait, including document outcomes after admission succeeds.
const waitOptions = { timeoutMs: 120_000, pollIntervalMs: 2_000 };
class MissingTarget extends Error {}
export class LessonClient {
  private context = captureContext();
  private documents: DocumentsApi;
  private admissions: AdmissionsApi;
  private waitOptions: typeof waitOptions & { signal?: AbortSignal };
  constructor(private update: (text: string) => void, private signal?: AbortSignal) {
    const config = coreConfiguration(this.context, this.context, signal);
    this.documents = new DocumentsApi(config);
    this.admissions = new AdmissionsApi(config);
    this.waitOptions = { ...waitOptions, signal };
  }
  private async checked<T>(request: Promise<T>) {
    // The SDK has already created this promise: always consume its rejection,
    // including when Stop aborts before this observation begins.
    const value = await accountResult(request);
    this.signal?.throwIfAborted();
    assertContext(this.context);
    return value;
  }
  private async pages<T>(
    read: (
      token?: string,
    ) => Promise<{ items: T[]; nextPageToken?: string | null }>,
  ) {
    const items: T[] = [];
    let token: string | undefined;
    const seen = new Set<string>();
    do {
      const page = await this.checked(read(token));
      items.push(...page.items);
      token = page.nextPageToken || undefined;
      if (token && seen.has(token))
        throw new Error("Repeated continuation token.");
      if (token) seen.add(token);
    } while (token);
    return items;
  }
  async content(sessionId: string) {
    return this.checked(this.documents.getDocumentContent({ sessionId }));
  }
  async target(root: string, path = ""): Promise<string> {
    return this.targetIn(this.documents, root, path);
  }
  private async targetIn(documents: DocumentsApi, root: string, path = ""): Promise<string> {
    if (!path || path === "/") return root;
    let current = root,
      remaining = path;
    const visited = new Set<string>();
    while (remaining) {
      if (visited.has(current + remaining))
        throw new Error("Cyclic target path.");
      visited.add(current + remaining);
      const rows = await this.pages((token) =>
        documents.getDocumentRelationships({
          sessionId: current,
          direction: "outgoing",
          limit: 100,
          pageToken: token,
        }),
      );
      const row = rows
        .filter(
          (r) =>
            r.source.sessionId &&
            (remaining === r.path || remaining.startsWith(r.path + "/")),
        )
        .sort((a, b) => b.path.length - a.path.length)[0];
      if (!row)
        throw new MissingTarget(
          `The connected document at ${path} is not available yet. Wait for the preceding operation to finish.`,
        );
      current = row.source.sessionId!;
      remaining = remaining.slice(row.path.length);
    }
    return current;
  }
  /** Finish a shared lesson step only once every mapped workspace has observed it. */
  private async synchronize(run: LessonRun, step?: LessonStep, entryId?: string) {
    const peers = [...new Set(Object.values(run.identities || {}))]
      .filter((id) => id !== this.context?.accountId);
    if (!peers.length) return;
    const started = Date.now();
    for (const accountId of peers) {
      const account = await this.checked(
        accountApi({ accountId }, this.context).getAccount({}, { signal: this.signal }),
      );
      if (account.accountId !== accountId || account.userId !== run.userId ||
          account.membership.status !== "ACTIVE")
        throw new Error("A mapped workspace is no longer available to this user.");
      const documents = new DocumentsApi(coreConfiguration(accountContext(account), this.context, this.signal));
      this.update(`Waiting for ${account.profile.displayName} to observe this step…`);
      await waitFor(async (signal) => {
        try {
          if (!step || !entryId) {
            const document = await this.checked(documents.getDocument({ sessionId: run.root! }, { signal }));
            return !!document.lastStable && document.processing?.state === "READY";
          }
          const sessionId = await this.targetIn(documents, run.root!, step.target);
          const input = await this.checked(documents.getDocumentInputsEntryblueid({ sessionId, entryBlueId: entryId }, { signal }));
          return observedOutcome(input, step.status === "FAILED", step.expectedRejectionReason);
        } catch (error) {
          if (error instanceof MissingTarget || (error as any)?.status === 404) return false;
          throw error;
        }
      }, Boolean, this.waitOptions);
    }
    run.pending!.synchronizationMs = (run.pending!.synchronizationMs || 0) + Date.now() - started;
    saveRun(run);
  }
  private intent(
    run: LessonRun,
    kind: "bootstrap" | "operation" = "operation",
  ) {
    if (!run.pending) {
      run.pending = {
        kind,
        key: crypto.randomUUID(),
        startedAt: new Date().toISOString(),
        accountId: this.context?.accountId,
      };
      saveRun(run);
    }
    if (run.pending.accountId !== this.context?.accountId)
      throw new Error(
        "Resume this accepted action from the workspace that submitted it.",
      );
    return run.pending;
  }
  private async admitted(run: LessonRun, submit: () => Promise<any>) {
    const intent = this.intent(run);
    if (!intent.admission) {
      intent.admission = await this.checked(submit());
      intent.submittedAt = Date.now();
      saveRun(run);
    }
    const result = await this.checked(waitForAdmission(
      {
        getAdmission: async (r, opts) => {
          this.signal?.throwIfAborted();
          assertContext(this.context);
          try {
            // The SDK needs the original ResponseError to recognize initial
            // projection lag. Convert errors for the UI only after its wait.
            return await this.admissions.getAdmission(r, opts);
          } finally {
            this.signal?.throwIfAborted();
            assertContext(this.context);
          }
        },
      },
      intent.admission,
      {
        ...this.waitOptions,
        onUpdate: (a) =>
          this.update(`Core admission: ${a.status.toLowerCase()}`),
      },
    ));
    intent.admittedAt ??= Date.now();
    intent.admission = result;
    saveRun(run);
    return result.details;
  }
  private finish(
    run: LessonRun,
    action: string,
    target: string,
    status: string,
    sessionId: string,
    entryId?: string,
  ): Measurement {
    const intent = run.pending!,
      started = Date.parse(intent.startedAt),
      submitted = intent.submittedAt || started,
      admitted = intent.admittedAt || submitted,
      finished = Date.now();
    const measurement = {
      action,
      target,
      status,
      sessionId,
      entryId,
      interrupted: intent.interrupted || undefined,
      startedAt: intent.startedAt,
      submissionMs: submitted - started,
      admissionMs: admitted - submitted,
      processingMs: finished - admitted,
      synchronizationMs: intent.synchronizationMs || 0,
      totalMs: finished - started,
    };
    run.results.push(measurement);
    run.pending = undefined;
    saveRun(run);
    return measurement;
  }
  async prepareBootstrap(run: LessonRun, lesson: Lesson) {
    if (!run.pending?.admission) {
      this.update("Checking the selected workspaces…");
      const execution = await processRuntime(Object.values(run.identities || {}), this.signal, run.selection);
      const { runtime } = execution;
      assertContext(this.context);
      if (run.selection) {
        const selected = lessonForRun(run);
        if (!sameSelection(run.selection.packages, selected.packages) ||
            definitionOf(lessonForRuntime(run.exampleId, runtime)) !== run.definition)
          throw new Error("The saved example definition does not match its runtime and package selection.");
        if (!sameSelection(run.selection.runtime, runtime))
          throw new Error("This run was prepared for a different workspace runtime. Start a new run; existing documents are preserved.");
      } else {
        // Older unfinished requests already used the retained pins. Never reinterpret
        // an uncertain submission as the newer process edition on retry.
        if (run.pending && (execution.executionSelection || processEdition(runtime) !== "finalGas"))
          throw new Error("This unfinished run used the earlier package edition. Start a new run; any existing document is preserved.");
        const selected = lessonForRuntime(run.exampleId, runtime);
        if (!run.pending) {
          run.source = prepareExampleDocument(selected.source, selected.roles, run.identities || {},
            this.context!.accountId!, run.representatives || {});
          run.definition = definitionOf(selected);
        } else if (run.definition !== definitionOf(selected)) {
          throw new Error("This unfinished run uses a different example definition. Its original request is preserved.");
        }
        run.selection = { ...execution, packages: [...selected.packages] };
        saveRun(run);
      }
    }
  }
  async start(run: LessonRun, lesson: Lesson) {
    await this.prepareBootstrap(run, lesson);
    if (run.pending) run.pending.interrupted = true;
    const intent = this.intent(run, "bootstrap");
    this.update("Starting the document and binding its participants…");
    const result = await this.admitted(run, () =>
      this.documents.bootstrapDocument({
        idempotencyKey: intent.key,
        bootstrapDocument: { document: run.source, packages: run.selection?.packages ?? lesson.packages,
          ...(run.selection?.executionSelection ? { executionSelection: run.selection.executionSelection } : {}) },
      }),
    );
    run.root = result.sessionId;
    saveRun(run);
    this.update("Waiting for the initialized document…");
    await waitForDocument(
      {
        getDocument: (r, opts) =>
          this.checked(this.documents.getDocument(r, opts)),
      },
      run.root!,
      this.waitOptions,
    );
    await this.synchronize(run);
    return this.finish(run, "Bootstrap", "/", "INITIALIZED", run.root!);
  }
  async prepare(run: LessonRun, lesson: Lesson, resolvedSessionId?: string) {
    let step = lesson.steps[run.step];
    if (!run.root || !step) throw new Error("No next operation.");
    const sessionId =
      resolvedSessionId || (await this.target(run.root, step.target));
    const document = await this.checked(
      this.documents.getDocument({ sessionId }),
    );
    if (step.clock) return { sessionId, document, step };
    const operations = await this.pages((token) =>
      this.documents.getDocumentOperations({
        sessionId,
        limit: 100,
        pageToken: token,
      }),
    );
    const operation = operations.find((o) => o.name === step.operation);
    const route = step.source
      ? operations
          .flatMap((o) => o.routes)
          .find((r) => r.channel === step.source)
      : operation?.routes.find((r) => r.channel === step.channel);
    if (step.accessCheck === "unavailable" && route)
      throw new Error(
        "This example expected the operation to be unavailable, but Core offers an authorized route.",
      );
    if (!step.accessCheck && (!operation || !route))
      throw new Error(
        `The selected workspace has no authorized ${step.channel} route for ${step.operation}. Use that participant’s workspace and retry.`,
      );
    if (!document.lastStable?.documentRef)
      throw new Error("Wait for the initialized document.");
    if (step.requestDocument !== undefined) {
      if (step.requestDocument !== "root" || !step.request ||
          typeof step.request !== "object" || Array.isArray(step.request))
        throw new Error("This example has an unsupported document-reference request.");
      const root = sessionId === run.root ? document : await this.checked(
        this.documents.getDocument({ sessionId: run.root }),
      );
      if (!root.lastStable?.documentRef)
        throw new Error("Wait for the initialized source document.");
      step = { ...step, request: { ...step.request, document: root.lastStable.documentRef } };
    }
    return { sessionId, document, step, operation, route };
  }
  private async verifyState(run: LessonRun, step: LessonStep) {
    if (step.expect) {
      this.update("Checking the resulting state…");
      await waitFor(
        async () => {
          const state = await this.checked(
            this.documents.getDocumentContent({ sessionId: run.root! }),
          );
          for (const [path, expected] of Object.entries(step.expect!)) {
            let actual = pointer(state, path);
            if (stringifyBlue(actual) !== stringifyBlue(expected)) {
              const segments = path.split("/");
              for (let length = segments.length - 1; length >= 2; length--) {
                try {
                  const target = await this.target(
                    run.root!,
                    segments.slice(0, length).join("/"),
                  );
                  actual = pointer(
                    await this.checked(
                      this.documents.getDocumentContent({ sessionId: target }),
                    ),
                    "/" + segments.slice(length).join("/"),
                  );
                  break;
                } catch (error) {
                  if (!(error instanceof MissingTarget)) throw error;
                }
              }
            }
            if (stringifyBlue(actual) !== stringifyBlue(expected)) return false;
          }
          return true;
        },
        Boolean,
        this.waitOptions,
      );
    }
  }
  async runStep(
    run: LessonRun,
    lesson: Lesson,
    onTarget: (id: string) => void,
    prepared?: Awaited<ReturnType<LessonClient["prepare"]>>,
  ) {
    const step = lesson.steps[run.step];
    if (!run.root || !step) throw new Error("No next operation.");
    // Include connected-document lookup in the observed action latency.
    if (run.pending) run.pending.interrupted = true;
    const intent = this.intent(run);
    let sessionId = intent.sessionId;
    if (!sessionId)
      sessionId =
        prepared?.sessionId || (await this.target(run.root, step.target));
    onTarget(sessionId);
    intent.sessionId = sessionId;
    saveRun(run);
    if (step.clock) {
      this.update("Waiting for real signed timer events…");
      await waitFor(
        (signal) =>
          this.checked(
            this.documents.getDocumentContent({ sessionId }, { signal }),
          ),
        (value) =>
          ["first", "latest", "schedule"].every((event) =>
            value.log?.includes(event),
          ),
        this.waitOptions,
      );
      run.step++;
      return this.finish(
        run,
        "Signed timer events",
        step.target || "/",
        "OBSERVED",
        sessionId,
      );
    }
    if (step.accessCheck === "unavailable") {
      await this.prepare(run, lesson, sessionId);
      this.update(
        "No authorized refund action is available to the hotel. Checking that the restaurant payment is unchanged…",
      );
      await this.verifyState(run, step);
      run.step++;
      return this.finish(
        run,
        step.operation!,
        step.target || "/",
        "EXPECTED_UNAVAILABLE",
        sessionId,
      );
    }
    // Once submitted, retries retain the original document version, route and key.
    // The operation may no longer be offered after it succeeded: do not rediscover it.
    if (!intent.payload) {
      const { document, route, step: preparedStep } =
        prepared || (await this.prepare(run, lesson, sessionId));
      intent.payload = {
        inputMode: "source",
        timeline: { blueId: route!.timeline },
        actor: route!.actor,
        message: {
          type: "Contracts/Operation Request",
          document: document.lastStable!.documentRef,
          channel: step.channel,
          operation: step.operation,
          requireExactDocumentVersion: step.requireExactDocumentVersion ?? true,
          request: preparedStep.request || {},
        },
      };
      saveRun(run);
    }
    this.update(`Submitting ${step.operation} as ${step.channel}…`);
    const result = await this.admitted(run, () =>
      this.documents.invokeOperation({
        sessionId,
        idempotencyKey: intent.key,
        invokeOperation: intent.payload,
      }),
    );
    const entryId = result.entryRef.blueId;
    intent.entryId = entryId;
    saveRun(run);
    this.update("Input accepted. Waiting for the document outcome…");
    try {
      await waitFor(
        (signal) =>
          this.checked(
            this.documents.getDocumentInputsEntryblueid(
              { sessionId, entryBlueId: entryId },
              { signal },
            ),
          ),
        (value) =>
          observedOutcome(
            value,
            step.status === "FAILED",
            step.expectedRejectionReason,
          ),
        this.waitOptions,
      );
    } catch (error) {
      if ((error as any)?.reason === "STALE_DOCUMENT_PRECONDITION") {
        const result = this.finish(
          run,
          step.operation!,
          step.target || "/",
          "STALE_VERSION_REJECTED",
          sessionId,
          entryId,
        );
        run.results.pop();
        (run.attempts ||= []).push(result);
        saveRun(run);
        throw new Error(
          "The document changed before the request was processed. No action was applied. Click Go to review and submit against its current version.",
        );
      }
      throw error;
    }
    await this.verifyState(run, step);
    await this.synchronize(run, step, entryId);
    run.step++;
    return this.finish(
      run,
      step.operation!,
      step.target || "/",
      step.expectedRejectionReason
        ? "EXPECTED_CHANNEL_REJECTION"
        : step.status === "FAILED"
          ? "EXPECTED_REJECTION"
          : "APPLIED",
      sessionId,
      entryId,
    );
  }
}
