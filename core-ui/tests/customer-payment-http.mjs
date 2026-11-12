/** Rendered customer component against the actual disposable HTTP fixture, never a mocked API. */
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';
const [configurationPath, sessionId, actionId, mode] = process.argv.slice(2);
if (!['fund', 'authorize', 'inspect'].includes(mode)) throw new Error('Expected a bounded customer page action');
const configuration = JSON.parse(await readFile(configurationPath, 'utf8'));
const account = configuration.accounts[mode === 'inspect' ? 'guarantor' : 'payer'];
const root = fileURLToPath(new URL('../', import.meta.url));
const code = (await build({ stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';import {CustomerPayment} from './src/customer-payment';createRoot(document.getElementById('root')).render(<CustomerPayment id=${JSON.stringify(sessionId)} actionId=${JSON.stringify(actionId)} navigate={() => {}}/>);`,
  resolveDir: root, loader: 'tsx' }, bundle: true, write: false, format: 'iife', platform: 'browser', jsx: 'automatic', define: { 'process.env.NODE_ENV': '"production"' } })).outputFiles[0].text;
const dom = new JSDOM('<div id="root"></div>', { url: configuration.url, runScripts: 'outside-only', pretendToBeVisual: true });
const { window } = dom;
Object.assign(window, { Headers, Response, Request, AbortController, AbortSignal, TextDecoder });
// This fixture uses a real bearer identity instead of a production portal cookie.
// It changes only transport authentication; every response comes from Core.
window.fetch = (path, options = {}) => {
  const url = new URL(path, configuration.url);
  assert.equal(url.origin, new URL(configuration.url).origin);
  const headers = new Headers(options.headers); headers.set('Authorization', 'Bearer ' + account.apiKey);
  return fetch(url, { ...options, headers });
};
async function until(predicate) {
  const deadline = Date.now() + 120_000;
  while (!predicate()) {
    const error = window.document.querySelector('[role="alert"]');
    if (error) throw new Error(error.textContent);
    if (Date.now() >= deadline) throw new Error('Customer component did not reach the expected state: ' + window.document.body.textContent.slice(0, 1200));
    await delay(50);
  }
}
try {
  window.eval(code);
  if (mode === 'inspect') {
    await until(() => window.document.body.textContent.includes('no payer operation'));
    assert.equal([...window.document.querySelectorAll('button')].some(button => ['Continue to funding', 'Authorize this amount'].includes(button.textContent)), false);
  } else {
    const label = mode === 'fund' ? 'Continue to funding' : 'Authorize this amount';
    const button = () => [...window.document.querySelectorAll('button')].find(button => button.textContent === label);
    await until(() => button() && !button().disabled); button().click();
    await until(() => window.document.body.textContent.includes('instruction processed your action'));
    const amount = [...window.document.querySelectorAll('dt')].find(node => node.textContent === 'Completed (minor units)');
    assert.equal(amount.nextElementSibling.textContent, '0', 'The page must not turn a request into payment success');
  }
  console.log('PASS: rendered customer page through actual Core HTTP — ' + mode);
} finally { window.close(); }
