import { assetUrl } from "../portal-location";
export function WorkspaceLoading({ message }: { message?: string }) {
  return (
    <main className="workspace-loading" aria-labelledby="workspace-loading-title">
      <div className="workspace-loading-content">
        <img src={assetUrl("/assets/myos-logo.svg")} width="140" height="56" alt="MyOS" />
        <div role="status" aria-live="polite" aria-atomic="true">
          <span className="workspace-loading-spinner" aria-hidden="true" />
          <h1 id="workspace-loading-title">Loading your workspace…</h1>
          <p>{message || "Getting your documents and tools ready. This may take a few moments."}</p>
        </div>
      </div>
    </main>
  );
}
