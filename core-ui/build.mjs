import { portalBuildConfig } from "./portal-build-config.mjs";
import { rumBuildConfig } from './observability/build-config.mjs';
import { execFileSync } from 'node:child_process';
const portal = portalBuildConfig();
import { swaggerAssets } from './swagger-assets.mjs';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
process.chdir(fileURLToPath(new URL('.', import.meta.url)));
import { mkdir, cp, readFile, writeFile, rm } from 'node:fs/promises';
await rm('dist', { recursive: true, force: true });
await mkdir('dist', { recursive: true });
await cp('public', 'dist', { recursive: true });
await mkdir('dist/swagger-assets', { recursive: true });
for (const asset of swaggerAssets) {
  await cp(`node_modules/swagger-ui-dist/${asset}`, `dist/swagger-assets/${asset}`);
}
const rum = rumBuildConfig(process.env, { version: process.env.MYOS_RUM_ENABLED === 'true' ? execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() : '', origin: portal.web, service: 'myos-core-console' });
const result = await build({ absWorkingDir: process.cwd(), entryPoints: ['src/main.tsx'], bundle: true, format: 'esm', splitting: true, outdir: 'dist', entryNames: 'app-[hash]', chunkNames: 'chunks/[name]-[hash]', metafile: true, minify: true, external: ['/assets/*'], sourcemap: 'external', jsx: 'automatic', target: ['es2022'], define: { 'process.env.NODE_ENV': '"production"', '__MYOS_PORTAL_CONFIG__': JSON.stringify(portal), '__MYOS_RUM_CONFIG__': JSON.stringify(rum) }, logLevel: 'info' });
const [script, output] = Object.entries(result.metafile.outputs).find(([, output]) => output.entryPoint === 'src/main.tsx');
const html = (await readFile('index.html', 'utf8')).replace('<script type="module" src="/src/main.tsx"></script>', `<link rel="stylesheet" href="${portal.base}${output.cssBundle.slice(5)}"><script type="module" src="${portal.base}${script.slice(5)}"></script>`);
await writeFile('dist/index.html', html);

if (portal.base !== "/") {
  const css = await readFile(output.cssBundle, "utf8");
  await writeFile(output.cssBundle, css.replaceAll("/assets/", portal.base + "assets/"));
}

// Swagger remains a static reference; its contract request goes straight to Core.
const swaggerHtml = await readFile('dist/swagger.html', 'utf8');
await writeFile('dist/swagger.html', swaggerHtml.replaceAll('="/', `="${portal.base}`));
const swaggerInit = await readFile('dist/swagger-init.js', 'utf8');
await writeFile('dist/swagger-init.js', swaggerInit.replace("'/api/blue/v1/openapi.json'", JSON.stringify(portal.api + '/api/blue/v1/openapi.json')));
