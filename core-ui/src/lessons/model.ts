import records from "../data/clause-examples.json";
import currentRecords from "../data/clause-examples-access-events.json";
import type { RuntimeIdentities } from "@blue-labs/myos-sdk";
import { parseBlue, stringifyBlue } from "../blue-values";
import { processEdition, sameSelection, type ProcessSelection } from "../process-packages";

export type LessonStep = {
  operation?: string;
  channel?: string;
  source?: string;
  target?: string;
  request?: unknown;
  requestDocument?: "root";
  status?: string;
  clock?: string;
  expect?: Record<string, unknown>;
  requireExactDocumentVersion?: boolean;
  accessCheck?: "unavailable";
  expectedRejectionReason?: "INVALID_TARGET_CHANNEL";
};
export type Lesson = Omit<(typeof records)[number], "steps"> & {
  steps: LessonStep[];
};
// The native bundle case submits a raw unmatched hotel input to the restaurant
// payment. In the console, demonstrate that boundary through its actual operation
// availability instead. This is an explicit read-only check, not a claimed rejection.
const adaptLessons = (catalogue: Lesson[]): Lesson[] => catalogue.map((lesson) => ({
  ...lesson,
  steps: lesson.steps.map((step) =>
    lesson.id === "04-bundle-order" &&
    step.operation === "refund" &&
    step.channel === "hotel" &&
    step.target === "/orders/dinner/payment"
      ? { ...step, accessCheck: "unavailable" as const }
      : lesson.id === "08-participants-agents" &&
          step.operation === "leadGreeting" &&
          step.source === "buyer__assistant" &&
          step.channel === "buyer" &&
          step.status === "FAILED"
        ? {
            ...step,
            expectedRejectionReason: "INVALID_TARGET_CHANNEL" as const,
          }
        : step,
  ),
}));
export const retainedLessons = adaptLessons(records as Lesson[]);
export const lessons = adaptLessons(currentRecords as Lesson[]);
export type Measurement = {
  action: string;
  target: string;
  status: string;
  startedAt: string;
  submissionMs: number;
  admissionMs: number;
  processingMs: number;
  synchronizationMs?: number;
  totalMs: number;
  entryId?: string;
  sessionId: string;
  interrupted?: boolean;
};
export type Intent = {
  kind?: "bootstrap" | "operation";
  interrupted?: boolean;
  accountId?: string;
  key: string;
  startedAt: string;
  sessionId?: string;
  payload?: any;
  admission?: any;
  entryId?: string;
  submittedAt?: number;
  admittedAt?: number;
  synchronizationMs?: number;
};
export type LessonRun = {
  version: 1;
  id: string;
  userId: string;
  exampleId: string;
  definition: string;
  identities: Record<string, string>;
  representatives: Record<string, string>;
  source: string;
  selection?: ProcessSelection;
  root?: string;
  step: number;
  stopped?: boolean;
  pending?: Intent;
  results: Measurement[];
  attempts?: Measurement[];
};

export const definitionOf = (lesson: Lesson) =>
  stringifyBlue([lesson.source, lesson.packages, lesson.steps]);
