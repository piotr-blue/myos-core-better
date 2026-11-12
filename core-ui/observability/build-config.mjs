import { portalEnvironment } from '../portal-origins.mjs';

/** Public browser settings only. API/application keys must never enter this object. */
export function rumBuildConfig(env, { version, origin, service }) {
  if (env.MYOS_RUM_ENABLED && !['true', 'false'].includes(env.MYOS_RUM_ENABLED)) throw new Error('MYOS_RUM_ENABLED must be true or false');
  if (env.MYOS_RUM_ENABLED !== 'true') return null;
  const replayPrivacyLevel = env.MYOS_RUM_REPLAY_PRIVACY_LEVEL ?? 'mask';
  if (!['mask', 'allow'].includes(replayPrivacyLevel)) throw new Error('Replay privacy must be mask or allow');
  if (!['dev', 'prod'].includes(env.MYOS_RUM_ENV)) throw new Error('RUM requires dev or prod');
  if (!/^[a-f0-9]{40}$/.test(version)) throw new Error('RUM requires the actual full source revision');
  if (!portalEnvironment(env.MYOS_RUM_ENV).origins.includes(origin)) throw new Error('RUM environment and portal origin differ');
  if (!['myos-core-web', 'myos-core-console'].includes(service)) throw new Error('Unknown RUM service');
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(env.MYOS_RUM_APPLICATION_ID || '')) throw new Error('Missing RUM application ID');
  if (!/^[A-Za-z0-9_-]{20,200}$/.test(env.MYOS_RUM_CLIENT_TOKEN || '')) throw new Error('Missing public RUM client token');
  return {
    applicationId: env.MYOS_RUM_APPLICATION_ID,
    clientToken: env.MYOS_RUM_CLIENT_TOKEN,
    site: 'datadoghq.eu', service, env: env.MYOS_RUM_ENV, version,
    sessionSampleRate: 100,
    sessionReplaySampleRate: 100,
    defaultPrivacyLevel: replayPrivacyLevel,
  };
}
