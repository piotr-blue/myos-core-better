import { currentPortalOrigin } from '../portal-origins.mjs';

declare const __MYOS_PORTAL_CONFIG__: { base: string; api: string; web: string; mcp?: string; mcpClientId?: string; dotsCimd?: boolean };
export const portalLocation = typeof __MYOS_PORTAL_CONFIG__ === 'undefined'
  ? { base: '/', api: '', web: '', mcp: undefined, mcpClientId: undefined, dotsCimd: false } : __MYOS_PORTAL_CONFIG__;
export function apiUrl(path: string) {
  if (!path.startsWith('/api/') || path.includes('\\') || path.includes('#')) throw new Error('Invalid API path');
  return portalLocation.api + path;
}
export function assetUrl(path: string) { return portalLocation.base + path.replace(/^\//, ''); }
export function portalOrigin() { return currentPortalOrigin(portalLocation.web, location.origin); }
export function sharedSignIn(returnTo = location.pathname + location.hash, mode = 'login') {
  const url = new URL('/sign-in', portalOrigin());
  url.searchParams.set('returnTo', returnTo);
  if (mode !== 'login') url.searchParams.set('mode', mode);
  return url.href;
}
