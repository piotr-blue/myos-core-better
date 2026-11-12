import { AdminMenuLink } from "./admin-menu-link";
// Visual structure adapted from myos-java's AuthenticatedAccountMenu.
import { useEffect, useRef, useState } from "react";
import { LogOut, ArrowUpRight, BriefcaseBusiness } from "lucide-react";
import { BusinessSwitch } from "../portal-auth";
import { portalOrigin } from "../portal-location";
import { UserAvatar } from "./user-avatar";

export function AccountMenu({
  accountId,
  imageUrl,
  logout,
  adminPanel = false,
}: {
  adminPanel?: boolean;
  accountId?: string;
  imageUrl?: string | null;
  logout: () => void | Promise<void>;
}) {
  const [open, setOpen] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const area = useRef<HTMLDivElement>(null),
    trigger = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const dismiss = (event: PointerEvent) => {
      if (!area.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", dismiss);
    return () => document.removeEventListener("pointerdown", dismiss);
  }, []);
  useEffect(() => {
    if (open)
      area.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
  }, [open]);
  return (
    <div
      className="account-area"
      ref={area}
      onBlur={(event) => {
        // Safari pointer clicks can blur the focused menu link to the body
        // before delivering the button click. Outside pointers already dismiss.
        if (event.relatedTarget && !area.current?.contains(event.relatedTarget as Node))
          setOpen(false);
      }}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          setOpen(false);
          trigger.current?.focus();
          return;
        }
        if (!open) return;
        const items = [
          ...area.current!.querySelectorAll<HTMLElement>('[role="menuitem"]'),
        ];
        const current = items.indexOf(document.activeElement as HTMLElement);
        const index =
          event.key === "ArrowDown"
            ? (current + 1) % items.length
            : event.key === "ArrowUp"
              ? (current + items.length - 1) % items.length
              : event.key === "Home"
                ? 0
                : event.key === "End"
                  ? items.length - 1
                  : null;
        if (index !== null) {
          event.preventDefault();
          items[index]?.focus();
        }
      }}
    >
      <button
        ref={trigger}
        className="account-button"
        aria-label="Account menu"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? "dev-account-menu" : undefined}
        onClick={() => setOpen(!open)}
      >
        <UserAvatar imageUrl={imageUrl} />
      </button>
      {open && (
        <div
          id="dev-account-menu"
          className="account-menu"
          role="menu"
          aria-label="Account"
        >
          {adminPanel ? <>
            <a role="menuitem" className="account-menu-item" href={`${portalOrigin()}/developers/app/`}><ArrowUpRight size={20} strokeWidth={1.5} aria-hidden="true" />Developer Platform</a>
            <a role="menuitem" className="account-menu-item" href={`${portalOrigin()}/business/`}><BriefcaseBusiness size={20} strokeWidth={1.5} aria-hidden="true" />MyOS Business</a>
          </> : <><AdminMenuLink />{accountId && <BusinessSwitch accountId={accountId} />}</>}
          <div className="account-menu-divider" role="separator" />
          <button
            role="menuitem"
            className="account-menu-item"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              setError("");
              try {
                await logout();
              } catch {
                setError("The session could not be ended. Try again.");
                setBusy(false);
              }
            }}
          >
            <LogOut size={20} strokeWidth={1.5} aria-hidden="true" />
            {busy ? "Signing out…" : "Sign out"}
          </button>
          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
