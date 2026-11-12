import { useId, useState } from "react";
import type { AccountAgent, AccountIdentity } from "@blue-labs/myos-sdk";
import { Dialog } from "./components/dialog";
import { CopyValue } from "./components/viewer";
import { portalLocation } from "./portal-location";

export function ConnectAgentDialog({ account, agent, onClose }: {
  account: AccountIdentity; agent?: AccountAgent; onClose: () => void;
}) {
  const id = useId();
  const [tab, setTab] = useState<"dots" | "other">("dots");
  const [copied, setCopied] = useState(false), [error, setError] = useState("");
  const seamless = tab === "dots" && portalLocation.dotsCimd;
  const selection = agent ? `select the existing agent “${agent.name}”` : "choose Create a new agent and give it a name";
  const instructions = `Connect to MyOS using this MCP server: ${portalLocation.mcp || "ask the MyOS administrator for the server URL"}. ${seamless
    ? "Use OAuth with Client ID Metadata Document (CIMD) registration. No manually supplied client ID or secret is needed."
    : `Use OAuth with a pre-registered client${tab === "dots" && portalLocation.mcpClientId ? ` (client ID: ${portalLocation.mcpClientId})` : ""} and client_secret_post authentication. I will enter the client secret in the integration settings if required.`} Start the OAuth connection so I can sign in and approve it. In account “${account.profile.displayName}”, ${selection}. After approval, call get_profile to confirm the connection.`;
  function select(next: "dots" | "other") { setTab(next); setCopied(false); setError(""); }
  return <Dialog title={agent ? "Reconnect application" : "Connect agent"} className="account-management account-onboarding connect-agent-dialog" onClose={onClose}>
    <nav className="tabs connection-tabs" role="tablist" aria-label="Agent application" onKeyDown={event => {
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
      event.preventDefault();
      const next = event.key === "Home" ? "dots" : event.key === "End" ? "other" : tab === "dots" ? "other" : "dots";
      select(next); document.getElementById(`${id}-${next}`)?.focus();
    }}>
      {(["dots", "other"] as const).map(value => <button key={value} type="button" role="tab" id={`${id}-${value}`} aria-selected={tab === value}
        aria-controls={`${id}-${value}-panel`} tabIndex={tab === value ? 0 : -1} className={`tab ${tab === value ? "active" : ""}`}
        onClick={() => select(value)}>{value === "dots" ? "Dots" : "Other"}</button>)}
    </nav>
    <section role="tabpanel" id={`${id}-${tab}-panel`} aria-labelledby={`${id}-${tab}`}>
      <p>{tab === "dots" ? "Connect your Dots agent" : "Connect another agent app"} to {account.profile.displayName}.</p>
      {portalLocation.mcp ? <label className="field">MCP server URL
        <span className="inline-actions"><input readOnly value={portalLocation.mcp} /><CopyValue value={portalLocation.mcp} /></span>
      </label> : <p>Ask your MyOS administrator for this environment’s MCP server URL.</p>}
      {seamless ? <>
        <p>Sign in and approve the connection. No client ID or secret to copy.</p>
        <ol>
          <li>In ChatGPT, open <strong>Plugins → Add → Add custom MCP server</strong>.</li>
          <li>Name it <strong>MyOS</strong>, paste the server URL and choose <strong>OAuth</strong>. Client registration is discovered automatically. If prompted, choose <strong>Client ID Metadata Document (CIMD)</strong> in Advanced OAuth settings.</li>
          <li>Start the connection, sign in to MyOS and choose <strong>{account.profile.displayName}</strong>. {agent ? <>Select <strong>{agent.name}</strong>.</> : <>Choose <strong>Create a new agent</strong> and name it <strong>Dots</strong>.</>} Review the access and approve.</li>
          <li>Return to Dots and ask it to use MyOS. Your agent appears in this list after approval.</li>
        </ol>
      </> : <>
        {tab === "dots" && <p>Automatic Dots setup is not enabled in this environment yet. Use the OAuth settings below.</p>}
        <section className="connection-oauth-setup" aria-label="Required OAuth setup">
          <h3>MCP and OAuth setup</h3>
          <p>Add the server URL in your app’s integrations and choose OAuth.</p>
          {tab === "dots" && portalLocation.mcpClientId && <label className="field">OAuth client ID
            <span className="inline-actions"><input readOnly value={portalLocation.mcpClientId} /><CopyValue value={portalLocation.mcpClientId} /></span>
          </label>}
          <p>For manual registration, use the client ID and secret supplied for your application by the MyOS administrator, with <code>client_secret_post</code> authentication.</p>
          {tab === "dots" ? <p>In ChatGPT, open <strong>Advanced OAuth settings</strong> and select <strong>User-Defined OAuth Client</strong>. Keep the discovered endpoints. Leave Registration URL empty and OpenID support off.</p>
            : <p>Use the authorization and token endpoints discovered from MyOS. Your app’s exact callback URL must be registered with its OAuth client.</p>}
        </section>
        <ol>
          <li>Start the connection and sign in to MyOS.</li>
          <li>Choose <strong>{account.profile.displayName}</strong> and {agent ? <>select <strong>{agent.name}</strong>.</> : <>create a new agent. Review the access and approve.</>}</li>
          <li>Return here to see {agent ? "the updated connection" : "your agent in the list"}.</li>
        </ol>
      </>}
      {!agent && <p>The agent is created when you approve the connection. New agents start with access to selected documents; you can manage their permissions here.</p>}
      <details key={tab}><summary>Send instructions to your agent</summary>
        <p>If your agent can set up integrations from a message, send it these instructions.</p>
        <textarea aria-label="Agent connection instructions" value={instructions} readOnly rows={5} />
        <button className="secondary" onClick={async () => {
          try { await navigator.clipboard.writeText(instructions); setCopied(true); setError(""); }
          catch { setError("Copy the instructions from the text field above."); }
        }}>{copied ? "Copied" : "Copy instructions"}</button>
      </details>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
    <div className="dialog-footer"><button className="primary" onClick={event => { event.currentTarget.closest("dialog")?.close(); onClose(); }}>Done</button></div>
  </Dialog>;
}
