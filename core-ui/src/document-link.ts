/** Account-qualified document links carry an identity hint, never an authentication guard. */
export function documentLinkAccount(): string | undefined {
  const query = new URLSearchParams(location.search);
  if (!query.has("accountId")) return undefined;
  const accounts = query.getAll("accountId");
  if (accounts.length !== 1 || !accounts[0].trim() || accounts[0] !== accounts[0].trim()
      || !/^#documents\/[^/?#]+(?:\?[^#]*)?$/.test(location.hash))
    throw new Error("This document link has an invalid workspace or document address.");
  return accounts[0];
}

export function consumeDocumentAccount(accountId: string) {
  const query = new URLSearchParams(location.search);
  if (query.get("accountId") !== accountId) return;
  query.delete("accountId");
  const search = query.toString();
  history.replaceState(history.state, "", location.pathname + (search ? `?${search}` : "") + location.hash);
}
