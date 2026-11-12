import type { RuntimeIdentities } from "@blue-labs/myos-sdk";
import retained from "../../packages/current.json";
import accessEvents from "../../runtime/blue-core-service/src/main/resources/package-catalog/access-events-defaults.json";
import runtimes from "./data/process-runtimes.json";
import better from "./data/process-better.json";

export type ProcessExecution = { runtime: RuntimeIdentities; executionSelection?: RuntimeIdentities };
export type ProcessSelection = ProcessExecution & { packages: string[] };

// RegistrationRuntimes advertises this exact historical publication to new accounts.
const accessEventsCanonical = { ...runtimes.accessEvents,
  canonicalWireMeasurementV1: runtimes.finalGasCanonical.canonicalWireMeasurementV1 };
const reviewedExecutions = [better.runtime, runtimes.accessEventsDefinitionRead, accessEventsCanonical, runtimes.accessEvents, runtimes.finalGasCanonical, runtimes.finalGas];

/** Prefer unchanged shared defaults; otherwise use a reviewed choice advertised by every participant. */
export function selectProcessExecution(
  accounts: { runtime: RuntimeIdentities; registrationExecutionSelections?: RuntimeIdentities[] }[],
  retained?: ProcessExecution,
): ProcessExecution {
  const supports = (runtime: RuntimeIdentities) => reviewedExecutions.some(choice => sameSelection(choice, runtime));
  const available = (selection: ProcessExecution) => accounts.length > 0 && supports(selection.runtime) &&
    accounts.every(info => selection.executionSelection
      ? sameSelection(selection.executionSelection, selection.runtime) &&
        info.registrationExecutionSelections?.some(choice => sameSelection(choice, selection.executionSelection))
      : sameSelection(info.runtime, selection.runtime));
  if (retained) {
    if (available(retained)) return retained;
    throw new Error("This run's saved execution selection is no longer supported by every workspace. Start a new run; existing documents are preserved.");
  }
  // Current clause packages require definition reads. Retained accounts select that
  // advertised document runtime explicitly; their saved account default is unchanged.
  const definition = { runtime: runtimes.accessEventsDefinitionRead, executionSelection: runtimes.accessEventsDefinitionRead };
  if (accounts.some(info => sameSelection(info.runtime, runtimes.accessEvents)) && available(definition)) return definition;
  const implicit = { runtime: accounts[0]?.runtime };
  if (available(implicit)) return implicit;
  for (const runtime of reviewedExecutions) {
    const explicit = { runtime, executionSelection: runtime };
    if (available(explicit)) return explicit;
  }
  throw new Error("These workspaces have no common supported process package edition. Their defaults may use different process package editions. Choose compatible workspaces before starting the document.");
}

/** Compare the complete native selection, including unknown fields and exact package membership. */
export function sameSelection(left: unknown, right: unknown): boolean {
  if (left === right) return true;
  if (!left || !right || typeof left !== "object" || typeof right !== "object") return false;
  if (Array.isArray(left) !== Array.isArray(right)) return false;
  const keys = Object.keys(left);
  return keys.length === Object.keys(right).length && keys.every(key =>
    Object.hasOwn(right, key) && sameSelection(left[key], right[key]));
}

export function processEdition(runtime: RuntimeIdentities): "finalGas" | "accessEvents" | "better" {
  if (sameSelection(runtime, better.runtime)) return "better";
  if (sameSelection(runtime, runtimes.accessEventsDefinitionRead) || sameSelection(runtime, runtimes.accessEvents) || sameSelection(runtime, accessEventsCanonical)) return "accessEvents";
  if (sameSelection(runtime, runtimes.finalGas) || sameSelection(runtime, runtimes.finalGasCanonical)) return "finalGas";
  throw new Error("This workspace's runtime is not supported by these examples. Choose a supported workspace before starting the document.");
}

/** Only reviewed, already published roots are selected; sources and retained editions stay immutable. */
export function processPackages(runtime: RuntimeIdentities, original?: string[]): string[] {
  if (processEdition(runtime) === "better") {
    if (!original) return better.packages.map(row => row.blueId);
    return original.map(id => {
      const prior = retained.packages.find(row => row.blueId === id);
      const selected = better.packages.find(row => row.name === prior?.namespace);
      if (!prior || !selected) throw new Error("This example requires a package not yet deployed in the Better runtime.");
      return selected.blueId;
    });
  }
  if (processEdition(runtime) === "finalGas") return [...(original ?? retained.packages.map(row => row.blueId))];
  if (!original) return accessEvents.packages.map(row => row.blueId);
  return original.map(id => {
    const prior = retained.packages.find(row => row.blueId === id);
    const selected = accessEvents.packages.find(row => row.name === prior?.namespace);
    if (!prior || !selected) throw new Error("This example has no reviewed package selection for this workspace's runtime.");
    return selected.blueId;
  });
}

export function validateProcessSelection(selection: ProcessSelection, original: string[]) {
  if (!selection || (selection.executionSelection && !sameSelection(selection.executionSelection, selection.runtime)) ||
      !sameSelection(selection.packages, processPackages(selection.runtime, original)))
    throw new Error("The saved example package selection does not match its runtime. Start a new run; existing documents are preserved.");
}
