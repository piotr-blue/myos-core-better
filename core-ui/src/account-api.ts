import { AccountApi, APIKeysApi, Configuration, ResponseError, FetchError } from "@blue-labs/myos-sdk";
import type { AccountIdentity, UserSession } from "@blue-labs/myos-sdk";
import { portalLocation } from "./portal-location";
import { authenticatedFetch } from "./transport";
import { consumeDocumentAccount, documentLinkAccount } from "./document-link";
import {
  captureContext,
  accountEpoch,
  accountContext,
  contextHeaders,
  contextIdentity,
  contextScope,
  selectedAccount,
  accountChoiceRequired,
  rememberAccount,
  userContext,
  type RequestContext,
} from "./account-context";

/** Generated SDK owns the new account API; the adapter only supplies the captured browser context. */
export function coreConfiguration(
  context: Partial<RequestContext> | undefined = captureContext(),
  refreshContext = context?.sessionContext ? context as RequestContext : captureContext(),
  signal?: AbortSignal,
) {
  return new Configuration({
    basePath: portalLocation.api,
    credentials: "include",
    headers: {
      "X-MyOS-Client": "console",
      ...contextHeaders(context as RequestContext),
    },
    fetchApi: (url, init) => {
      const target = new URL(
        url instanceof Request ? url.url : url,
        location.origin,
      );
      const path = target.pathname + target.search;
      return authenticatedFetch(path, signal ? { ...init, signal: AbortSignal.any([signal, ...(init?.signal ? [init.signal] : [])]) } : init, context as RequestContext, false, refreshContext);
    },
  });
}
export function accountApi(
  context: Partial<RequestContext> | undefined = captureContext(),
  refreshContext = context?.sessionContext ? context as RequestContext : captureContext(),
) {
  return new AccountApi(coreConfiguration(context, refreshContext));
}
export async function accountResult<T>(request: Promise<T>): Promise<T> {
  try {
    return await request;
  } catch (error) {
    if (error instanceof FetchError && error.cause?.name === "AbortError") throw error.cause;
    if (!(error instanceof ResponseError)) throw error;
    const problem = await error.response.json().catch(() => ({}));
    throw Object.assign(
      new Error(problem.detail || `Request failed (${error.response.status})`),
      { status: error.response.status, code: problem.title || problem.code },
    );
  }
}
export async function listOwnApiKeys() {
  return (await accountResult(new APIKeysApi(coreConfiguration()).listApiKeys())).items;
}
export function checkedUser(value: UserSession) {
  if (
    value.accountModel !== "MEMBERSHIPS_V1" ||
    !value.userId ||
    !value.sessionContext ||
    !Array.isArray(value.accounts) ||
    !value.preferences || !Number.isSafeInteger(value.preferences.revision) ||
    value.preferences.revision < 1 ||
    value.sessionExpiresAt <= Date.now()
  )
    throw new Error("Core returned an unsupported User session.");
  return value;
}
export function checkedAccount(
  value: AccountIdentity,
  user: UserSession,
  selected: string,
) {
  const selectedMembership = user.accounts.find(
    (account) => account.accountId === selected,
  )?.membership;
  if (
    value.userId !== user.userId ||
    value.membership?.generation !== selectedMembership?.generation
  )
    throw Object.assign(
      new Error("Your account session changed. Choose the current membership."),
      { status: 409 },
    );
  if (
    value.accountModel !== "MEMBERSHIPS_V1" ||
    value.userId !== user.userId ||
    value.accountId !== selected ||
    !value.sessionContext ||
    value.membership?.status !== "ACTIVE" ||
    !Number.isSafeInteger(value.membership.generation) ||
    value.membership.generation < 1 ||
    !Number.isSafeInteger(value.membership.revision) ||
    value.membership.revision < 1 ||
    !["PERSONAL", "BUSINESS"].includes(value.kind) ||
    !value.profile?.displayName
  )
    throw new Error("Core returned an unsupported selected account.");
  return value;
}
/** Discover without installing a new guard into an existing tab's request context. */
export async function discoverAccounts(signal?: AbortSignal) {
  const prior = captureContext();
  const linkedAccount = prior ? undefined : documentLinkAccount();
  const epoch = accountEpoch();
  const changed = () => Object.assign(
    new Error("Your account session changed. Choose the current membership."),
    { status: 409, code: "session_changed" },
  );
  const user = checkedUser(await accountResult(accountApi({}, prior).getMe({}, { signal })));
  if (epoch !== accountEpoch()) throw new DOMException("Workspace changed", "AbortError");
  if (contextIdentity(captureContext()) !== contextIdentity(prior) ||
      (prior && prior.userId !== user.userId)) throw changed();
  if (linkedAccount && !user.accounts.some(account =>
    account.accountId === linkedAccount && account.membership.status === "ACTIVE"))
    throw Object.assign(new Error("You do not have active access to the workspace in this document link."),
      { status: 403, code: "document_account_unavailable" });
  const existing = linkedAccount ? undefined : selectedAccount(user);
  const preferred = !prior && !existing && !accountChoiceRequired(user) && location.hash !== "#accounts"
    ? user.accounts.find(item => item.accountId === user.preferences.defaultAccountId && item.membership.status === "ACTIVE")
    : undefined;
  const selected = linkedAccount ?? existing ?? preferred?.accountId;
  const selectionEpoch = accountEpoch();
  if (prior && prior.accountId !== selected) throw changed();
  // Initial selection needs a base User guard for refresh, but /account discovery
  // cannot send that base guard as if it were an already-selected account guard.
  let account: AccountIdentity | undefined;
  try {
    account = selected ? checkedAccount(await accountResult(
      accountApi(prior ?? { accountId: selected }, prior ?? userContext(user))
        .getAccount({}, { signal }),
    ), user, selected) : undefined;
  } catch (failure: any) {
    if (linkedAccount && [403, 409].includes(failure.status))
      throw Object.assign(new Error("Access to the workspace in this document link could not be verified. Reopen the link after checking your access."),
        { status: failure.status, code: "document_account_unavailable" });
    throw failure;
  }
  signal?.throwIfAborted();
  if (selectionEpoch !== accountEpoch()) throw new DOMException("Workspace changed", "AbortError");
  const next = account ? accountContext(account) : userContext(user);
  if (contextIdentity(captureContext()) !== contextIdentity(prior) ||
      (prior && (contextScope(prior) !== contextScope(next) || prior.sessionContext !== next.sessionContext)))
    throw changed();
  if ((linkedAccount || preferred) && account) {
    if (!linkedAccount && accountChoiceRequired() && location.hash !== "#invitation") location.hash = "documents";
    rememberAccount(account.accountId, account.userId, account.membership.generation);
    if (linkedAccount) consumeDocumentAccount(linkedAccount);
  }
  return { user, account };
}
/** Same exact intent keeps its UUID through an unknown response or reload; success ends that intent. */
export async function accountCommand<T>(
  context: RequestContext,
  action: string,
  input: unknown,
  invoke: (api: AccountApi, key: string) => Promise<T>,
) {
  const epoch = accountEpoch();
  const bytes = new TextEncoder().encode(
    JSON.stringify([contextScope(context), action, input]),
  );
  const digest = Array.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
  )
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
  if (epoch !== accountEpoch()) throw new DOMException("Workspace changed", "AbortError");
  const slot = "myos-account-intent-" + digest;
  const key = sessionStorage.getItem(slot) || crypto.randomUUID();
  sessionStorage.setItem(slot, key);
  const result = await accountResult(invoke(accountApi(context), key));
  if (epoch !== accountEpoch()) throw new DOMException("Workspace changed", "AbortError");
  sessionStorage.removeItem(slot);
  return result;
}
