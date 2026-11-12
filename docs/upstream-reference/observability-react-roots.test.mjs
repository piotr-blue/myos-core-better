import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

// Exercise the real public entry point, substituting only page content and the
// existing reporting helper. The SDK privacy/lifecycle fixtures cover that helper.
const compiled = (await build({
  stdin: { contents: 'import {mountPortal} from "../core-web/src/main"; mountPortal(document.getElementById("root"));', resolveDir: process.cwd(), loader: 'tsx' }, bundle: true, write: false,
  format: 'iife', platform: 'browser', jsx: 'automatic',
  loader: { '.css': 'empty' }, define: { 'process.env.NODE_ENV': '"production"' },
  plugins: [{ name: 'failing-page', setup(build) {
    build.onResolve({ filter: /^\.\/(app|observability|privacy-preferences)$/ }, args => {
      if (args.importer.endsWith('/core-web/src/main.tsx')) return { path: args.path, namespace: 'fixture' };
    });
    build.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({
      resolveDir: new URL('../../core-web/src/', import.meta.url).pathname,
      contents: args.path === './observability'
        ? 'export const observability = { reportError(error) { window.reported.push(error.name); } };'
        : args.path === './privacy-preferences'
          ? 'export function PrivacyPreferences() { return null; }'
          : `import React from 'react';
            class Boundary extends React.Component {
              state = { failed: false };
              static getDerivedStateFromError() { return { failed: true }; }
              render() { return this.state.failed ? <p>Recovered</p> : this.props.children; }
            }
            function Broken() { throw new RangeError('private error content'); }
            export function App() { return window.caught ? <Boundary><Broken /></Boundary> : <Broken />; }`,
      loader: 'jsx',
    }));
  } }],
})).outputFiles[0].text;

for (const caught of [true, false]) test(`public React root reports ${caught ? 'caught' : 'uncaught'} rendering errors`, async t => {
  const dom = new JSDOM('<div id="root"></div>', { url: 'https://dev.beta.myos.blue/', runScripts: 'outside-only' });
  t.after(() => dom.window.close());
  dom.window.caught = caught;
  dom.window.reported = [];
  dom.window.eval(compiled);
  const deadline = Date.now() + 1000;
  while (dom.window.reported.length === 0 && Date.now() < deadline) await delay(5);
  assert.deepEqual(Array.from(dom.window.reported), ['RangeError']);
  assert.equal(dom.window.document.body.textContent, caught ? 'Recovered' : '');
});
