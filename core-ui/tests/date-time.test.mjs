import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
const directory = await mkdtemp(join(tmpdir(), 'myos-date-test-'));
const output = join(directory, 'date-time.mjs');
await build({ entryPoints: [fileURLToPath(new URL('../src/date-time.ts', import.meta.url))], bundle: true, format: 'esm', platform: 'node', outfile: output });
const { formatLastChange } = await import(pathToFileURL(output));
await rm(directory, { recursive: true });

test('document dates use local calendar days and twelve-hour times', () => {
  const now = new Date(2026, 9, 4, 23, 30);
  assert.equal(formatLastChange(new Date(2026, 9, 4, 20, 42).toISOString(), now), '8:42 PM');
  assert.equal(formatLastChange(new Date(2026, 9, 4, 0, 5).toISOString(), now), '12:05 AM');
  assert.equal(formatLastChange(new Date(2026, 9, 3, 23, 59).toISOString(), now), 'Oct 3');
  assert.equal(formatLastChange(new Date(2025, 9, 4, 20, 42).toISOString(), now), 'Oct 4');
});

test('missing and invalid dates remain unknown', () => {
  for (const value of [null, undefined, '', 'invalid']) assert.equal(formatLastChange(value), '—');
});
