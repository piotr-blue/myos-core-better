import type { RumInitConfiguration, datadogRum } from '@datadog/browser-rum';
export function createRumLifecycle(load: () => Promise<typeof datadogRum>, configuration: RumInitConfiguration, allowCurrentPage?: () => boolean): {
  reportError(error: unknown): void;
  update(state: { authenticated: boolean; publicPage: boolean; analytics: boolean | null; view?: { key: string; name: string } }): Promise<void>;
};
