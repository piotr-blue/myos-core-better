import { impersonation } from "./impersonation-state";
import { useCallback, useEffect, useRef, useState } from "react";
import type { AccountIdentity, UserSession } from "@blue-labs/myos-sdk";
import { discoverAccounts } from "./account-api";
import {
  chooseAccount,
  rememberAccount,
  selectedAccount,
  setAccountContext,
} from "./account-context";

export function useAccounts() {
  const [user, setUser] = useState<UserSession>();
  const [account, setAccount] = useState<AccountIdentity>();
  const [switching, setSwitching] = useState(false);
  const selecting = useRef(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const pending = useRef<AbortController>(undefined);
  const initialized = useRef(false);
  const incomingDocument = useRef(new URLSearchParams(location.search).has("accountId"));
  const load = useCallback(async () => {
    if (selecting.current) return;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    if (!initialized.current) setLoading(true);
    setError("");
    try {
      const { user: current, account: active } = await discoverAccounts(controller.signal);
      if (controller.signal.aborted) return;
      incomingDocument.current = false;
      setUser(current);
      setAccount(active);
      setAccountContext(active, current);
    } catch (failure: any) {
      if (controller.signal.aborted || failure.name === "AbortError") return;
      setAccount(undefined);
      setAccountContext();
      if (failure.status === 401) setUser(undefined);
      else if (failure.code === "document_account_unavailable") setError(failure.message);
      else if (failure.code === "session_changed" || ([403, 409].includes(failure.status) && selectedAccount()))
        chooseAccount();
      else
        setError(
          failure.message ||
            "The account service is unavailable. Retry to continue.",
        );
    } finally {
      if (!controller.signal.aborted) {
        initialized.current = true;
        setLoading(false);
      }
    }
  }, []);
  const selectAccount = async (accountId: string, userId: string, generation: number, signal?: AbortSignal) => {
    signal?.throwIfAborted();
    if (selecting.current || user?.userId !== userId || !user.accounts.some(item =>
      item.accountId === accountId && item.membership.status === "ACTIVE" && item.membership.generation === generation))
      throw new Error("Choose a current account membership.");
    selecting.current = true;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    const selectionSignal = signal ? AbortSignal.any([controller.signal, signal]) : controller.signal;
    rememberAccount(accountId, userId, generation);
    setAccountContext();
    setAccount(undefined);
    setSwitching(true);
    setError("");
    try {
      const next = await discoverAccounts(selectionSignal);
      selectionSignal.throwIfAborted();
      if (next.user.userId !== userId || next.account?.accountId !== accountId ||
          next.account.membership.generation !== generation)
        throw new Error("The participant membership changed. Choose the workspace again.");
      setAccountContext(next.account, next.user);
      setUser(next.user);
      setAccount(next.account);
    } catch (failure: any) {
      if (signal?.aborted && !controller.signal.aborted && account && user) {
        rememberAccount(account.accountId, account.userId, account.membership.generation);
        setAccountContext(account, user);
        setAccount(account);
      } else if (!selectionSignal.aborted) setError(failure.message || "Unable to switch workspace.");
      throw failure;
    } finally {
      selecting.current = false;
      setSwitching(false);
    }
  };
  useEffect(() => {
    void load();
    const recheck = () => {
      void load();
    };
    const changed = () => {
      // Incoming document selection has not installed a context yet. Its discovery
      // reports denied/rejoined membership in place instead of reloading forever.
      if (incomingDocument.current) return;
      chooseAccount();
    };
    const ended = () => {
      pending.current?.abort();
      setAccountContext();
      setAccount(undefined);
      setUser(undefined);
      setLoading(false);
      setError("");
    };
    const channel = new BroadcastChannel("myos-session");
    channel.onmessage = (event) => {
      if (event.data === "signed-out" && !impersonation()) ended();
    };
    addEventListener("focus", recheck);
    addEventListener("myos-account-selected", recheck);
    addEventListener("myos-session-changed", changed);
    addEventListener("myos-session-ended", ended);
    const timer = setInterval(recheck, 60000);
    return () => {
      pending.current?.abort();
      channel.close();
      clearInterval(timer);
      removeEventListener("focus", recheck);
      removeEventListener("myos-account-selected", recheck);
      removeEventListener("myos-session-changed", changed);
      removeEventListener("myos-session-ended", ended);
    };
  }, [load]);
  return { user, account, loading, switching, error, reload: load, selectAccount };
}
