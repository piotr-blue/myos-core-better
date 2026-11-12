import { assetUrl, portalOrigin } from "../portal-location";
import { AccountMenu } from "./account-menu";
import { PrivacySettingsButton } from "../privacy-preferences";
// Adapted from myos-java/apps/myos-blue/src/app/dev/components/dev-layout.tsx.
import { useEffect, useState, type ReactNode } from "react";
import {
  FileText,
  CalendarDays,
  Webhook,
  Package,
  Database,
  Key,
  ListOrdered,
  Menu,
  X,
  Code2,
  Wallet,
  Settings,
  UserRound,
  Users,
  Bell,
} from "lucide-react";
const navItems = [
  { label: "Timelines", href: "timelines", icon: ListOrdered },
  { label: "Documents", href: "documents", icon: FileText },
  { label: "Contents", href: "contents", icon: Database },
  { label: "MyOS Events", href: "events", icon: CalendarDays },
  { label: "Webhooks", href: "webhooks", icon: Webhook },
  { label: "Packages", href: "packages", icon: Package },
  { label: "API Keys", href: "api-keys", icon: Key },
  { label: "Gas", href: "gas", icon: Wallet },
  { label: "API Explorer", href: "explorer", icon: Code2 },
  { label: "Settings", href: "settings", icon: Settings },
];
export function DevLayout({
  page,
  navigate,
  accountId,
  userImageUrl,
  admin = true,
  adminPanel = false,
  workspaceSelector,
  logout,
  children,
}: {
  page: string;
  navigate: (p: string) => void;
  accountId?: string;
  userImageUrl?: string | null;
  admin?: boolean;
  adminPanel?: boolean;
  workspaceSelector?: ReactNode;
  logout?: () => void | Promise<void>;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const section = page.split(/[/?]/)[0];
  const navigationSection = section === "types" ? "packages" : section === "access-settings" ? "settings" : section;
  useEffect(() => {
    setOpen(false);
  }, [page]);
  useEffect(() => {
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("keydown", escape);
    return () => document.removeEventListener("keydown", escape);
  }, []);
  return (
    <div className="app-shell">
      <nav aria-label={adminPanel ? "Admin header" : "Developer header"} className="app-header">
        <div className="header-brand">
          <a href={adminPanel ? "#admin" : `${portalOrigin()}/dev/`} aria-label={adminPanel ? "MyOS Admin Panel" : "MyOS Dev home"}>
            <img
              src={assetUrl("/assets/myos-logo.svg")}
              width="121"
              height="48"
              alt="MyOS"
            />
          </a>
          {adminPanel && <span className="admin-brand-label">Admin Panel</span>}
          <button
            className="mobile-menu"
            aria-label={open ? "Close navigation" : "Open navigation"}
            aria-expanded={open}
            aria-controls="dev-sidebar"
            onClick={() => setOpen(!open)}
          >
            {open ? <X size={20} /> : <Menu size={20} />}
          </button>
        </div>
        <div className="workspace-controls">
          {workspaceSelector}
          {logout ? (
            <AccountMenu key={page} accountId={accountId} imageUrl={userImageUrl} logout={logout} adminPanel={adminPanel} />
          ) : (
            <span className="account-button">
              <UserRound size={26} strokeWidth={1.5} />
            </span>
          )}
        </div>
      </nav>
      <div className="app-body">
        {open && (
          <button
            className="nav-scrim"
            aria-label="Close navigation overlay"
            onClick={() => setOpen(false)}
          />
        )}
        <aside id="dev-sidebar" className={`sidebar ${open ? "is-open" : ""}`}>
          <nav aria-label="Main navigation">
            {(adminPanel ? [{ label: "Users", href: "users", icon: Users }, { label: "Notifications", href: "notifications", icon: Bell }] : navItems)
              .filter(
                (item) =>
                  admin ||
                  !["timelines", "gas", "webhooks"].includes(item.href),
              )
              .map((item) => (
                <a
                  key={item.href}
                  href={adminPanel ? "#admin" : `#${item.href}`}
                  aria-current={
                    navigationSection === item.href
                      ? "page"
                      : undefined
                  }
                  onClick={(e) => {
                    e.preventDefault();
                    setOpen(false);
                    navigate(item.href);
                  }}
                  className={`nav-item ${navigationSection === item.href ? "active" : ""}`}
                >
                  <item.icon size={24} strokeWidth={1.5} />
                  <span>{item.label}</span>
                </a>
              ))}
          </nav>
          <PrivacySettingsButton />
        </aside>
        <main className="main-content">{children}</main>
      </div>
    </div>
  );
}
