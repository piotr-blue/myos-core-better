export const preferenceKey: string;
export const preferenceEvent: string;
export function readAnalyticsPreference(storage?: Storage, now?: number): boolean | null;
export function saveAnalyticsPreference(analytics: boolean, storage?: Storage, now?: number): void;
