import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';

const publicFile = name => readFile(new URL(`../public/${name}`, import.meta.url), 'utf8');

test('Swagger uses local scripts and styles without inline JavaScript', async () => {
  const html = await publicFile('swagger.html');
  assert.doesNotMatch(html, /(?:src|href)=["'](?:https?:)?\/\//i);
  assert.doesNotMatch(html, /\son\w+\s*=/i);
  const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)];
  assert.equal(scripts.length, 3);
  assert.deepEqual(scripts.map(([, attributes]) => attributes.match(/src="([^"]+)"/)[1]), [
    '/swagger-assets/swagger-ui-bundle.js',
    '/swagger-assets/swagger-ui-standalone-preset.js',
    '/swagger-init.js',
  ]);
  assert.ok(scripts.every(([, , body]) => body.trim() === ''));
  assert.match(html, /href="\/swagger-assets\/swagger-ui.css"/);
});

test('Swagger reads the runtime contract and cannot submit operations or persist credentials', async () => {
  let config;
  const SwaggerUIBundle = options => { config = options; };
  SwaggerUIBundle.presets = { apis: 'apis' };
  const window = {};
  runInNewContext(await publicFile('swagger-init.js'), { window, SwaggerUIBundle, SwaggerUIStandalonePreset: 'standalone' });
  window.onload();
  assert.equal(config.url, '/api/blue/v1/openapi.json');
  assert.equal(config.deepLinking, true);
  assert.equal(config.filter, true);
  assert.equal(config.docExpansion, 'none');
  assert.equal(config.validatorUrl, null);
  assert.deepEqual(Array.from(config.supportedSubmitMethods), []);
  assert.equal(config.tryItOutEnabled, false);
  assert.equal(config.persistAuthorization, false);
  assert.equal(config.requestInterceptor, undefined);
  assert.equal(config.queryConfigEnabled, false);
});

test('Vite serves pinned Swagger assets with their real bytes and content types', async () => {
  const { createServer: createViteServer } = await import('vite');
  const { createServer: createHttpServer } = await import('node:http');
  const { fileURLToPath } = await import('node:url');
  const vite = await createViteServer({
    root: fileURLToPath(new URL('..', import.meta.url)),
    server: { middlewareMode: true, hmr: false, watch: null },
    optimizeDeps: { noDiscovery: true, include: [] },
  });
  const server = createHttpServer(vite.middlewares);
  try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    for (const [name, type] of [
      ['swagger-ui.css', 'text/css'],
      ['swagger-ui-bundle.js', 'text/javascript'],
      ['swagger-ui-standalone-preset.js', 'text/javascript'],
    ]) {
      const response = await fetch(`${origin}/swagger-assets/${name}`);
      assert.equal(response.status, 200);
      assert.ok(response.headers.get('content-type')?.startsWith(type), `${name} must be ${type}`);
      assert.equal(await response.text(), await readFile(new URL(`../node_modules/swagger-ui-dist/${name}`, import.meta.url), 'utf8'));
    }
    assert.equal((await fetch(`${origin}/swagger-assets/missing.js`)).status, 404);
  } finally {
    await new Promise(resolve => server.close(resolve));
    await vite.close();
  }
});
