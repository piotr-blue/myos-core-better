import type { RumInitConfiguration, datadogRum } from '@datadog/browser-rum';
export function browserObservability(configuration: RumInitConfiguration | null, load: () => Promise<typeof datadogRum>): {
  reportError(error: unknown): void;
  setAuthenticated(value: boolean, sessionExpiresAt?: number): void;
  setPublicPage(value: boolean): void;
  syncView(): void;
};
