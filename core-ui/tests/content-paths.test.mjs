import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { build } from 'esbuild';

const compiled = (await build({
  stdin: {
    contents: 'export { api } from "./src/api"; export * from "./src/content-paths";',
    resolveDir: process.cwd(),
  },
  bundle: true, write: false, format: 'esm',
})).outputFiles[0].text;
const { api, contentPath } = await import(
  'data:text/javascript;base64,' + Buffer.from(compiled).toString('base64')
);
const ordinaryId = 'G3ntniy2bRan6SrTxSqhGw82jXKTGVWZkNy3ABe8bH8z';

async function observeRequests(t) {
  const received = [];
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://localhost');
    received.push({
      segments: url.pathname.split('/').map(decodeURIComponent),
      query: Object.fromEntries(url.searchParams),
    });
    response.setHeader('Content-Type', 'application/json');
    response.end('{"name":"Available content"}');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const nativeFetch = globalThis.fetch;
  globalThis.fetch = (path, options) => nativeFetch(
    new URL(path, `http://127.0.0.1:${server.address().port}`), options,
  );
  t.after(async () => {
    globalThis.fetch = nativeFetch;
    await new Promise(resolve => server.close(resolve));
  });
  return received;
}

test('ordinary content reads use the direct route and full value', async t => {
  const received = await observeRequests(t);
  assert.deepEqual(await api(contentPath(ordinaryId)), { name: 'Available content' });
  assert.deepEqual(received, [{
    segments: ['', 'api', 'blue', 'v1', 'contents', ordinaryId], query: {},
  }]);
});

test('cyclic content member IDs reach the direct endpoint intact', async t => {
  const received = await observeRequests(t);
  for (const member of [0, 12]) {
    const id = `${ordinaryId}#${member}`;
    await api(contentPath(id));
    assert.deepEqual(received.at(-1), {
      segments: ['', 'api', 'blue', 'v1', 'contents', id], query: {},
    });
  }
});