export function lessonForRuntime(id: string, runtime: RuntimeIdentities): Lesson {
  const lesson = (processEdition(runtime) === "accessEvents" ? lessons : retainedLessons).find(row => row.id === id);
  if (!lesson) throw new Error("This example has no reviewed definition for the selected runtime.");
  return lesson;
}
export function lessonForRun(run: LessonRun): Lesson {
  const lesson = [...retainedLessons, ...lessons].find(row => row.id === run.exampleId && definitionOf(row) === run.definition);
  if (!lesson) throw new Error("This saved guide does not match a reviewed example edition. Start a new run; existing documents are preserved.");
  return lesson;
}
const key = (userId: string, id: string) => `myos-lesson:${userId}:${id}`;
export function saveRun(run: LessonRun) {
  sessionStorage.setItem(key(run.userId, run.id), stringifyBlue(run));
}
export function loadRun(
  userId: string,
  id: string,
  lesson: Lesson,
): LessonRun | undefined {
  const raw = sessionStorage.getItem(key(userId, id));
  if (!raw) return;
  const run = parseBlue(raw);
  lessonForRun({ ...run, exampleId: lesson.id, definition: definitionOf(lesson) });
  const selected = lessonForRun(run);
  if (
    run.version !== 1 ||
    run.id !== id ||
    run.userId !== userId ||
    run.exampleId !== lesson.id ||
    !Number.isInteger(run.step) ||
    run.step < 0 ||
    run.step > selected.steps.length ||
    !Array.isArray(run.results)
  )
    throw new Error(
      "This saved guide does not match the current example. Start a new run; the existing documents are preserved.",
    );
  if (run.selection && (definitionOf(lessonForRuntime(run.exampleId, run.selection.runtime)) !== run.definition ||
      !sameSelection(run.selection.packages, selected.packages)))
    throw new Error("The saved example definition does not match its runtime and package selection.");
  return run;
}
export function resumesBootstrap(run: LessonRun) {
  return !run.root || run.pending?.kind === "bootstrap";
}
export function pointer(value: any, path: string): any {
  if (path === "/" || path === "") return value;
  if (!path.startsWith("/")) throw new Error("Expected a document pointer.");
  return path
    .slice(1)
    .split("/")
    .reduce((v, k) => v?.[k.replace(/~1/g, "/").replace(/~0/g, "~")], value);
}
interface InputDecision {
  outcome?: string;
  reason?: string;
  code?: string;
}

interface ObservedInput {
  assessment?: InputDecision | null;
  disposition?: InputDecision | null;
  localHistory?: ReadonlyArray<{ stable?: boolean }>;
}

const rejectedOutcomes = new Set([
  "FAILED", "EXECUTION_REJECTED", "REJECTED", "NO_MATCH", "IGNORED",
  "INAPPLICABLE", "GAS_LIMIT_EXCEEDED", "TERMINATED",
]);

