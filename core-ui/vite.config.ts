import { portalBuildConfig } from "./portal-build-config.mjs";
const portal = portalBuildConfig();
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { swaggerAssets } from './swagger-assets.mjs';

export default defineConfig({
  base: portal.base,
  define: { __MYOS_PORTAL_CONFIG__: JSON.stringify(portal), __MYOS_RUM_CONFIG__: 'null' },
  plugins: [react(), {
    name: 'local-swagger-assets',
    configureServer(server) {
      server.middlewares.use('/swagger-assets', async (request, response, next) => {
        const asset = request.url?.split('?')[0].slice(1);
        if (!swaggerAssets.includes(asset)) {
          response.statusCode = 404;
          response.end();
          return;
        }
        try {
          const content = await readFile(new URL(`./node_modules/swagger-ui-dist/${asset}`, import.meta.url));
          const type = asset.endsWith('.css') ? 'text/css' : asset.endsWith('.js') ? 'text/javascript' : 'text/plain';
          response.setHeader('Content-Type', `${type}; charset=utf-8`);
          response.end(content);
        } catch (error) {
          next(error);
        }
      });
    },
  }],
  server: {
    host: '127.0.0.1', strictPort: true,
    port: Number(process.env.CORE_UI_PORT || '5173'),
    ...(process.env.CORE_TLS_CERT && process.env.CORE_TLS_KEY ? {
      https: { cert: readFileSync(process.env.CORE_TLS_CERT), key: readFileSync(process.env.CORE_TLS_KEY) },
    } : {}),
    proxy: Object.fromEntries(['/api', '/health', '/timeline-provider/'].map(path => [path, process.env.CORE_API_URL || 'http://127.0.0.1:8088'])),
  },
});
