import { SignIn } from "./better-auth";
import "./better-auth.css";
import { assetUrl } from "./portal-location";
import { apiUrl, portalLocation, portalOrigin, sharedSignIn } from "./portal-location";
import { useEffect, useState } from "react";
import { BriefcaseBusiness } from "lucide-react";
import { PrivacySettingsButton } from "./privacy-preferences";

type PortalConfig = { sharedPortalOrigin: string; businessOrigin: string };
let configuration: Promise<PortalConfig> | undefined;
export function usePortalConfig() {
  const [config, setConfig] = useState<PortalConfig>();
  useEffect(() => {
    let active = true;
    configuration ||= fetch(apiUrl("/api/config")).then(async response => {
      if (!response.ok) throw new Error("Portal configuration unavailable");
      return await response.json() as PortalConfig;
    });
    configuration.then(value => { if (active) setConfig(value); }).catch(() => { configuration = undefined; });
    return () => { active = false; };
  }, []);
  return config;
}
export function PortalSignIn({ returnTo = "/#documents", redirect = false }: { returnTo?: string; redirect?: boolean }) {
  return <SignIn onSignedIn={() => location.reload()} />;
}

export function BusinessSwitch({ accountId }: { accountId: string }) {
  const config = usePortalConfig();
  if (!config?.businessOrigin) return null;
  const link = new URL("/business/", portalOrigin());
  return <a role="menuitem" className="account-menu-item" href={link.href}><BriefcaseBusiness size={20} strokeWidth={1.5} aria-hidden="true" />MyOS Business</a>;
}
