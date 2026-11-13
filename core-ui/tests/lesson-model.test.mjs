import { test } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { readFileSync } from "node:fs";
const bundled = await build({
  entryPoints: ["src/lessons/model.ts"],
  bundle: true,
  write: false,
  format: "esm",
});
const {
  resumesBootstrap,
  observedOutcome,
  pointer,
  lessons,
  retainedLessons,
  lessonForRun,
  definitionOf,
  saveRun,
  loadRun,
} = await import(
  "data:text/javascript;base64," +
    Buffer.from(bundled.outputFiles[0].text).toString("base64")
);
test("progress requires a stable result; admission alone is not execution", () => {
  assert.equal(
    observedOutcome({ assessment: { outcome: "ACCEPTED" } }, false),
    false,
  );
  assert.equal(
    observedOutcome({ localHistory: [{ stable: false }] }, false),
    false,
  );
  assert.equal(
    observedOutcome({ localHistory: [{ stable: true }] }, false),
    true,
  );
  for (const outcome of [
    "EXECUTION_REJECTED",
    "REJECTED",
    "NO_MATCH",
    "IGNORED",
  ])
    assert.throws(() => observedOutcome({ assessment: { outcome } }, false));
});
test("expected rejection never accepts an authorization failure or an applied mutation", () => {
  assert.equal(
    observedOutcome({ assessment: { outcome: "EXECUTION_REJECTED" } }, true),
    true,
  );
  for (const outcome of ["APPLIED", "REJECTED", "NO_MATCH"])
    assert.throws(() => observedOutcome({ assessment: { outcome } }, true));
  assert.throws(() =>
    observedOutcome({ localHistory: [{ stable: true }] }, true),
  );
});
test("the representative boundary requires its exact rejection and no committed change", () => {
  const reason = "INVALID_TARGET_CHANNEL";
  assert.equal(observedOutcome({ disposition: { outcome: "REJECTED", reason } }, true, reason), true);
  assert.equal(observedOutcome({ assessment: { outcome: "ACCEPTED" } }, true, reason), false);
  for (const disposition of [
    { outcome: "REJECTED", reason: "OTHER" },
    { outcome: "EXECUTION_REJECTED", reason },
    { outcome: "APPLIED", reason },
    { outcome: "NO_MATCH", reason },
  ]) assert.throws(() => observedOutcome({ disposition }, true, reason));
  assert.throws(() => observedOutcome({ disposition: { outcome: "REJECTED", reason }, localHistory: [{ stable: true }] }, true, reason));
  assert.throws(() => observedOutcome({ disposition: { outcome: "REJECTED", reason: "STALE_DOCUMENT_PRECONDITION" } }, true, reason), /version changed/);
  const steps = lessons.flatMap(l => l.steps.filter(s => s.expectedRejectionReason).map(s => [l.id, s.operation, s.source]));
  assert.deepEqual(steps, [["08-participants-agents", "leadGreeting", "buyer__assistant"]]);
});
test("guide state is scoped to the user and exact example definition", () => {
  const store = new Map();
  globalThis.sessionStorage = {
    getItem: (k) => store.get(k),
    setItem: (k, v) => store.set(k, v),
  };
  const lesson = lessons[0],
    run = {
      version: 1,
      id: "run",
      userId: "one",
      exampleId: lesson.id,
      definition: definitionOf(lesson),
      step: 0,
      results: [],
      identities: {},
      representatives: {},
      source: lesson.source,
    };
  saveRun(run);
  assert.deepEqual(loadRun("one", "run", lesson), run);
  assert.equal(loadRun("two", "run", lesson), undefined);
  assert.throws(() =>
    loadRun("one", "run", {
      ...lesson,
      source: lesson.source + "\nchanged: true",
    }),
  );
});
test("state assertions interpret escaped JSON pointer segments", () => {
  assert.equal(pointer({ "a/b": { "c~d": 4 } }, "/a~1b/c~0d"), 4);
  assert.equal(pointer({}, "/missing"), undefined);
  assert.throws(() => pointer({}, "missing"));
});
test("every catalogue step has one supported action and a declared channel", () => {
  for (const lesson of lessons)
    for (const step of lesson.steps) {
      assert.ok(
        Boolean(step.operation) !== Boolean(step.clock),
        `${lesson.id}: unsupported step`,
      );
      if (step.operation) assert.ok(step.channel, lesson.id);
    }
});

