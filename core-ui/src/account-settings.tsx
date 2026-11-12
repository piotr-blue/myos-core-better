import { AccountImageEditor, imageFingerprint } from "@blue-labs/account-ui";
import { Fragment, useEffect, useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { AccountIdentity, AccountAgent, AgentConnection, UpdateAccountAgent } from "@blue-labs/myos-sdk";
import { accountApi, accountCommand, accountResult } from "./account-api";
import { accountContext, accountEpoch } from "./account-context";
import { CopyValue } from "./components/viewer";
import { ConnectAgentDialog } from "./connect-agent";
import { MandateSharing } from "./mandate-sharing";
import "./account-settings.css";

export function AccountSettings({
  account,
  reload,
}: {
  account: AccountIdentity;
  reload: () => Promise<void>;
}) {
  const [name, setName] = useState(account.profile.displayName),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const [address, setAddress] = useState(account.profile.address ?? "");
  const [website, setWebsite] = useState(account.profile.website ?? "");
  const [converting, setConverting] = useState(false);
  const business = account.kind === "BUSINESS" || converting;
  const admin = ["OWNER", "ADMIN"].includes(account.membership.role);
  const profileDirty = converting || name.trim() !== account.profile.displayName || address.trim() !== (account.profile.address ?? "") || website.trim() !== (account.profile.website ?? "");
  return (
    <>
      <section className="panel panel-pad account-management" aria-label="Account profile">
        <h2>Account profile</h2>
        <dl>
          <dt>Account ID</dt>
          <dd>
            <code>{account.accountId}</code> <CopyValue value={account.accountId} />
          </dd>
          <dt>Account Type</dt>
          <dd>{account.kind === "PERSONAL" ? "Personal" : "Business"}</dd>
        </dl>
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            setBusy(true);
            setError("");
            const input = {
              displayName: name.trim(),
              website: website.trim() || null,
              ...(business ? { address: address.trim() } : {}),
              ...(converting ? { kind: "BUSINESS" as const } : {}),
              expectedRevision: account.revision,
            };
            try {
              await accountCommand(
                accountContext(account),
                "profile",
                input,
                (api, key) =>
                  api.updateAccountProfile({
                    idempotencyKey: key,
                    updateAccountProfile: input,
                  }),
              );
              setConverting(false);
              await reload();
            } catch (failure: any) {
              setError(failure.message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label className="field">
            {business ? "Business Name" : "Display name"}
            <input
              required
              maxLength={160}
              value={name}
              disabled={!admin || busy}
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          <label className="field">Website (optional)
            <input name="website" type="url" maxLength={2048} autoComplete="url" placeholder="https://example.com" value={website} disabled={busy || !admin} onChange={event => setWebsite(event.target.value)} />
          </label>
          {business && <label className="field">Address (optional)
            <textarea rows={3} maxLength={1000} autoComplete="street-address" value={address} disabled={busy || !admin} onChange={event => setAddress(event.target.value)} />
          </label>}
          {admin ? (
            <div className="inline-actions">
              <button
                className="primary"
                disabled={
                  busy ||
                  !name.trim() ||
                  (!converting && name.trim() === account.profile.displayName && address.trim() === (account.profile.address ?? "") && website.trim() === (account.profile.website ?? ""))
                }
              >
                {busy ? "Saving…" : converting ? "Convert to Business" : "Save profile"}
              </button>
              {account.kind === "PERSONAL" && (
                <button className="secondary" type="button" disabled={busy} onClick={() => setConverting(!converting)}>
                  {converting ? "Cancel conversion" : "Convert to Business"}
                </button>
              )}
            </div>
          ) : (
            <p>An Admin manages this account profile.</p>
          )}
          {converting && <p>Enter your business name and address. This account cannot be converted back to Personal.</p>}
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
        </form>
        {account.kind === "BUSINESS" && profileDirty && <p>Save your profile text before changing the account image.</p>}
      </section>
        <AccountImageEditor imageUrl={account.profile.imageUrl} business={account.kind === "BUSINESS"} disabled={busy || !admin || profileDirty}
          upload={async file => {
            setBusy(true);
            try {
              const epoch = accountEpoch();
              const sha256 = await imageFingerprint(file);
              if (epoch !== accountEpoch()) throw new DOMException("Workspace changed", "AbortError");
              await accountCommand(accountContext(account), "account-image", { sha256, expectedRevision: account.revision },
                (api, key) => api.putAccountImage({ file, expectedRevision: account.revision, idempotencyKey: key }));
              await reload();
            } finally { setBusy(false); }
          }}
          remove={async () => {
            setBusy(true);
            try {
              await accountCommand(accountContext(account), "account-image-remove", { expectedRevision: account.revision },
                (api, key) => api.deleteAccountImage({ deleteAccountImage: { expectedRevision: account.revision }, idempotencyKey: key }));
              await reload();
            } finally { setBusy(false); }
          }} />
    </>
  );
}
export function AccountAgents({ account }: { account: AccountIdentity }) {
  const [agents, setAgents] = useState<AccountAgent[]>([]);
  const [expanded, setExpanded] = useState<string>();
  const [connecting, setConnecting] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(true), [error, setError] = useState("");
  const admin = ["OWNER", "ADMIN"].includes(account.membership.role);
  useEffect(() => {
    let active = true;
    setLoading(true); setError("");
    accountResult(accountApi(accountContext(account)).listAccountAgents())
      .then(result => { if (active) setAgents(result.items); })
      .catch(failure => { if (active) setError(failure.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [account, refresh]);
  return <section className="panel account-agent-list" aria-label="Account agents">
    <header className="panel-heading">
      <div><h2>Agents</h2><p>Manage the agents that work in {account.profile.displayName}.</p></div>
      {admin && <button className="primary" onClick={() => setConnecting(true)} aria-haspopup="dialog">Connect agent</button>}
    </header>
    {admin && <MandateSharing key={account.accountId} account={account} />}
    {connecting && <ConnectAgentDialog account={account} onClose={() => { setConnecting(false); setRefresh(value => value + 1); }} />}
    {error && <p role="alert" className="error panel-pad">{error}</p>}
    {loading && agents.length === 0 ? <p className="panel-pad" role="status">Loading agents…</p>
      : agents.length === 0 ? <p className="panel-pad">No agents yet. Connect an agent to get started.</p>
      : <div className="account-agents-table"><table className="data-table" aria-label="Account agents">
        <thead><tr><th>Agent</th><th>Type</th><th>Read access</th><th>Status</th></tr></thead>
        <tbody>{agents.map(agent => {
          const open = expanded === agent.agentId;
          const name = agent.kind === "EXTERNAL" ? agent.name : "MyOS Agent";
          return <Fragment key={agent.agentId}>
            <tr className={open ? "agent-row expanded" : "agent-row"}>
              <td><button className="agent-expand" aria-expanded={open} aria-controls={`agent-${agent.agentId}`}
                onClick={() => setExpanded(open ? undefined : agent.agentId)}>
                {open ? <ChevronDown size={18} aria-hidden="true" /> : <ChevronRight size={18} aria-hidden="true" />}{name}
              </button></td>
              <td>{agent.kind === "EXTERNAL" ? "External" : "Built-in"}</td>
              <td>{agent.role === "READ_ALL" ? "All documents" : "Selected documents"}</td>
              <td><span className="member-status">{agentStatus(agent)}</span></td>
            </tr>
            {open && <tr id={`agent-${agent.agentId}`} className="agent-expanded"><td colSpan={4}>
              <AccountAgentSettings key={agent.agentId + ":" + agent.revision} account={account} agent={agent} admin={admin}
                changed={next => setAgents(current => current.map(item => item.agentId === next.agentId ? next : item))} />
            </td></tr>}
          </Fragment>;
        })}</tbody>
      </table></div>}
  </section>;
}

function agentStatus(agent: AccountAgent) {
  return agent.kind === "EXTERNAL" ? agent.status === "PAUSED" ? "Paused" : "Active"
    : agent.execution === "ENABLED" ? "Enabled" : "Disabled";
}

function AccountAgentSettings({ account, agent, admin, changed }: {
  account: AccountIdentity; agent: AccountAgent; admin: boolean; changed: (agent: AccountAgent) => void;
}) {
  const [name, setName] = useState(agent.name ?? "");
  const [connections, setConnections] = useState<AgentConnection[]>();
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  const external = agent.kind === "EXTERNAL";
  const [connecting, setConnecting] = useState(false);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    if (!external || !admin) return;
    let active = true;
    accountResult(accountApi(accountContext(account)).listAgentConnections({ agentId: agent.agentId }))
      .then(result => { if (active) setConnections(result.items); })
      .catch(failure => { if (active) setError(failure.message); });
    return () => { active = false; };
  }, [account, agent.agentId, external, admin, refresh]);
  async function update(patch: Omit<UpdateAccountAgent, "expectedRevision">) {
    setBusy(true); setError("");
    try {
      const input = { ...patch, expectedRevision: agent.revision };
      changed(await accountCommand(accountContext(account), "agent-settings", { agentId: agent.agentId, ...input },
        (api, key) => api.updateAccountAgent({ agentId: agent.agentId, updateAccountAgent: input, idempotencyKey: key })));
    } catch (failure: any) { setError(failure.message); } finally { setBusy(false); }
  }
  return <section className="agent-settings account-management" aria-label={external ? agent.name : "MyOS Agent"}>
    <h3>{external ? agent.name : "MyOS Agent"}</h3>
    <dl><dt>Agent ID</dt><dd><code>{agent.agentId}</code> <CopyValue value={agent.agentId} /></dd>
      <dt>Status</dt><dd>{external ? agent.status === "PAUSED" ? "Paused" : "Active" : agent.execution === "ENABLED" ? "Enabled" : "Disabled"}</dd></dl>
    {external && admin && <form onSubmit={event => { event.preventDefault(); void update({ name: name.trim() }); }}>
      <label className="field"><span>Name</span><input value={name} maxLength={160} required onChange={event => setName(event.target.value)} /></label>
      <button className="secondary" disabled={busy || !name.trim() || name.trim() === agent.name}>Save name</button>
    </form>}
    <label className="field"><span>Read access</span>
      <select value={agent.role} disabled={!external || !admin || busy} onChange={event => void update({ role: event.target.value as UpdateAccountAgent["role"] })}>
        <option value="READ_SELECTED">Selected documents</option><option value="READ_ALL">All account documents</option>
      </select>
    </label>
    {external && <p>Read access does not authorize actions. Each document decides which operations this Agent may perform.</p>}
    <label className="field"><span><input type="checkbox" checked={agent.inclusion} disabled={!admin || busy}
      onChange={event => void update({ inclusion: event.target.checked })} /> Include this Agent in new documents</span></label>
    <p>This preference applies to supported Party documents. Existing documents keep their original participants.</p>
    {external && admin && <>
      <div className="inline-actions"><button className="secondary" disabled={busy} onClick={() => void update({ status: agent.status === "PAUSED" ? "ACTIVE" : "PAUSED" })}>{agent.status === "PAUSED" ? "Resume Agent" : "Pause Agent"}</button>
      <button className="secondary" disabled={busy} aria-haspopup="dialog" onClick={() => setConnecting(true)}>Reconnect application</button></div>
      {connecting && <ConnectAgentDialog account={account} agent={agent} onClose={() => { setConnecting(false); setRefresh(value => value + 1); }} />}
      <h4>Connections</h4>
      {!connections && <p role="status">Loading connections…</p>}
      {connections?.length === 0 && <p>No approved connections. Connect an application to use this agent.</p>}
      {connections?.map(connection => <div className="agent-connection-row" key={connection.connectionId}>
        <span>{connection.clientId} · {connection.status === "ACTIVE" ? "Connected" : "Disconnected"}</span>
        {connection.status === "ACTIVE" && <button className="secondary" disabled={busy} onClick={async () => {
          setBusy(true); setError("");
          try {
            const input = { expectedRevision: connection.revision };
            const next = await accountCommand(accountContext(account), "agent-disconnect", { connectionId: connection.connectionId, ...input },
              (api, key) => api.revokeAgentConnection({ agentId: agent.agentId, connectionId: connection.connectionId, revokeAgentConnection: input, idempotencyKey: key }));
            setConnections(current => current?.map(item => item.connectionId === next.connectionId ? next : item));
          } catch (failure: any) { setError(failure.message); } finally { setBusy(false); }
        }}>Disconnect</button>}
      </div>)}
    </>}
    {error && <p role="alert" className="error">{error}</p>}
  </section>;
}
