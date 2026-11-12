import { AccountApi, type AccountIdentity, type MandateSharingSettings } from "@blue-labs/myos-sdk";
import { useEffect, useRef, useState } from "react";
import { accountResult, coreConfiguration } from "./account-api";
import { accountContext } from "./account-context";

/** One account-wide disclosure preference; native mandate approvals remain independent. */
export function MandateSharing({ account }: { account: AccountIdentity }) {
  const [setting, setSetting] = useState<MandateSharingSettings>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const attempt = useRef<{ key: string; enabled: boolean }>(undefined);
  useEffect(() => {
    let active = true;
    setSetting(undefined); setError(""); attempt.current = undefined;
    accountResult(new AccountApi(coreConfiguration(accountContext(account))).getMandateSharing())
      .then(value => { if (active) setSetting(value); })
      .catch(error => { if (active) setError(error.message); });
    return () => { active = false; };
  }, [account]);
  const update = async () => {
    if (!setting) return;
    const request = attempt.current ??= { key: crypto.randomUUID(), enabled: !setting.enabled };
    setBusy(true); setError("");
    try {
      setSetting(await accountResult(new AccountApi(coreConfiguration(accountContext(account))).updateMandateSharing({
        idempotencyKey: request.key, updateMandateSharingRequest: { enabled: request.enabled },
      })));
      attempt.current = undefined;
    } catch (error: any) { setError(error.message); }
    finally { setBusy(false); }
  };
  return <section className="panel-pad" aria-label="Mandate verification">
    <h3>Mandate verification</h3>
    <label className="checkbox-label">
      <input type="checkbox" checked={setting?.enabled ?? false} disabled={busy || !setting} onChange={update} />
      Allow counterparties to verify this account’s agent mandates
    </label>
    <p className="muted">Set this once for the account. When an agent uses an existing or future mandate on a shared agreement, its counterparties can read that mandate and its history. Each mandate still needs its own approval.</p>
    <p className="muted">Turning this off stops new imports. Existing recipients retain history to verify past actions and revocation. To revoke authority, terminate the mandate.</p>
    {busy && <p role="status">Saving…</p>}
    {error && <p className="error" role="alert">{error}</p>}
  </section>;
}
