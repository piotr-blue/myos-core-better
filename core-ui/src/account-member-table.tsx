import { useEffect, useState, type ReactNode } from "react";
import type { AccountInvitation } from "@blue-labs/myos-sdk";
import { CopyValue } from "./components/viewer";
import { roleLabels } from "./account-roles";

export function MemberId({ value }: { value?: string | null }) {
  return value ? (
    <div className="member-id">
      <code title={value}>{value}</code>
      <CopyValue value={value} />
    </div>
  ) : (
    <span className="subtle" title="Assigned when the invitation is accepted">
      —
    </span>
  );
}

export function MemberTable({ children }: { children: ReactNode }) {
  return (
    <section>
      <h2>Account users</h2>
      <div className="panel account-users-table">
        <table className="data-table" aria-label="Account users">
          <thead>
            <tr>
              {["Email", "User ID", "Status", "Role", "Actions"].map(
                (title) => (
                  <th scope="col" key={title}>
                    {title}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody>{children}</tbody>
        </table>
      </div>
    </section>
  );
}

export function PendingInvitationRows({
  items,
  busy,
  revoke,
}: {
  items: AccountInvitation[];
  busy: boolean;
  revoke: (item: AccountInvitation) => void;
}) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(timer);
  }, []);
  return (
    <>
      {items
        .filter((item) => item.status === "PENDING" && item.expiresAt > now)
        .map((item) => (
          <tr key={item.invitationId}>
            <td>{item.recipientEmail}</td>
            <td>
              <MemberId value={item.recipientUserId} />
            </td>
            <td>
              <span
                className="member-status pending"
                title={`Expires ${new Date(item.expiresAt).toLocaleString()}`}
              >
                Pending invite
              </span>
            </td>
            <td>{roleLabels[item.role]}</td>
            <td>
              <button
                className="secondary"
                disabled={busy}
                onClick={() => revoke(item)}
              >
                Remove
              </button>
            </td>
          </tr>
        ))}
    </>
  );
}
