import { execFileSync } from 'node:child_process';
import { rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
// TypeScript does not remove outputs when a source file is deleted.
await rm(new URL('dist/', root), { recursive: true, force: true });
execFileSync(process.execPath, [fileURLToPath(new URL('node_modules/typescript/bin/tsc', root)),
  '-p', fileURLToPath(new URL('tsconfig.json', root))], { cwd: fileURLToPath(root), stdio: 'inherit' });
