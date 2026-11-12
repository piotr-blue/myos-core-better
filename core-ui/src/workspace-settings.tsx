import { useState } from "react";
import type { AccountIdentity, UserSession } from "@blue-labs/myos-sdk";
import { accountCommand } from "./account-api";
import { chooseAccount, userContext } from "./account-context";
import { CreateAccountButton } from "./account-selection";
import { AccountSettings, AccountAgents } from "./account-settings";
import { AccountUsers } from "./account-users";
import { AccountInvitations } from "./account-invitations";
import { UserProfileSettings } from "./user-profile-settings";
import { AccessSettings } from "./access-settings";

export function WorkspaceSelector({ user, account }: { user: UserSession; account: AccountIdentity }) {
  const workspaceName = (item: Pick<AccountIdentity, "profile" | "membership">) => `${item.profile.displayName}${item.membership.role === "OWNER" ? " (Owner)" : item.membership.role === "ADMIN" ? " (Admin)" : ""}`;
  return <label className="workspace-selector">
    {account.profile.imageUrl && <img src={account.profile.imageUrl} alt="Account logo" width={32} height={32} style={{ objectFit: "contain" }} />}
    <span className="workspace-selector-control">
    <span className="workspace-selector-label" aria-hidden="true">{workspaceName(account)}</span>
    <select aria-label="Workspace" value={account.accountId} onChange={event => {
      const next = user.accounts.find(item => item.accountId === event.target.value && item.membership.status === "ACTIVE");
      if (next) { location.hash = "documents"; chooseAccount(next.accountId, user.userId, next.membership.generation); }
    }}>
      {user.accounts.filter(item => item.membership.status === "ACTIVE").map(item =>
        <option key={item.accountId} value={item.accountId}>{workspaceName(item)}</option>)}
    </select>
    </span>
  </label>;
}

export function WorkspaceSettings({ user, account, page, reload }: {
  user: UserSession; account?: AccountIdentity; page: string; reload: () => Promise<void>;
}) {
  const [path, query = ""] = page.split("?");
  const section = path.split("/")[1] || (account ? "accounts" : "profile");
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  return <div className="account-management">
    <h1>Settings</h1>
    <nav className="settings-sections" aria-label="Settings">
      {[["profile", "My profile"], ["accounts", "Accounts"], ["users", "Users"], ["agents", "Agents"]].filter(([id]) => id === "profile" || id === "accounts" || (account && (id !== "users" || ["OWNER", "ADMIN"].includes(account.membership.role)))).map(([id, label]) =>
        <a key={id} href={`#settings/${id}`} aria-current={section === id || (id === "users" && section === "invitations") ? "page" : undefined}>{label}</a>)}
      {account && ["OWNER", "ADMIN"].includes(account.membership.role) && <a href="#access-settings" aria-current={section === "access" ? "page" : undefined}>Participant access</a>}
    </nav>
    {section === "profile" ? <UserProfileSettings key={user.userId + user.sessionContext} user={user} reload={reload} />
    : !account ? <section className="panel panel-pad"><h2>Your accounts</h2><p><a href="#accounts">Choose or create a workspace</a> to manage account settings.</p></section>
    : section === "access" ? <AccessSettings key={account.accountId + account.sessionContext} />
    : section === "accounts" ? <>
      <section className="panel panel-pad">
        <div className="account-list-heading">
          <h2>Your accounts</h2>
          <CreateAccountButton key={user.userId + account.accountId} user={user} />
        </div>
        <p>Your default workspace opens when you sign in. Switching workspaces does not change it.</p>
        <label className="field">Default workspace
          <select value={user.preferences.defaultAccountId ?? ""} disabled={busy} onChange={async event => {
            const next = user.accounts.find(item => item.accountId === event.target.value);
            if (!next) return;
            setBusy(true); setError("");
            const input = {defaultAccountId: next.accountId, expectedMembershipGeneration: next.membership.generation, expectedRevision: user.preferences.revision};
            try {
              await accountCommand(userContext(user), "default-workspace", input,
                (api, key) => api.updateUserPreferences({idempotencyKey: key, updateUserPreferences: input}));
              await reload();
            } catch (failure: any) { setError(failure.message); }
            finally { setBusy(false); }
          }}>
            {!user.preferences.defaultAccountId && <option value="" disabled>Choose a default workspace</option>}
            {user.accounts.map(item => <option key={item.accountId} value={item.accountId}>{item.profile.displayName}{item.membership.role === "OWNER" ? " (Owner)" : item.membership.role === "ADMIN" ? " (Admin)" : ""}</option>)}
          </select>
        </label>
        {error && <p className="error" role="alert">{error}</p>}
      </section>
      <AccountSettings key={account.revision} account={account} reload={reload} />
    </> : section === "users" ? <AccountUsers account={account} reload={reload} />
      : section === "invitations" ? <AccountInvitations key={query} account={account} targetUserId={new URLSearchParams(query).get("userId") || ""} />
      : section === "agents" ? <AccountAgents key={account.accountId} account={account} /> : <p>Choose a Settings section.</p>}
  </div>;
}
