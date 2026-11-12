import { useEffect, useRef, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { AdminApi } from "@blue-labs/myos-sdk";
import { coreConfiguration } from "../account-api";
import { impersonation } from "../impersonation-state";
import { portalOrigin } from "../portal-location";
import { UserAvatar } from "./user-avatar";
export function AdminMenuLink({
  onboarding = false,
  imageUrl,
}: {
  onboarding?: boolean;
  imageUrl?: string | null;
}) {
  const [allowed, setAllowed] = useState(false);
  const menu = useRef<HTMLDetailsElement>(null);
  useEffect(() => {
    if (impersonation()) return;
    const controller = new AbortController();
    new AdminApi(coreConfiguration({}))
      .getAdminSession({}, { signal: controller.signal })
      .then(() => {
        if (!controller.signal.aborted) setAllowed(true);
      })
      .catch(() => {
        /* Only verified administrators see this destination. */
      });
    return () => controller.abort();
  }, []);
  if (!allowed) return null;
  const link = (
    <a
      role={onboarding ? undefined : "menuitem"}
      className="account-menu-item"
      href={`${portalOrigin()}/developers/app/#admin`}
    >
      <ShieldCheck size={20} strokeWidth={1.5} aria-hidden="true" />
      Admin Panel
    </a>
  );
  if (!onboarding) return link;
  return (
    <details
      className="account-area onboarding-admin-menu"
      ref={menu}
      onBlur={(event) => {
        if (
          event.relatedTarget &&
          !menu.current?.contains(event.relatedTarget as Node)
        )
          menu.current!.open = false;
      }}
      onKeyDown={(event) => {
        if (event.key === "Escape" && menu.current) {
          event.stopPropagation();
          menu.current.open = false;
          menu.current.querySelector("summary")?.focus();
        }
      }}
    >
      <summary className="account-button" aria-label="Account menu">
        <UserAvatar imageUrl={imageUrl} />
      </summary>
      <div className="account-menu">{link}</div>
    </details>
  );
}
