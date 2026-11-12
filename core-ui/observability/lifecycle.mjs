import { demoVisitorReplay, demoVisitorRum } from './demo-policy.mjs';

/** Service replay requires authentication; the PROD demo also records public pages. */
export function createRumLifecycle(load, configuration, allowCurrentPage = () => true) {
  const visitorRum = demoVisitorRum(configuration);
  const visitorReplay = demoVisitorReplay(configuration);
  let desired = 'off';
  let applied = 'off';
  let desiredView;
  let appliedView;
  let sdk;
  let pending;
  const apply = () => {
    if (!sdk) return;
    const changed = desired !== applied;
    if (changed) {
      sdk.stopSessionReplayRecording();
      sdk.setTrackingConsent(desired === 'off' ? 'not-granted' : 'granted');
    }
    if (desired !== 'off' && desiredView && (changed || desiredView.key !== appliedView)) {
      sdk.startView({ name: desiredView.name, context: { collection: desired } });
      appliedView = desiredView.key;
    }
    // An existing RUM cookie can retain the prior release's no-replay sampling.
    // Upgrade it through the SDK only for eligible pages with 100% replay.
    if (changed && (desired === 'service' || desired === 'visitor' && visitorReplay)) sdk.startSessionReplayRecording(
      configuration.sessionReplaySampleRate === 100 ? { force: true } : undefined);
    if (desired === 'off') appliedView = undefined;
    applied = desired;
  };
  return {
    reportError(error) {
      // Never load or queue telemetry for an ineligible session.
      if (!sdk || desired === 'off' || applied !== desired || !allowCurrentPage()) return;
      try { sdk.addError(error); } catch { /* Reporting cannot replace the application error. */ }
    },
    update({ authenticated, publicPage, analytics, view }) {
      desired = authenticated ? 'service' : publicPage && (analytics === true || visitorRum) ? 'visitor' : 'off';
      desiredView = view;
      apply();
      if (sdk || desired === 'off') return Promise.resolve();
      pending ??= load().then(api => {
        sdk = api;
        const sanitize = configuration.beforeSend;
        api.init({ ...configuration,
          trackingConsent: 'not-granted', startSessionReplayRecordingManually: true,
          beforeSend(event, context) {
            const collection = event.context?.collection;
            // Consent withdrawal emits a final view synchronously. The SDK cannot
            // discard views, so sanitize them even after eligibility has ended.
            if (sanitize?.(event, context) === false) return false;
            if (event.type !== 'view' && (desired === 'off' || !allowCurrentPage())) return false;
            event.context = collection === 'service' || collection === 'visitor' ? { collection } : {};
            return true;
          },
        });
        apply();
      }).catch(() => {
        // Observability cannot prevent rendering, authentication or application work.
        pending = undefined;
        sdk = undefined;
      });
      return pending;
    },
  };
}
