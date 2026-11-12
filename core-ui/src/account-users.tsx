import { useEffect, useState, useId } from "react";
import type {
  AccountIdentity,
  AccountMember,
  MembershipRole,
} from "@blue-labs/myos-sdk";
import { accountApi, accountCommand, accountResult } from "./account-api";
import { accountContext } from "./account-context";
import { RoleOptions } from "./account-roles";
import { AccountInvitations } from "./account-invitations";
import { MemberId } from "./account-member-table";

export function AccountUsers({
  account,
  reload,
}: {
  account: AccountIdentity;
  reload: () => Promise<void>;
}) {
  const [members, setMembers] = useState<AccountMember[]>([]),
    [error, setError] = useState("");
  const [target, setTarget] = useState<AccountMember>();
  const [selection, setSelection] = useState(0);
  const admin = ["OWNER", "ADMIN"].includes(account.membership.role);
  useEffect(() => {
    if (!admin) return;
    let active = true;
    accountResult(accountApi(accountContext(account)).listAccountMembers())
      .then((value) => {
        if (active) setMembers(value.items);
      })
      .catch((failure) => {
        if (active) setError(failure.message);
      });
    return () => {
      active = false;
    };
  }, [account, admin]);
  if (!admin) return <p>An Admin manages account users.</p>;
  return (
    <>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <AccountInvitations
        selection={selection}
        account={account}
        targetUserId={target?.userId}
        initialEmail={target?.email}
      >
        {members.map((member) => (
          <MemberRow
            key={`${member.userId}:${member.generation}:${member.revision}`}
            account={account}
            member={member}
            accountHasOwner={members.some((item) => item.status === "ACTIVE" && item.role === "OWNER")}
            lastAdmin={
              ["OWNER", "ADMIN"].includes(member.role) &&
              member.status === "ACTIVE" &&
              members.filter(
                (item) => item.status === "ACTIVE" && ["OWNER", "ADMIN"].includes(item.role),
              ).length === 1
            }
            changed={reload}
            invite={() => {
              setTarget(member);
              setSelection((value) => value + 1);
              document
                .getElementById("account-invitations")
                ?.scrollIntoView({ behavior: "smooth" });
            }}
          />
        ))}
      </AccountInvitations>
    </>
  );
}
function MemberRow({
  account,
  member,
  accountHasOwner,
  lastAdmin,
  changed,
  invite,
}: {
  account: AccountIdentity;
  member: AccountMember;
  accountHasOwner: boolean;
  lastAdmin: boolean;
  changed: () => Promise<void>;
  invite: () => void;
}) {
  const [role, setRole] = useState<MembershipRole>(member.role),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  async function update(status: "ACTIVE" | "REMOVED") {
    setBusy(true);
    setError("");
    const input = {
      role,
      status,
      expectedGeneration: member.generation,
      expectedRevision: member.revision,
    };
    try {
      await accountCommand(
        accountContext(account),
        `membership:${member.userId}`,
        input,
        (api, key) =>
          api.updateAccountMembership({
            userId: member.userId,
            idempotencyKey: key,
            updateMembership: input,
          }),
      );
      await changed();
    } catch (failure: any) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }
  const own = member.userId === account.userId;
  const reasonId = useId();
  const owner = member.role === "OWNER";
  const roleDisabled = busy;
  const ownerUnavailable = owner || member.canOwnAccount === true ? undefined
    : accountHasOwner ? "change the current Owner to Admin first" : "already owns another account";
  const saveDisabled = roleDisabled || role === member.role
    || ((lastAdmin || owner) && !["OWNER", "ADMIN"].includes(role))
    || (role === "OWNER" && ownerUnavailable !== undefined);
  const reason = owner
    ? "Change this role to Admin before transferring ownership or removing this user."
    : lastAdmin ? "Keep an Owner or Admin assigned before removing this user or reducing their role." : undefined;
  return (
    <tr
      className="account-member"
      aria-label={own ? "Your membership" : `User ${member.userId}`}
    >
      <td>
        {member.email}
        {own ? " (you)" : ""}
      </td>
      <td>
        <MemberId value={member.userId} />
      </td>
      <td>
        <span className="member-status">
          {member.status === "ACTIVE" ? "Active" : "Removed"}
        </span>
      </td>
      <td>
        {member.status === "ACTIVE" ? (
          <select
            aria-label="Role"
            value={role}
            disabled={roleDisabled}
            aria-describedby={reason ? reasonId : undefined}
            onChange={(event) => setRole(event.target.value as MembershipRole)}
          >
            <RoleOptions owner ownerUnavailable={ownerUnavailable} administratorsOnly={lastAdmin || owner} />
          </select>
        ) : (
          "—"
        )}
      </td>
      <td>
        <div
          className="inline-actions"
          aria-describedby={reason ? reasonId : undefined}
        >
          {member.status === "ACTIVE" ? (
            <>
              <button
                className="primary"
                aria-describedby={reason ? reasonId : undefined}
                disabled={saveDisabled}
                onClick={() => void update("ACTIVE")}
              >
                Save
              </button>
              <button
                className="secondary"
                aria-describedby={reason ? reasonId : undefined}
                disabled={busy || lastAdmin || owner}
                onClick={() => {
                  if (
                    confirm(
                      `Remove ${own ? "yourself" : "this user"} from the account? Current access will end.`,
                    )
                  )
                    void update("REMOVED");
                }}
              >
                Remove
              </button>
            </>
          ) : (
            <button className="secondary" onClick={invite}>
              Invite again
            </button>
          )}
        </div>
        {reason && (
          <p id={reasonId} className="member-guidance">
            {reason}
          </p>
        )}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
      </td>
    </tr>
  );
}
