import { AccountCreationForm } from "@blue-labs/account-ui";
import { useEffect, useRef, useState } from "react";
import type { UserSession } from "@blue-labs/myos-sdk";
import { accountApi, accountResult, accountCommand } from "./account-api";
import { chooseAccount, userContext, accountContext, accountEpoch } from "./account-context";
import "./account-settings.css";
import { AccountGate } from "./components/account-gate";
import { Dialog } from "./components/dialog";
import { AdminMenuLink } from "./components/admin-menu-link";

export function AccountSelection({ user }: { user: UserSession }) {
  const accounts = user.accounts.filter(
    (item) => item.membership.status === "ACTIVE",
  );
  const needsDefault =
    accounts.length > 0 && !user.preferences.defaultAccountId;
  return (
    <AccountGate
      headerActions={<AdminMenuLink onboarding imageUrl={user.imageUrl} />}
      title={
        needsDefault
          ? "Choose your default workspace"
          : accounts.length
            ? "Choose a workspace"
            : "Set up your account"
      }
    >
      <p>Signed in as {user.email}.</p>
      <p><a href="#settings/profile">Edit my profile</a></p>
      {needsDefault ? (
        <DefaultWorkspaceChoice key={user.preferences.revision} user={user} />
      ) : accounts.length ? (
        <>
          <p>Select a current workspace to continue.</p>
          <div className="account-choices">
            {accounts.map((item) => (
              <button
                className="secondary"
                key={item.accountId}
                onClick={() =>
                  chooseAccount(
                    item.accountId,
                    user.userId,
                    item.membership.generation,
                  )
                }
              >
                <>{item.profile.imageUrl && <img src={item.profile.imageUrl} alt="" width={32} height={32} style={{ objectFit: "contain" }} />}<strong>{item.profile.displayName}</strong></> ·{" "}
                {item.kind === "PERSONAL" ? "Personal" : "Business"}
                {["OWNER", "ADMIN"].includes(item.membership.role) && <span>({item.membership.role === "OWNER" ? "Owner" : "Admin"})</span>}
              </button>
            ))}
          </div>
        </>
      ) : (
        <>
          <p>
            Create a workspace to get started, or open an invitation link to
            join one. Your login stays the same.
          </p>
          <CreateAccount user={user} />
        </>
      )}
    </AccountGate>
  );
}

function DefaultWorkspaceChoice({ user }: { user: UserSession }) {
  const [selected, setSelected] = useState("");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const accounts = user.accounts.filter(
    (item) => item.membership.status === "ACTIVE",
  );
  return (
    <form
      onSubmit={async (event) => {
        event.preventDefault();
        const next = accounts.find((item) => item.accountId === selected);
        if (busy || !next) return;
        setBusy(true);
        setError("");
        const input = {
          defaultAccountId: next.accountId,
          expectedMembershipGeneration: next.membership.generation,
          expectedRevision: user.preferences.revision,
        };
        try {
          await accountCommand(
            userContext(user),
            "default-workspace",
            input,
            (api, key) =>
              api.updateUserPreferences({
                idempotencyKey: key,
                updateUserPreferences: input,
              }),
          );
          if (alive.current) {
            location.hash = "documents";
            chooseAccount(
              next.accountId,
              user.userId,
              next.membership.generation,
            );
          }
        } catch (failure: any) {
          if (alive.current) setError(failure.message);
        } finally {
          if (alive.current) setBusy(false);
        }
      }}
    >
      <p>
        Your previous default workspace is no longer available. Choose the
        workspace you want to open when you sign in.
      </p>
      <label className="field">
        Default workspace
        <select
          required
          value={selected}
          disabled={busy}
          onChange={(event) => setSelected(event.target.value)}
        >
          <option value="" disabled>
            Select a workspace
          </option>
          {accounts.map((item) => (
            <option key={item.accountId} value={item.accountId}>
              {item.profile.displayName}
              {item.membership.role === "OWNER" ? " (Owner)" : item.membership.role === "ADMIN" ? " (Admin)" : ""}
            </option>
          ))}
        </select>
      </label>
      {error && (
        <p role="alert" className="error">
          {error}{" "}
          <button
            type="button"
            className="secondary"
            onClick={() => location.reload()}
          >
            Refresh workspaces
          </button>
        </p>
      )}
      <button className="primary" disabled={busy || !selected}>
        {busy ? "Saving…" : "Continue"}
      </button>
    </form>
  );
}

export function CreateAccountButton({ user }: { user: UserSession }) {
  const [open, setOpen] = useState(false);
  return <>
    <button type="button" className="primary" aria-haspopup="dialog" onClick={() => setOpen(true)}>Create account</button>
    {open && <Dialog title="Create account" className="account-management account-onboarding account-creation-dialog" onClose={() => setOpen(false)}>
      <CreateAccount user={user} additional onComplete={result => {
        location.hash = "settings/accounts";
        chooseAccount(result.accountId, user.userId, result.membershipGeneration);
        setOpen(false);
      }} />
    </Dialog>}
  </>;
}

export function CreateAccount({ user, additional = false, onComplete }: { user: UserSession; additional?: boolean; onComplete?: (result: {accountId:string; membershipGeneration:number}) => void | Promise<void> }) {
  const epoch = accountEpoch();
  const assertCurrent = () => { if (epoch !== accountEpoch()) throw new DOMException("Your workspace changed. Reopen account creation.", "AbortError"); };
  return <AccountCreationForm key={user.userId + user.sessionContext} scope={user.userId + ":" + user.sessionContext} additional={additional}
    create={async (input, requestId) => {
      assertCurrent();
      const result = await accountResult(accountApi(userContext(user)).createAccount({ createAccount: input, idempotencyKey: requestId }));
      assertCurrent(); return result;
    }}
    upload={async (created, file, requestId) => {
      assertCurrent();
      const account = await accountResult(accountApi({ accountId: created.accountId }, userContext(user)).getAccount());
      assertCurrent();
      if (account.userId !== user.userId || account.membership.generation !== created.membershipGeneration) throw new Error("Your account membership changed.");
      const result = await accountResult(accountApi(accountContext(account), userContext(user)).putAccountImage({ file, expectedRevision: 1, idempotencyKey: requestId }));
      assertCurrent(); return result;
    }}
    complete={async result => {
      assertCurrent();
      if (onComplete) {await onComplete(result); return;}
      location.hash = additional ? "settings/accounts" : "documents";
      chooseAccount(result.accountId, user.userId, result.membershipGeneration);
    }} />;
}
