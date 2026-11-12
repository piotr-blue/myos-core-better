import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname } from 'node:path';
import { build } from 'esbuild';

const require = createRequire(import.meta.url);
const compiled = (await build({
  entryPoints: ['src/resource-pages.tsx'],
  bundle: true,
  write: false,
  format: 'esm',
  platform: 'browser',
  plugins: [{
    name: 'document-processing-harness',
    setup(build) {
      build.onLoad({ filter: /\/resource-pages\.tsx$/ }, async ({ path }) => ({
        contents: await readFile(path, 'utf8'),
        loader: 'tsx',
        resolveDir: dirname(path),
      }));
      build.onResolve({ filter: /^react$/ }, () => ({ path: 'react-hooks', namespace: 'processing-test' }));
      build.onLoad({ filter: /.*/, namespace: 'processing-test' }, () => ({
        contents: `export * from ${JSON.stringify(require.resolve('react'))};
          export { default } from ${JSON.stringify(require.resolve('react'))};
          export const useCallback = callback => callback;
          export const useEffect = () => {};
          export const useState = initial => globalThis.documentProcessingState.useState(initial);`,
        resolveDir: process.cwd(),
      }));
    },
  }],
})).outputFiles[0].text;

globalThis.localStorage = { getItem: () => null };
globalThis.sessionStorage = { getItem: () => null, setItem() {}, removeItem() {} };
const { DocumentDetail } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);

function renderDocument(processing, control = 'RUNNING') {
  const values = [
    { sessionId: 'root', name: 'Root', documentId: 'source', control, processing, channels: [] },
    '', undefined, [], [], [], '', false, false,
  ];
  let cursor = 0;
  globalThis.documentProcessingState = { useState: () => [values[cursor++], () => {}] };
  return DocumentDetail({ id: 'root', navigate: () => {}, operations: () => {}, accountId: 'account' });
}

function findElement(element, predicate) {
  if (!element || typeof element !== 'object') return undefined;
  if (predicate(element)) return element;
  return [element.props?.children].flat(Infinity)
    .map(child => findElement(child, predicate))
    .find(Boolean);
}

function findElements(element, predicate) {
  if (!element || typeof element !== 'object') return [];
  return [
    ...(predicate(element) ? [element] : []),
    ...[element.props?.children].flat(Infinity).flatMap(child => findElements(child, predicate)),
  ];
}

function findComponent(element, name) {
  return findElement(element, child => child.type?.name === name);
}

function textOf(element) {
  if (element === null || element === undefined || typeof element === 'boolean') return '';
  if (typeof element !== 'object') return String(element);
  return [element.props?.children].flat(Infinity).map(textOf).join('');
}

for (const state of ['ERROR', 'HOST_INCOMPLETE']) test(`${state} processing renders a unified reason as an alert`, () => {
  const reason = {
    code: 'EXECUTION_FAILED',
    message: 'The operation could not be applied.',
    phase: 'execute',
    details: { gasEvaluation: 'gas-evaluation-1' },
  };
  const rendered = renderDocument({
    state, nativeState: state, checkedBoundary: 42, retryable: true, reason,
  });

  const alert = findElement(rendered, child => child.props?.role === 'alert');
  assert.equal(alert.props.className, 'error');
  assert.equal(findComponent(alert, 'CodeViewer').props.value, reason);
  assert.equal(findElement(rendered, child => child.props?.role === 'status'), undefined);
});

test('document processing formats gas waits from reason details', () => {
  const rendered = renderDocument({
    state: 'WAITING', nativeState: 'WAITING', checkedBoundary: 43, retryable: false,
    reason: {
      code: 'WAITING_ACCOUNT_GAS',
      message: 'Awaiting account gas.',
      details: { required: 1200, available: 350 },
    },
  });

  const notice = findElement(rendered, child => child.props?.role === 'status');
  assert.equal(notice.props.className, 'notice');
  assert.match(textOf(notice), /Waiting for gas: 1200 required, 350 available/);
});

test('paused processing links dependencies from reason details', () => {
  const rendered = renderDocument({
    state: 'PAUSED', nativeState: 'WAITING', checkedBoundary: 44, retryable: false,
    reason: {
      code: 'DEPENDENCY_PAUSED',
      message: 'A dependency is paused.',
      details: { timeline: 'timeline#1', documents: ['root', 'child#1'] },
    },
  });

  const notice = findElement(rendered, child => child.props?.role === 'status');
  assert.match(textOf(notice), /^Paused/);
  assert.deepEqual(
    findElements(notice, child => child.type === 'a').map(link => link.props.href),
    ['#timelines/timeline%231', '#documents/child%231'],
  );
});

test('retry sends the gas evaluation retained in reason details', async () => {
  const gasEvaluation = 'gas-evaluation-2';
  const processing = {
    state: 'ERROR', nativeState: 'ERROR', checkedBoundary: 45, retryable: true,
    reason: {
      code: 'EXECUTION_FAILED',
      message: 'Charged execution failed.',
      details: { gasEvaluation },
    },
  };
  const requests = [];
  globalThis.fetch = async (path, options = {}) => {
    requests.push({ path, options });
    if (options.method === 'POST') return Response.json({});
    if (path === '/api/blue/v1/documents/root') {
      return Response.json({ sessionId: 'root', documentId: 'source', control: 'RUNNING', processing });
    }
    if (path === '/api/blue/v1/contents/source') return Response.json({ name: 'Root' });
    if (path === '/api/blue/v1/documents/root/timelines?limit=100') {
      return Response.json({ items: [], hasMore: false });
    }
    throw new Error(`Unexpected request: ${path}`);
  };
  const rendered = renderDocument(processing);
  const retry = findElement(rendered, child => child.type === 'button' && textOf(child).startsWith('Retry processing'));

  await retry.props.onClick();

  assert.equal(requests[0].path, '/api/blue/v1/documents/root/retry');
  assert.deepEqual(JSON.parse(requests[0].options.body), { gasEvaluation });
  assert.match(textOf(retry), /gas may be charged again/);
});
