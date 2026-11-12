import { useState } from "react";
import type { UserSession } from "@blue-labs/myos-sdk";
import { accountApi, accountResult } from "./account-api";
import { chooseAccount, userContext } from "./account-context";
import { AccountGate } from "./components/account-gate";
import "./account-settings.css";
const slot = "myos-pending-invitation";
type Invitation = {
  invitationId: string;
  token: string;
  expectedRevision: number;
};
/** The secret never enters an HTTP URL or sign-in returnTo; retain it only in this tab. */
export function captureInvitation() {
  if (!location.hash.startsWith("#invitation?")) return;
  const query = new URLSearchParams(location.hash.slice("#invitation?".length));
  const invitationId = query.get("invitationId"),
    token = query.get("token"),
    revision = Number(query.get("revision"));
  history.replaceState(
    history.state,
    "",
    location.pathname + location.search + "#invitation",
  );
  sessionStorage.removeItem(slot);
  if (invitationId && token && Number.isSafeInteger(revision) && revision > 0)
    sessionStorage.setItem(
      slot,
      JSON.stringify({ invitationId, token, expectedRevision: revision }),
    );
}
function retained(): Invitation | undefined {
  try {
    const value = JSON.parse(sessionStorage.getItem(slot) || "null");
    if (
      value?.invitationId &&
      value?.token &&
      Number.isSafeInteger(value.expectedRevision)
    )
      return value;
  } catch {
    /* A malformed link cannot grant account access. */
  }
}
export function InvitationAcceptance({ user }: { user: UserSession }) {
  const [invitation] = useState(retained),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  return (
    <AccountGate title="Accept account invitation">
      <p>
        Join the account using the email address that received this invitation.
      </p>
      <p>
        Signed in as <strong>{user.email}</strong>.
      </p>
      <div className="account-invitation-actions">
        {!invitation ? (
          <p>
            This tab has no valid invitation. Open the complete link from your
            email.
          </p>
        ) : (
          <button
            className="primary"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              setError("");
              try {
                const result = await accountResult(
                  accountApi(userContext(user)).acceptAccountInvitation({
                    invitationId: invitation.invitationId,
                    acceptInvitation: {
                      token: invitation.token,
                      expectedRevision: invitation.expectedRevision,
                    },
                  }),
                );
                sessionStorage.removeItem(slot);
                location.hash = "settings/accounts";
                chooseAccount(
                  result.accountId,
                  user.userId,
                  result.membershipGeneration,
                );
              } catch (failure: any) {
                setError(failure.message);
              } finally {
                setBusy(false);
              }
            }}
          >
            {busy ? "Accepting…" : "Accept invitation"}
          </button>
        )}
        <a className="secondary" href="#accounts">
          Choose an account
        </a>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </AccountGate>
  );
}
