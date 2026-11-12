import { useSyncExternalStore } from "react";
import { serviceWaiting, subscribeServiceWaiting } from "../service-recovery";

export function ServiceRecoveryNotice() {
  const waiting = useSyncExternalStore(subscribeServiceWaiting, serviceWaiting);
  if (!waiting) return null;
  return (
    <div className="service-recovery-notice" role="status" aria-live="polite">
      <strong>Waiting for the service to reconnect…</strong>
      <span>Keep this page open. Your request will resume automatically.</span>
    </div>
  );
}
