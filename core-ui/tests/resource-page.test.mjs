import { test } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
const compiled = (
  await build({
    entryPoints: ["src/resource-page.ts"],
    bundle: true,
    write: false,
    format: "esm",
  })
).outputFiles[0].text;
const { ResourcePager } = await import(
  "data:text/javascript;base64," + Buffer.from(compiled).toString("base64")
);

test("first render requests one page, even with thousands of documents; more follows only on demand", async () => {
  const requests = [];
  const pager = new ResourcePager(
    "documents?limit=10&q=Northstar",
    async (path) => {
      requests.push(path);
      return {
        items: [{ sessionId: String(requests.length) }],
        total: 2400,
        nextPageToken: "cursor",
      };
    },
    () => {},
  );
  await pager.first();
  assert.equal(requests.length, 1);
  assert.equal(pager.state.rows.length, 1);
  assert.equal(pager.state.total, 2400);
  await pager.more();
  assert.equal(requests.length, 2);
  assert.equal(requests[1], "documents?limit=10&q=Northstar&pageToken=cursor");
  assert.equal(pager.state.rows.length, 2);
  pager.dispose();
});
test("a disposed filter or account cannot publish a late response", async () => {
  let complete, signal;
  const states = [];
  const pager = new ResourcePager(
    "documents?limit=10",
    (_, requestSignal) => {
      signal = requestSignal;
      return new Promise((resolve) => {
        complete = resolve;
      });
    },
    (state) => states.push(state),
  );
  const request = pager.first();
  pager.dispose();
  complete({ items: [{ sessionId: "stale" }], total: 1 });
  await request;
  assert.equal(signal.aborted, true);
  assert.equal(
    states.some((state) => state.rows.length),
    false,
  );
});
test("refresh events coalesce during a pending page and refresh only the first page", async () => {
  const requests = [];
  let complete;
  const pager = new ResourcePager(
    "documents?limit=10",
    (path) => {
      requests.push(path);
      return new Promise((resolve) => {
        complete = resolve;
      });
    },
    () => {},
  );
  const request = pager.first();
  pager.first();
  pager.first();
  pager.more();
  complete({
    items: [{ sessionId: "one" }],
    total: 1000,
    nextPageToken: "next",
  });
  await request;
  assert.equal(requests.length, 2);
  assert.ok(requests.every((path) => !path.includes("pageToken")));
  complete({
    items: [{ sessionId: "new" }],
    total: 1001,
    nextPageToken: "next",
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(pager.state.rows[0].sessionId, "new");
  pager.dispose();
});
test("a failed next page preserves rows and continuation for retry", async () => {
  let fail = false;
  const pager = new ResourcePager(
    "timelines?limit=10",
    async () => {
      if (fail) throw new Error("Temporarily unavailable");
      return {
        items: [{ timelineId: "one" }],
        total: 20,
        nextPageToken: "next",
      };
    },
    () => {},
  );
  await pager.first();
  fail = true;
  await pager.more();
  assert.equal(pager.state.rows.length, 1);
  assert.equal(pager.state.next, "next");
  assert.equal(pager.state.error, "Temporarily unavailable");
  pager.dispose();
});

test("the same empty Documents page transitions from initial to projected read state", async () => {
  let readState = "initial";
  const pager = new ResourcePager("documents?limit=10", async () => ({items: [], total: 0, nextPageToken: null, readState}), () => {});
  await pager.first();
  assert.equal(pager.state.readState, "initial");
  assert.deepEqual(pager.state.rows, []);
  assert.equal(pager.state.next, null);
  readState = "projected";
  await pager.first();
  assert.equal(pager.state.readState, "projected");
  assert.deepEqual(pager.state.rows, []);
  pager.dispose();
});
