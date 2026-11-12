import { useEffect, useState, type ReactNode } from "react";
import type {
  AccountIdentity,
  AccountInvitation,
  InvitationRole,
} from "@blue-labs/myos-sdk";
import { accountApi, accountCommand, accountResult } from "./account-api";
import { accountContext } from "./account-context";
import { RoleOptions } from "./account-roles";
import { MemberTable, PendingInvitationRows } from "./account-member-table";

export function AccountInvitations({
  account,
  targetUserId = "",
  initialEmail = "",
  children,
  selection = 0,
}: {
  account: AccountIdentity;
  targetUserId?: string;
  initialEmail?: string;
  children?: ReactNode;
  selection?: number;
}) {
  const [items, setItems] = useState<AccountInvitation[]>([]);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    if (!["OWNER", "ADMIN"].includes(account.membership.role)) return;
    let active = true;
    accountResult(accountApi(accountContext(account)).listAccountInvitations())
      .then((value) => {
        if (active) setItems(value.items);
      })
      .catch((failure) => {
        if (active) setError(failure.message);
      });
    return () => {
      active = false;
    };
  }, [account]);
  if (!["OWNER", "ADMIN"].includes(account.membership.role))
    return <p>An Admin manages account invitations.</p>;
  async function refresh() {
    setItems(
      (
        await accountResult(
          accountApi(accountContext(account)).listAccountInvitations(),
        )
      ).items,
    );
  }
  async function revoke(item: AccountInvitation) {
    setBusy(true);
    setError("");
    const input = { expectedRevision: item.revision };
    try {
      await accountCommand(
        accountContext(account),
        `revoke-invitation:${item.invitationId}`,
        input,
        (api, key) =>
          api.revokeAccountInvitation({
            invitationId: item.invitationId,
            idempotencyKey: key,
            revokeInvitation: input,
          }),
      );
      await refresh();
    } catch (failure: any) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <InvitationForm
        key={`${targetUserId}:${selection}`}
        account={account}
        targetUserId={targetUserId}
        initialEmail={initialEmail}
        refresh={refresh}
      />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <MemberTable>
        {children}
        <PendingInvitationRows
          items={items}
          busy={busy}
          revoke={(item) => void revoke(item)}
        />
      </MemberTable>
    </>
  );
}

function InvitationForm({
  account,
  targetUserId,
  initialEmail,
  refresh,
}: {
  account: AccountIdentity;
  targetUserId: string;
  initialEmail: string;
  refresh: () => Promise<void>;
}) {
  const [email, setEmail] = useState(initialEmail),
    [target, setTarget] = useState(targetUserId);
  const [role, setRole] = useState<InvitationRole | "">("");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  return (
    <>
      <section
        id="account-invitations"
        className="panel panel-pad account-management"
      >
        <h2>Invite a user</h2>
        <form
          className="account-invite-form"
          onSubmit={async (event) => {
            event.preventDefault();
            if (!role || busy) return;
            setBusy(true);
            setError("");
            const input = {
              email: email.trim(),
              role,
              ...(target.trim() ? { targetUserId: target.trim() } : {}),
            };
            try {
              await accountCommand(
                accountContext(account),
                "invite",
                input,
                (api, key) =>
                  api.createAccountInvitation({
                    idempotencyKey: key,
                    createInvitation: input,
                  }),
              );
              setEmail("");
              setTarget("");
              await refresh();
            } catch (failure: any) {
              setError(failure.message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label className="field">
            Recipient email
            <input
              autoFocus={Boolean(targetUserId)}
              type="email"
              required
              value={email}
              disabled={busy}
              onChange={(event) => {
                setEmail(event.target.value);
                setTarget("");
              }}
            />
          </label>
          <label className="field">
            Role
            <select
              value={role}
              required
              disabled={busy}
              onChange={(event) =>
                setRole(event.target.value as InvitationRole)
              }
            >
              <option value="">Choose a role</option>
              <RoleOptions />
            </select>
          </label>
          <button className="primary" disabled={busy || !role}>
            {busy ? "Sending…" : "Send invite"}
          </button>
        </form>
      </section>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </>
  );
}
