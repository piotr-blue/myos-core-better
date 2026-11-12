import type { AccountIdentity, UserSession } from "@blue-labs/myos-sdk";

export type RequestContext = Readonly<{
  userId: string;
  sessionContext: string;
  accountId?: string;
  generation?: number;
  revision?: number;
}>;
let current: RequestContext | undefined;
let epoch = 0;
let requests = new AbortController();
export const accountSignal = () => requests.signal;
function advanceAccountEpoch() {
  epoch++;
  requests.abort(new DOMException("Workspace changed", "AbortError"));
  requests = new AbortController();
}
export const accountEpoch = () => epoch;
const choiceRequiredKey = "myos-core-account-choice-required";
export function accountChoiceRequired(user?: UserSession) {
  const raw = sessionStorage.getItem(choiceRequiredKey);
  if (!raw) return false;
  try {
    const previous = JSON.parse(raw);
    const accounts = user?.accounts.filter(item => item.membership.status === "ACTIVE") ?? [];
    // Fresh discovery may recover a removed membership into the sole default.
    // A changed human, same-account rejoin or unverified selection stays explicit.
    return !(validSelection(previous) && previous.userId === user?.userId &&
      accounts.length === 1 && accounts[0].accountId !== previous.accountId &&
      accounts[0].accountId === user.preferences.defaultAccountId);
  } catch { return true; }
}
function validSelection(value: any) {
  return value && Object.keys(value).length === 3 && typeof value.accountId === "string" && value.accountId.length > 0 &&
    typeof value.userId === "string" && value.userId.length > 0 &&
    Number.isSafeInteger(value.generation) && value.generation > 0;
}
function requireAccountChoice(previous?: unknown) {
  advanceAccountEpoch();
  if (previous !== undefined || !sessionStorage.getItem(choiceRequiredKey))
    sessionStorage.setItem(choiceRequiredKey, validSelection(previous) ? JSON.stringify(previous) : "true");
  sessionStorage.removeItem("myos-core-account");
}
export const captureContext = () => current;
export function userContext(user: UserSession): RequestContext {
  return { userId: user.userId, sessionContext: user.sessionContext };
}
export function setAccountContext(account?: AccountIdentity, user?: UserSession) {
  current = account ? accountContext(account) : user && userContext(user);
}
export function accountContext(account: AccountIdentity): RequestContext {
  return Object.freeze({
    userId: account.userId,
    accountId: account.accountId,
    sessionContext: account.sessionContext,
    generation: account.membership.generation,
    revision: account.membership.revision,
  });
}
export function contextHeaders(
  context?: Pick<RequestContext, "accountId" | "sessionContext">,
) {
  return {
    ...(context?.accountId ? { "X-MyOS-Account": context.accountId } : {}),
    ...(context?.sessionContext
      ? { "X-MyOS-Session-Context": context.sessionContext }
      : {}),
  };
}
export function contextScope(context?: RequestContext) {
  return JSON.stringify([
    context?.userId,
    context?.accountId,
    context?.generation,
  ]);
}
export function contextIdentity(context?: RequestContext) {
  return JSON.stringify([
    contextScope(context),
    context?.revision,
    context?.sessionContext,
  ]);
}
export function assertContext(context?: RequestContext) {
  if (context && contextIdentity(context) !== contextIdentity(current))
    throw Object.assign(
      new Error(
        "Your account session changed. Continue from the current account.",
      ),
      { status: 409 },
    );
}
export function selectedAccount(user?: UserSession) {
  const raw = sessionStorage.getItem("myos-core-account");
  if (!raw) return undefined;
  try {
    const value = JSON.parse(raw);
    if (!validSelection(value)) {
      requireAccountChoice(true);
      return undefined;
    }
    if (
      user &&
      (value.userId !== user.userId ||
        !user.accounts.some(
          (account) =>
            account.accountId === value.accountId &&
            account.membership.status === "ACTIVE" &&
            account.membership.generation === value.generation,
        ))
    ) {
      requireAccountChoice(value);
      return undefined;
    }
    return value.accountId as string;
  } catch {
    requireAccountChoice(true);
    return undefined;
  }
}
export function rememberAccount(accountId: string, userId: string, generation: number) {
  if (!accountId || !userId || !Number.isSafeInteger(generation) || generation < 1)
    throw new Error("Choose a current account membership.");
  advanceAccountEpoch();
  sessionStorage.setItem("myos-core-account", JSON.stringify({ accountId, userId, generation }));
  sessionStorage.removeItem(choiceRequiredKey);
}
export function chooseAccount(
  accountId?: string,
  userId?: string,
  generation?: number,
) {
  if (accountId) {
    if (!userId || !Number.isSafeInteger(generation) || generation < 1)
      throw new Error("Choose a current account membership.");
    rememberAccount(accountId, userId, generation);
    if (location.hash === "#accounts")
      history.replaceState(history.state, "", "#documents");
  } else {
    const selected = selectedAccount();
    requireAccountChoice(selected ? JSON.parse(sessionStorage.getItem("myos-core-account")) : undefined);
  }
  // The departing document must not start new requests while reload is pending.
  requests.abort(new DOMException("Workspace changed", "AbortError"));
  setAccountContext();
  // A fresh document discards every in-flight component closure before a new context is installed.
  location.reload();
}
