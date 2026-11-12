/** Reviewed frontend aliases and unchanged API origins for the frontend cutover. */
const environments = Object.freeze({
  dev: Object.freeze({
    origins: Object.freeze(['https://dev.myos.blue', 'https://dev.beta.myos.blue']),
    coreApi: 'https://core.api.dev.beta.myos.blue',
    businessApi: 'https://business.api.dev.beta.myos.blue',
  }),
  prod: Object.freeze({
    origins: Object.freeze(['https://myos.blue', 'https://beta.myos.blue']),
    coreApi: 'https://core.api.beta.myos.blue',
    businessApi: 'https://business.api.beta.myos.blue',
  }),
});

export function portalEnvironment(environment) { return environments[environment]; }
export function portalBoundary(origin) {
  return Object.values(environments).find(value => value.origins.includes(origin));
}

/** Keep in-app links on an approved current alias until the manual domain move. */
export function currentPortalOrigin(configured, current) {
  return portalBoundary(configured)?.origins.includes(current) ? current : configured || current;
}
