import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

test('a public SDK build removes deleted client outputs before packaging', async () => {
  const root = fileURLToPath(new URL('../', import.meta.url));
  const fixture = await mkdtemp(path.join(tmpdir(), 'myos-sdk-clean-build-'));
  try {
    await mkdir(path.join(fixture, 'scripts'));
    await cp(path.join(root, 'scripts/build.mjs'), path.join(fixture, 'scripts/build.mjs'));
    await cp(path.join(root, 'tsconfig.json'), path.join(fixture, 'tsconfig.json'));
    await symlink(path.join(root, 'node_modules'), path.join(fixture, 'node_modules'), 'dir');
    await writeFile(path.join(fixture, 'package.json'), JSON.stringify({ type: 'module' }));
    await writeFile(path.join(fixture, 'index.ts'), 'export const publicValue: number = 60;\n');
    const obsolete = path.join(fixture, 'dist/generated/apis');
    await mkdir(obsolete, { recursive: true });
    for (const extension of ['js', 'd.ts']) await writeFile(path.join(obsolete, `RemovedApi.${extension}`), 'obsolete private client');
    execFileSync(process.execPath, [path.join(fixture, 'scripts/build.mjs')], { stdio: 'pipe', timeout: 30_000 });
    assert.deepEqual((await readdir(path.join(fixture, 'dist'))).sort(), ['index.d.ts', 'index.js']);
    assert.match(await readFile(path.join(fixture, 'dist/index.js'), 'utf8'), /publicValue = 60/);
    assert.match(await readFile(path.join(fixture, 'dist/index.d.ts'), 'utf8'), /publicValue: number/);
  } finally {
    await rm(fixture, { recursive: true, force: true });
  }
});
