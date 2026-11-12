// Temporary DEV/PROD demo policy, authorized with consent collected outside the UI.
// Set this flag to false and rebuild after the demo. Stored banner choices are
// never changed. PROD also records public visits; DEV replay requires authentication.
const demoVisitorAnalytics = true;

export const demoVisitorRum = configuration => demoVisitorAnalytics && ['dev', 'prod'].includes(configuration?.env);
export const demoVisitorReplay = configuration => demoVisitorRum(configuration) && configuration?.env === 'prod';
