import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
const directory = await mkdtemp(join(tmpdir(), 'myos-live-test-'));
const output = join(directory, 'live.mjs');
await build({ stdin: { contents: 'export * from "./src/transport"; export * from "./src/live"; export * from "./src/api"; export * from "./src/account-context";', resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'ts' }, bundle: true, format: 'esm', platform: 'browser', outfile: output });
const { affects, startLive, subscribeLive, mutate, authenticatedFetch, api, chooseAccount, rememberAccount, selectedAccount, setAccountContext } = await import(pathToFileURL(output));
await rm(directory, { recursive: true });
const encoder = new TextEncoder();
const storage = () => { const values = new Map(); return { getItem: key => values.get(key) || null, setItem: (k,v) => values.set(k,v), removeItem: key => values.delete(key) }; };
globalThis.localStorage = storage();
globalThis.sessionStorage = storage();
const until = async predicate => { const deadline = Date.now() + 2000; while (!predicate()) { assert.ok(Date.now() < deadline, 'condition timed out'); await new Promise(resolve => setTimeout(resolve, 5)); } };

test('fragmented frames are parsed, the stream reconnects, and stopping cancels it', async () => {
  const previous = globalThis.fetch;
  const changes = []; let opens = 0, canceled = 0, stream;
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/blue/v1/live');
    assert.equal(new Headers(options.headers).has('X-MyOS-Profile'), false);
    opens++;
    return new Response(new ReadableStream({ start(c) { stream = c; }, cancel() { canceled++; } }), { headers: { 'Content-Type': 'text/event-stream' } });
  };
  const unsubscribe = subscribeLive(change => changes.push(change));
  const stop = startLive(() => {});
  try {
    await until(() => opens === 1);
    stream.enqueue(encoder.encode('event: change\ndata: {"reset":tr'));
    await new Promise(resolve => setTimeout(resolve, 10)); assert.equal(changes.length, 0);
    stream.enqueue(encoder.encode('ue}\n\nevent: change\ndata: {"changes":{"documents":["first"]}}\n\n'));
    await until(() => changes.length === 2);assert.equal(changes[0].reset, true);assert.deepEqual(changes[1].changes.documents, ['first']);
    stream.close();await until(() => opens === 2);
    stream.enqueue(encoder.encode('event: change\ndata: {"reset":true}\n\n'));
    await until(() => changes.length === 3);
  } finally { stop(); unsubscribe(); globalThis.fetch = previous; }
  await until(() => canceled === 1);
});

test('an admission that completes before the POST returns does not wait for a polling timer', async () => {
  const previous = globalThis.fetch;
  let stream, ready = false, reads = 0;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/live')) return new Response(new ReadableStream({ start(c) { stream = c; c.enqueue(encoder.encode('event: change\ndata: {"reset":true}\n\n')); } }));
    if (options.method === 'POST') {
      stream.enqueue(encoder.encode('event: change\ndata: {"changes":{"admissions":["fast"]}}\n\n'));
      await new Promise(resolve => setImmediate(resolve));
      return Response.json({ status: 'PENDING', admissionId: 'fast' });
    }
    reads++; assert.ok(url.endsWith('/admissions/fast'));
    return Response.json({ status: 'SUCCEEDED', admissionId: 'fast', details: { ok: true } });
  };
  const stop = startLive(state => { ready = state === 'live'; });
  try {
    await until(() => ready);
    const answer = await Promise.race([mutate('documents/example/operations', { operation: 'bump' }), new Promise((_, reject) => setTimeout(() => reject(new Error('missed fast completion notification')), 300))]);
    assert.deepEqual(answer, { ok: true });assert.equal(reads, 1);
  } finally { stop(); globalThis.fetch = previous; }
});

test('authenticated requests send no runtime selection header', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    assert.equal(new Headers(options.headers).has('X-MyOS-Profile'), false);
    return Response.json({});
  };
  try { await authenticatedFetch('info'); }
  finally { globalThis.fetch = previous; }
});

const selected = { userId: 'user', accountId: 'first', sessionContext: 'first-context', membership: { generation: 1, revision: 1 } };
const turn = () => new Promise(resolve => setImmediate(resolve));

