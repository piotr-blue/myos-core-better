import { portalEnvironment } from '../portal-origins.mjs';
import { createRumLifecycle } from './lifecycle.mjs';
import { rumConfiguration, safeViewName } from './privacy.mjs';
import { preferenceEvent, preferenceKey, readAnalyticsPreference } from './preferences.mjs';

/** Authentication is supplied by verified session discovery, never by browser storage. */
export function browserObservability(configuration, load) {
  let sessionExpiresAt = 0;
  let expirationTimer;
  let publicPage = false;
  let analytics = readAnalyticsPreference();
  const allowedOrigins = portalEnvironment(configuration?.env)?.origins || [];
  const origin = globalThis.location.origin;
  const authenticated = () => sessionExpiresAt > Date.now();
  const currentUrl = () => new URL(globalThis.location.href || origin);
  const allowCurrentPage = () => {
    const url = currentUrl();
    return !/^\/sign-in\/*$/.test(url.pathname)
      && !(/^\/developers\/app\/?$/.test(url.pathname) && /^#(?:login|signup|confirm|forgot|reset)(?:[/?]|$)/.test(url.hash));
  };
  const lifecycle = configuration && allowedOrigins.includes(origin)
    ? createRumLifecycle(() => load().then(api => {
      // A verified session can expire while the optional SDK is downloading.
      void update();
      return api;
    }), rumConfiguration(configuration, origin, () => authenticated() && allowCurrentPage()), allowCurrentPage) : undefined;
  const update = () => {
    const allowed = allowCurrentPage();
    const url = currentUrl();
    return lifecycle?.update({ authenticated: authenticated() && allowed, publicPage: publicPage && allowed, analytics,
      view: { key: url.pathname + (/^\/developers\/app\/?$/.test(url.pathname) ? url.hash.split('?')[0] : ''), name: safeViewName(url.href, origin) } });
  };
  const ended = () => {
    sessionExpiresAt = 0;
    clearTimeout(expirationTimer);
    void update();
  };
  const expire = () => {
    clearTimeout(expirationTimer);
    if (!authenticated()) { ended(); return; }
    // The backend's verified epoch-millisecond deadline is authoritative. Chunk
    // long delays to avoid the browser's signed 32-bit timeout overflow.
    expirationTimer = setTimeout(expire, Math.min(sessionExpiresAt - Date.now(), 2_147_483_647));
  };
  if (lifecycle) {
    globalThis.window.addEventListener(preferenceEvent, event => { analytics = event.detail === true; void update(); });
    globalThis.window.addEventListener('storage', event => {
      if (event.key === preferenceKey || event.key === null) { analytics = readAnalyticsPreference(); void update(); }
    });
    globalThis.window.addEventListener('myos-session-ended', ended);
    globalThis.window.addEventListener('myos-session-changed', ended);
    globalThis.window.addEventListener('focus', () => { if (!authenticated()) ended(); });
    globalThis.window.addEventListener('pageshow', () => { if (!authenticated()) ended(); });
    globalThis.window.addEventListener('visibilitychange', () => { if (!authenticated()) ended(); });
    const channel = new globalThis.BroadcastChannel('myos-session');
    channel.onmessage = event => { if (event.data === 'signed-out') ended(); };
  }
  return {
    reportError(error) { if (!authenticated()) ended(); lifecycle?.reportError(error); },
    setAuthenticated(value, expiresAt) {
      sessionExpiresAt = value && Number.isSafeInteger(expiresAt) && expiresAt > Date.now() ? expiresAt : 0;
      if (lifecycle) expire();
      void update();
    },
    setPublicPage(value) { publicPage = value; void update(); },
    syncView() { void update(); },
  };
}