export function observedOutcome(
  input: ObservedInput,
  expectedFailure: boolean,
  expectedRejectionReason?: LessonStep["expectedRejectionReason"],
): boolean {
  // Admission and execution are separate stages. A terminal document disposition takes
  // precedence over a prior ACCEPTED assessment for the same signed entry.
  const decision = input.disposition?.outcome ? input.disposition : input.assessment;
  const outcome = decision?.outcome;
  const reason = decision?.code || decision?.reason;
  const stable = input.localHistory?.some(position => position.stable) === true;
  const rejected = outcome !== undefined && rejectedOutcomes.has(outcome);
  if (rejected && reason === "STALE_DOCUMENT_PRECONDITION")
    throw Object.assign(new Error("The displayed document version changed."), { outcome, reason });

  if (expectedRejectionReason) {
    if (stable)
      throw new Error("The forbidden representative action unexpectedly produced a committed transition.");
    if ((outcome === "REJECTED" || outcome === "FAILED") && reason === expectedRejectionReason)
      return true;
    if (rejected || outcome === "APPLIED")
      throw new Error(`Expected the representative channel restriction ${expectedRejectionReason}, received ${outcome}${reason ? ` (${reason})` : ""}.`);
    return false;
  }
  if (expectedFailure) {
    if (!stable && (outcome === "EXECUTION_REJECTED" ||
        (outcome === "FAILED" && reason === "BEX_EXECUTION_REJECTED"))) return true;
    if (rejected || stable || outcome === "APPLIED")
      throw new Error(`Expected a business-rule rejection, received ${outcome || "a committed transition"}${reason ? ` (${reason})` : ""}.`);
    return false;
  }
  if (rejected)
    throw Object.assign(new Error(`The operation was not applied: ${outcome}. ${reason || ""}`), { outcome, reason });
  return stable;
}
export function stepCopy(step: LessonStep) {
  const operation = step.operation || "Wait for signed time";
  const human = operation
    .replace(/_/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2");
  const explanations: Record<string, string> = {
    startPayment:
      "Prepare and attach the payment instruction using the agreement’s participants and terms. Then inspect the connected payment document.",
    acceptPaymentInstruction:
      "The guarantor accepts responsibility for this payment instruction. This does not yet transfer funds.",
    authorize:
      "Ask the payment provider to reserve the stated amount. Authorization and capture are separate decisions.",
    secureFunds:
      "Record the provider’s confirmation that funds are secured. Capture still follows the document’s conditions.",
    confirmSatisfaction:
      "The customer confirms satisfaction directly in the payment process. Its rules decide whether capture can be requested.",
    completePayment:
      "Record the guarantor’s confirmation of completed payment and check that the agreement observes it.",
    refund:
      "Request a refund through the seller’s allowed operation. The provider must still confirm the reversal.",
    reverseAfterCompletion:
      "Record the provider’s confirmed reversal and verify the updated payment balance.",
    startShipping:
      "Prepare a shipment document from the agreement. The seller and carrier then coordinate fulfillment in that connected document.",
    bookShipment:
      "Ask the carrier to book this shipment. A booking request is separate from the carrier’s confirmation.",
    confirmShipment:
      "The carrier confirms the booking and supplies its tracking reference.",
    recordDispatched:
      "The carrier records that the shipment has been dispatched. This enables the later delivery confirmation.",
    confirmDelivery:
      "The carrier confirms delivery. The shipment checks the required earlier milestones before updating its state.",
    changeAddress:
      "Request a new delivery address. The carrier must confirm the change before it takes effect.",
    confirmAddressChange:
      "The carrier accepts the pending address change and updates the shipment.",
    rejectAddressChange:
      "The carrier declines the pending address change; the existing address stays in effect.",
    newChild:
      "Create a connected document from the declared template. Its state remains separate and is visible through the document’s relationships.",
    newOrder:
      "Create an individual customer order under the reseller agreement. The order keeps its own payment and fulfillment state.",
    greet:
      "Call the greeting operation on the selected document, then inspect the resulting state and events.",
    reportFailure:
      "Record an order failure. The example’s listener reacts to that event and updates the greeting.",
    setStatus:
      "Change the document’s status and observe the listener that reacts to that property change.",
    quote:
      "Provide the quantity and unit price. The Product clause validates them and calculates the quoted amount.",
    setTarget:
      "Set the amount owed for this ledger item. Reusing its key corrects that item instead of adding another charge.",
    reserve:
      "Reserve part of the available ledger balance for a payout. The clause rejects a reservation that exceeds what is available.",
    settle:
      "Record the settlement outcome and update the corresponding balance.",
    requestTermination:
      "Request termination through the document’s rules. This begins the closing process rather than silently deleting the document.",
    finishTermination:
      "Complete the permitted termination process and record the final state.",
    completeWork:
      "Record completion of the work while the document is still allowed to accept that action.",
    proposeBooking:
      "Propose a booking change. The existing agreement stays in effect until the required participants approve the proposal.",
    guestApprove: "The guest approves the proposed booking revision.",
    hostApprove:
      "The host approves the proposed booking revision. Once all required approvals exist, the amendment can take effect.",
    agentGreeting:
      "Act through the assistant’s representative route and perform one of its explicitly allowed operations.",
    leadGreeting:
      "Act through the main party’s route. The assistant does not automatically inherit this authority.",
    advanceRepair:
      "Move the repair to the proposed status. The Status clause permits only the transitions defined for the current state.",
    acceptDesign:
      "Accept the design milestone. Only the payment connected to that milestone can react to this acceptance.",
    acceptDelivery:
      "Accept the delivery milestone independently of the design milestone.",
    startDesignpayment:
      "Create the payment for the design milestone, with its own acceptance condition and state.",
    startDeliverypayment:
      "Create a separate payment for the delivery milestone.",
    startPayout:
      "Create the partner’s payout from the reseller ledger, separately from the customer’s payment.",
    authorizePayout:
      "Request authorization for the partner payout. The provider must still confirm that funds are secured.",
    capturePayout:
      "Request completion of the authorized payout, subject to its payment rules.",
    bookHotel:
      "Create the hotel’s order within the bundle. It keeps its own terms and payment state.",
    bookRestaurant: "Create a separate restaurant order within the bundle.",
    recordTrialAttendance:
      "Record attendance at the free trial. Attendance alone does not enroll the customer in paid membership.",
    joinMembership:
      "The customer explicitly chooses paid membership after the trial.",
    renew:
      "Create the next membership period and its separate payment obligation.",
    publishHotel: "Publish the hotel’s settlement statement for review.",
    publishRestaurant:
      "Publish the restaurant’s separate settlement statement for review.",
    reviseStatement:
      "Correct a settlement statement before calculating and approving the net amount.",
    proposeNet: "Propose a net settlement using the participating statements.",
    customerApproveNet: "The customer approves the proposed net settlement.",
    sellerApproveNet:
      "The seller approves the same net settlement before its payment proceeds.",
    recordTransactionInitiated:
      "The provider links its transaction reference to this instruction. A transaction reference alone does not confirm that money has moved.",
    provideSession:
      "The provider supplies the test payment-session reference for the customer’s funding flow.",
    confirmFunding:
      "The provider confirms funding for that session; the remaining payment conditions still apply.",
    reportDelivery:
      "Record delivery in the payment process. The customer’s acceptance condition remains a separate check.",
    remind:
      "Request the example’s reminder. This source uses a two-second countdown, so it may fire before you reach the next screen. The document log records what happened.",
    cancel:
      "Cancel the reminder if it is still pending. A timer that already fired cannot be undone; inspect the document log to see the difference.",
    report:
      "Inspect and record the outcome after the expected signed timer events.",
    sayHello:
      "Submit the prepared message. The document validates the request and records the permitted change.",
  };
  const rejected: Record<string, string> = {
    sayHello:
      "Try a greeting without the required approval. The request must be rejected rather than changing the stored message.",
    quote:
      "Try a quote with an invalid quantity. The Product clause must reject it instead of accepting an invalid price calculation.",
    reserve:
      "Try reserving more than the available balance. The Ledger clause must reject the excess reservation.",
    confirmSatisfaction:
      "Submit a response that does not confirm satisfaction. The payment must not treat it as permission to capture funds.",
    confirmDelivery:
      "Try confirming delivery before dispatch is recorded. The Shipment clause must enforce the missing milestone.",
    rejectAddressChange:
      "Try rejecting an address-change request that was already confirmed. The resolved request cannot be changed a second time.",
    leadGreeting:
      "Try a main-party operation through the assistant’s route. Core must reject the mismatched channel before executing it; the assistant cannot use the main party’s authority.",
    advanceRepair:
      "Try a transition that is not allowed from the current repair status. The branch rules must reject it.",
    updateParty_customer:
      "Try replacing a party in this frozen instruction. Its approved participant identities must remain protected.",
  };
  return {
    title: step.accessCheck
      ? "Check the hotel’s refund boundary"
      : human.charAt(0).toUpperCase() + human.slice(1),
    actor: step.channel || "Time provider",
    description:
      step.clock ||
      (step.accessCheck
        ? "Open the restaurant payment as the hotel. Verify that no hotel refund action is available and the restaurant’s refunded amount remains zero. This step does not submit a request."
        : step.status === "FAILED"
          ? rejected[operation] ||
            "Try the supplied invalid request. The guide continues only when Core confirms a business-rule rejection."
          : operation.startsWith("approve_")
            ? "This participant approves the current terms. Activation waits for all required participants before enabling the business operations."
            : explanations[operation] ||
              (operation.startsWith("request")
                ? "Submit the prepared request for " +
                  human.replace(/^request /, "").toLowerCase() +
                  ". The requested change still needs its defined confirmation."
                : operation.endsWith("Unknown")
                  ? "Record that the provider outcome is still unknown. The document preserves that uncertainty instead of treating it as success or failure."
                  : /^(decline|fail|reject)/.test(operation)
                    ? "Record the provider’s refusal or failure for the pending request, preserving the previously confirmed financial state."
                    : /^(lock|unlock)/.test(operation)
                      ? "Change whether this payment action is allowed. A permission change does not itself move funds."
                      : /^(confirm|resolve)/.test(operation)
                        ? "Confirm the prepared change through the authorized provider’s operation, then inspect the updated payment state."
                        : "") ||
              `Open ${step.target || "the main document"}, fill ${operation} and submit it. Continue only after its result is recorded.`),
  };
}
