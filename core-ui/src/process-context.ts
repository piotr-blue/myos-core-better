import { accountApi, accountResult } from "./account-api";
import { accountContext, assertContext, captureContext } from "./account-context";
import { selectProcessExecution, type ProcessExecution } from "./process-packages";

/** Resolve every mapped account before submitting a multi-party bootstrap. */
export async function processRuntime(accountIds: string[] = [], signal?: AbortSignal, retained?: ProcessExecution): Promise<ProcessExecution> {
  const context = captureContext();
  if (!context?.accountId) throw new Error("Choose a workspace before starting the document.");
  const accounts = [];
  for (const accountId of new Set([context.accountId, ...accountIds])) {
    let target = context;
    if (accountId !== context.accountId) {
      const account = await accountResult(accountApi({ accountId }, context).getAccount({}, { signal }));
      assertContext(context);
      if (account.accountId !== accountId || account.userId !== context.userId || account.membership.status !== "ACTIVE")
        throw new Error("A mapped workspace is no longer available to this user.");
      target = accountContext(account);
    }
    const info = await accountResult(accountApi(target, context).getInfo({}, { signal }));
    signal?.throwIfAborted();
    assertContext(context);
    if (info.accountId !== accountId) throw new Error("Core returned a different workspace's runtime.");
    accounts.push(info);
  }
  return selectProcessExecution(accounts, retained);
}
