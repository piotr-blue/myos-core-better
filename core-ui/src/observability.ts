import type { RumInitConfiguration } from '@datadog/browser-rum';
import { browserObservability } from '../observability/browser.mjs';

declare const __MYOS_RUM_CONFIG__: RumInitConfiguration | null;
export const observability = browserObservability(
  typeof __MYOS_RUM_CONFIG__ === 'undefined' ? null : __MYOS_RUM_CONFIG__,
  () => import('@datadog/browser-rum').then(({ datadogRum }) => datadogRum),
);