test('workspace handoff stops the old stream without a reset that clears the new selection', async () => {
  const previous = { fetch: globalThis.fetch, window: globalThis.window, location: globalThis.location };
  const requests = [], reads = [], states = [];
  let stream, opens = 0, changed = 0;
  globalThis.window = new EventTarget();
  globalThis.location = { origin: 'https://console.example.test', hash: '#documents', reload() {} };
  globalThis.sessionStorage = storage();
  rememberAccount('first', 'user', 1); setAccountContext(selected);
  globalThis.window.addEventListener('myos-session-changed', () => { changed++; chooseAccount(); });
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/live')) {
      opens++;
      return new Response(new ReadableStream({
        start(controller) {
          stream = controller;
          // Fetch aborts an established response body when its signal is aborted.
          options.signal.addEventListener('abort', () => controller.error(options.signal.reason), { once: true });
        },
      }));
    }
    const headers = new Headers(options.headers);
    requests.push({ account: headers.get('X-MyOS-Account'), guard: headers.get('X-MyOS-Session-Context') });
    return requests.at(-1).guard
      ? Response.json({ items: [] })
      : Response.json({ title: 'session_changed' }, { status: 409 });
  };
  const unsubscribe = subscribeLive(() => { reads.push(api('packages').catch(() => {})); });
  const stop = startLive(state => states.push(state));
  try {
    await until(() => stream);
    stream.enqueue(encoder.encode('data: {"reset":true}\n\n'));
    await until(() => requests.length === 1);
    await Promise.all(reads);
    assert.deepEqual(requests, [{ account: 'first', guard: 'first-context' }]);
    // Keep the departing document alive after reload(), as a slow navigation does.
    chooseAccount('second', 'user', 1);
    await turn();
    await Promise.all(reads);
    assert.equal(requests.length, 1, 'the old stream must not refresh subscribers without account context');
    assert.equal(changed, 0);
    assert.equal(selectedAccount(), 'second');
    assert.equal(opens, 1);
    assert.deepEqual(states, ['connecting', 'live']);
  } finally {
    stop(); unsubscribe(); setAccountContext();
    globalThis.fetch = previous.fetch; globalThis.window = previous.window; globalThis.location = previous.location;
  }
});

test('workspace handoff discards an already queued frame before reader continuation', async () => {
  const previous = { fetch: globalThis.fetch, location: globalThis.location };
  let stream, canceled = 0;
  const changes = [];
  globalThis.location = { origin: 'https://console.example.test', hash: '#documents', reload() {} };
  rememberAccount('first', 'user', 1); setAccountContext(selected);
  globalThis.fetch = async () => new Response(new ReadableStream({
    start(controller) { stream = controller; },
    cancel() { canceled++; },
  }));
  const unsubscribe = subscribeLive(change => changes.push(change));
  const stop = startLive(() => {});
  try {
    await until(() => stream);
    await turn();
    stream.enqueue(encoder.encode('data: {"reset":true}\n\n'));
    chooseAccount('second', 'user', 1);
    await turn();
    assert.deepEqual(changes, []);
    assert.equal(canceled, 1);
  } finally {
    stop(); unsubscribe(); setAccountContext();
    globalThis.fetch = previous.fetch; globalThis.location = previous.location;
  }
});

test('a real stream failure still resets subscribers and reconnects in the same workspace', async () => {
  const previous = globalThis.fetch;
  const changes = [];
  let stream, opens = 0;
  rememberAccount('first', 'user', 1); setAccountContext(selected);
  globalThis.fetch = async () => {
    opens++;
    return new Response(new ReadableStream({ start(controller) { stream = controller; } }));
  };
  const unsubscribe = subscribeLive(change => changes.push(change));
  const stop = startLive(() => {});
  try {
    await until(() => stream);
    stream.error(new TypeError('Network disconnected'));
    await until(() => opens === 2);
    assert.deepEqual(changes, [{ reset: true }]);
    stream.enqueue(encoder.encode('data: {"changes":{"documents":["recovered"]}}\n\n'));
    await until(() => changes.length === 2);
    assert.deepEqual(changes[1], { changes: { documents: ['recovered'] } });
  } finally {
    stop(); unsubscribe(); setAccountContext(); globalThis.fetch = previous;
  }
});

// Better publishes a canonical graph atomically; member handles share its root notification.
test('a graph commit refreshes its open member without refreshing unrelated documents', () => {
  const changed = { changes: { documents: ['graph-first'] } };
  assert.equal(affects(changed, 'documents', 'graph-first.child'), true);
  assert.equal(affects(changed, 'documents', 'graph-first'), true);
  assert.equal(affects(changed, 'documents', 'graph-first-other.child'), false);
  assert.equal(affects(changed, 'documents', 'graph-second.child'), false);
  assert.equal(affects(changed, 'timelines', 'graph-first.child'), false);
});
