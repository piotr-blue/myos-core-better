import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Configuration, SystemApi } from '../dist/index.js';

if (!process.env.MYOS_URL) throw new Error('Set MYOS_URL to the backend origin, without /api/blue/v1');
const base = new URL(process.env.MYOS_URL);
if (base.username || base.password || base.search || base.hash || base.pathname !== '/') {
  throw new Error('MYOS_URL must be an origin, without credentials, path, query or fragment');
}
const api = new SystemApi(new Configuration({ basePath: base.origin }));
const contract = await api.getOpenApi({
  headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(20_000),
});
const expected = JSON.parse(await readFile(new URL('../../../core-ui/public/openapi.json', import.meta.url), 'utf8'));
try { assert.deepEqual(contract, expected); } catch {
  throw new Error('Backend Swagger differs from the committed Core Swagger. Align the backend and SDK revision before using the client.');
}
console.log(`PASS: ${base.origin} serves the SDK source contract (read-only; no authenticated flow tested)`);