test("an accepted bootstrap resumes initialization even when its session ID is already retained", () => {
  assert.equal(
    resumesBootstrap({ root: "session", pending: { kind: "bootstrap" } }),
    true,
  );
  assert.equal(
    resumesBootstrap({ root: "session", pending: { kind: "operation" } }),
    false,
  );
});

test('every example explains its value and unique mechanism consistently in both public and signed-in views',async()=>{
 const {readFile}=await import('node:fs/promises');
 const values=JSON.parse(await readFile('src/data/example-values.json','utf8'));
 const publicValues=JSON.parse(await readFile('tests/fixtures/public-example-values.json','utf8'));
 assert.deepEqual(values,publicValues);
 for(const lesson of lessons){assert.equal(values[lesson.id]?.length,2,lesson.id);assert.ok(values[lesson.id].every(x=>x.length>30),lesson.id);}
});

test('retained and current payment runs restore their own exact source and steps after reload', () => {
  const store = new Map();
  globalThis.sessionStorage = { getItem: key => store.get(key), setItem: (key,value) => store.set(key,value) };
  const current = lessons.find(row => row.id === '01-order-payment');
  const retained = retainedLessons.find(row => row.id === current.id);
  const runtimes = JSON.parse(readFileSync('src/data/process-runtimes.json','utf8'));
  assert.notEqual(definitionOf(current),definitionOf(retained));
  assert.ok(current.steps.length > retained.steps.length, 'Current instructions require their own participant approvals');
  for (const [edition, runtime] of [[retained,runtimes.finalGas],[current,runtimes.accessEvents]]) {
    const run = {version:1,id:'run',userId:'user',exampleId:edition.id,definition:definitionOf(edition),source:edition.source,
      identities:{customer:'workspace'},representatives:{},step:0,results:[],selection:{runtime,packages:edition.packages}};
    saveRun(run);
    const restored = loadRun('user','run',current);
    assert.deepEqual(restored,run);
    assert.equal(lessonForRun(restored),edition);
    assert.deepEqual(lessonForRun(restored).steps,edition.steps);
  }
  const old = {version:1,id:'old',userId:'user',exampleId:retained.id,definition:definitionOf(retained),source:retained.source,
    identities:{},representatives:{},step:3,results:[]};
  saveRun(old);
  assert.equal(lessonForRun(loadRun('user','old',current)),retained);
  const forged = {...old, id:'tampered', selection:{runtime:runtimes.accessEvents,packages:current.packages}};
  saveRun(forged);
  assert.throws(()=>loadRun('user','tampered',current),/does not match its runtime/);
});

test("native dispositions resolve guided steps after signing-account admission", () => {
  const retained = (outcome, code) => ({
    assessment: { outcome: "ACCEPTED" },
    disposition: { outcome, code, epoch: 4, reason: "The transaction was rejected at epoch 4." },
    localHistory: [],
  });
  const failed = retained("FAILED", "BEX_EXECUTION_REJECTED");
  assert.equal(observedOutcome(failed, true), true);
  assert.throws(() => observedOutcome(failed, false), /BEX_EXECUTION_REJECTED/);
  assert.throws(() => observedOutcome({...failed, localHistory: [{stable: true}]}, true));
  assert.throws(() => observedOutcome(retained("FAILED", "STALE_DOCUMENT_PRECONDITION"), true),
    e => e.reason === "STALE_DOCUMENT_PRECONDITION");
  assert.equal(observedOutcome(retained("FAILED", "INVALID_TARGET_CHANNEL"), true, "INVALID_TARGET_CHANNEL"), true);
  for (const outcome of ["INAPPLICABLE", "GAS_LIMIT_EXCEEDED", "TERMINATED", "FAILED"]) {
    assert.throws(() => observedOutcome(retained(outcome), true));
    assert.throws(() => observedOutcome(retained(outcome), false));
  }
});
