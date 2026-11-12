// Shared by Core and Business on the same portal origin; never contains identity.
export const preferenceKey = 'myos:privacy:v1';
export const preferenceEvent = 'myos-privacy-changed';
const lifetime = 180 * 24 * 60 * 60 * 1000;
export function readAnalyticsPreference(storage, now = Date.now()) {
  try {
    storage ??= globalThis.window.localStorage;
    const value = JSON.parse(storage.getItem(preferenceKey) || 'null');
    return typeof value?.analytics === 'boolean' && Number.isFinite(value.expiresAt) && value.expiresAt > now && value.expiresAt <= now + lifetime
      ? value.analytics : null;
  } catch { return null; }
}
export function saveAnalyticsPreference(analytics, storage, now = Date.now()) {
  try { (storage ?? globalThis.window.localStorage).setItem(preferenceKey, JSON.stringify({ analytics, expiresAt: now + lifetime })); }
  catch { /* This page still honors the choice when browser storage is unavailable. */ }
  globalThis.window.dispatchEvent(new globalThis.CustomEvent(preferenceEvent, { detail: analytics }));
}
