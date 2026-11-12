import type { UserSession } from "@blue-labs/myos-sdk";
import { prepareExampleDocument } from "../example-document";
import { definitionOf, type Lesson, type LessonRun } from "./model";

export const mainRoles = (lesson: Lesson) =>
  lesson.roles.filter((r) => r.kind !== "representative");
export const runnable = (lesson: Lesson) =>
  !lesson.roles.some((r) => r.kind === "representative") ||
  lesson.id === "08-participants-agents";

export function mappingIssue(lesson: Lesson, mapping: Record<string, string>) {
  for (const [left, right] of lesson.distinctRoles || []) {
    if (mapping[left] && mapping[left] === mapping[right]) {
      const label = (name: string) =>
        lesson.roles.find((r) => r.name === name)?.label || name;
      return `${label(left)} and ${label(right)} need different accounts for this account-to-account payment. Choose another account or create one below.`;
    }
  }
  return "";
}

export function suggestAccounts(
  lesson: Lesson,
  user: UserSession,
  current?: string,
) {
  const accounts = user.accounts.filter(
    (a) => a.membership.status === "ACTIVE",
  );
  const ordered = [...accounts].sort(
    (a, b) => Number(b.accountId === current) - Number(a.accountId === current),
  );
  return Object.fromEntries(
    mainRoles(lesson).map((role, i) => [
      role.name,
      ordered[i]?.accountId || "",
    ]),
  );
}

export function createRun(
  lesson: Lesson,
  user: UserSession,
  mapping: Record<string, string>,
): LessonRun {
  if (!runnable(lesson))
    throw new Error(
      "This representative composition is available as a source preview only.",
    );
  const managed = new Set(
    user.accounts
      .filter((a) => a.membership.status === "ACTIVE")
      .map((a) => a.accountId),
  );
  const identities = { ...mapping },
    representatives: Record<string, string> = {};
  for (const role of mainRoles(lesson)) {
    if (!managed.has(identities[role.name]))
      throw new Error(`Choose an available account for ${role.label}.`);
  }
  const issue = mappingIssue(lesson, identities);
  if (issue) throw new Error(issue);
  for (const role of lesson.roles.filter((r) => r.kind === "representative")) {
    identities[role.name] = identities[role.name.split("__")[0]];
    representatives[role.name] = user.userId;
  }
  return {
    version: 1,
    id: crypto.randomUUID(),
    userId: user.userId,
    exampleId: lesson.id,
    definition: definitionOf(lesson),
    identities,
    representatives,
    source: prepareExampleDocument(
      lesson.source,
      lesson.roles,
      identities,
      identities[mainRoles(lesson)[0].name],
      representatives,
    ),
    step: 0,
    results: [],
  };
}

export function actingAccount(run: LessonRun, lesson: Lesson) {
  if (run.pending?.accountId) return run.pending.accountId;
  const role = !run.root
    ? mainRoles(lesson)[0].name
    : lesson.steps[run.step]?.source ||
      lesson.steps[run.step]?.channel ||
      mainRoles(lesson)[0].name;
  return run.identities[role] || run.identities[role.split("__")[0]];
}
