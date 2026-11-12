import { portalBoundary } from '../portal-origins.mjs';

const pages = new Set(['/', '/developers', '/developers/', '/developers/app/', '/sign-in', '/business/']);
const apiSegments = new Set(['api', 'blue', 'v1', 'auth', 'login', 'logout', 'signup', 'confirm', 'forgot', 'reset', 'me', 'config', 'accounts', 'users', 'documents', 'sessions', 'timelines', 'events', 'messages', 'operations', 'state', 'history', 'info', 'entries', 'memberships', 'invitations', 'keys', 'webhooks', 'settings', 'profile', 'avatar', 'image', 'activity', 'packages', 'health', 'live']);
const errorTypes = new Set(['Error', 'TypeError', 'ReferenceError', 'RangeError', 'SyntaxError', 'URIError', 'EvalError', 'AggregateError']);
const consoleViews = new Set(['documents', 'timelines', 'events', 'webhooks', 'gas', 'accounts', 'settings', 'packages', 'examples', 'explorer', 'invitation']);

export function safeViewName(value, origin) {
  try {
    const url = new URL(value, origin);
    if (url.pathname === '/developers/app/') {
      const route = url.hash.slice(1).split(/[/?]/)[0];
      return '/developers/app/' + (consoleViews.has(route) ? route : 'other');
    }
    return new URL(safeUrl(value, origin)).pathname;
  } catch { return '/other'; }
}

/** No query, fragment, userinfo, arbitrary host, document ID or user-controlled slug. */
export function safeUrl(value, origin) {
  try {
    const url = new URL(value, origin);
    const boundary = portalBoundary(origin);
    const hosts = new Set(boundary ? [...boundary.origins, boundary.coreApi, boundary.businessApi]
      : [origin, origin.replace('://', '://core.api.'), origin.replace('://', '://business.api.')]);
    if (!hosts.has(url.origin)) return 'https://external.invalid/redacted';
    let path = url.pathname;
    if (!pages.has(path)) {
      if (!/^\/(?:developers\/app\/)?(?:assets\/[^/]+|chunks\/[A-Za-z0-9_-]+|app-[A-Za-z0-9_-]+)\.(?:js|css)$/.test(path)) {
        path = path.split('/').map(part => apiSegments.has(part) || part === '' ? part : ':id').join('/');
      }
    }
    return url.origin + path;
  } catch { return origin + '/redacted'; }
}

/** Retain only generated JS asset locations needed for source-map resolution. */
export function safeStack(value, origin) {
  return String(value || '').split('\n').flatMap(line => {
    const match = line.match(/(https:\/\/[^\s()]+\.js):(\d+):(\d+)\)?$/);
    if (!match) return [];
    const url = safeUrl(match[1], origin);
    if (!url.startsWith(origin + '/') || !url.endsWith('.js')) return [];
    return [`    at ${url}:${match[2]}:${match[3]}`];
  }).slice(0, 40).join('\n');
}

/** Use only the SDK's documented editable fields; do not attach application data. */
export function beforeSend(origin) {
  return event => {
    event.context = {};
    if (event.view) {
      event.view.name = safeViewName(event.view.url, origin);
      event.view.url = safeUrl(event.view.url, origin);
      event.view.referrer = event.view.referrer ? safeUrl(event.view.referrer, origin) : '';
      if (event.view.performance?.lcp?.resource_url)
        event.view.performance.lcp.resource_url = safeUrl(event.view.performance.lcp.resource_url, origin);
    }
    if (event.resource) event.resource.url = safeUrl(event.resource.url, origin);
    if (event.action?.target) event.action.target.name = 'interaction';
    if (event.error) {
      // Custom error names/causes may contain content and are not all editable by beforeSend.
      if (event.error.type && !errorTypes.has(event.error.type)) return false;
      if (event.error.causes?.length) return false;
      event.error.message = event.error.type || 'Browser error';
      const frames = safeStack(event.error.stack, origin);
      event.error.stack = frames ? `${event.error.type || 'Error'}\n${frames}` : '';
      if (event.error.resource) event.error.resource.url = safeUrl(event.error.resource.url, origin);
    }
    for (const script of event.long_task?.scripts || []) {
      if (script.source_url) script.source_url = safeUrl(script.source_url, origin);
      if (script.invoker) script.invoker = 'script';
    }
    return true;
  };
}

export function rumConfiguration(publicConfig, origin, isAuthenticated = () => false) {
  const replayPrivacyLevel = publicConfig.defaultPrivacyLevel ?? 'mask';
  if (!['mask', 'allow'].includes(replayPrivacyLevel)) throw new Error('Replay privacy must be mask or allow');
  return {
    ...publicConfig,
    defaultPrivacyLevel: replayPrivacyLevel, enablePrivacyForActionName: true,
    trackResources: true, trackUserInteractions: true, trackLongTasks: true,
    trackViewsManually: true,
    trackingConsent: 'not-granted', startSessionReplayRecordingManually: true,
    allowedTracingUrls: [{
      match(value) {
        if (!isAuthenticated()) return false;
        try {
          const url = new URL(value);
          return url.origin === (portalBoundary(origin)?.coreApi || origin.replace('://', '://core.api.'))
            && !url.username && !url.password && url.pathname.startsWith('/api/blue/v1/');
        } catch { return false; }
      },
      propagatorTypes: ['tracecontext'],
    }],
    traceSampleRate: 100, traceContextInjection: 'sampled', propagateTraceBaggage: false,
    telemetrySampleRate: 0,
    beforeSend: beforeSend(origin),
  };
}
