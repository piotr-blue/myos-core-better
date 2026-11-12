import { build } from "esbuild";
import assert from "node:assert/strict";
import { performance } from "node:perf_hooks";
import { readFile, writeFile } from "node:fs/promises";
import { stringify } from "yaml";
const compiled = await build({
  entryPoints: ["src/presentation.ts"],
  bundle: true,
  write: false,
  format: "esm",
});
const { minimize } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString("base64")}`
);
const textId = "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC";
const scalar = { type: { blueId: textId }, value: "Zyc" };
assert.deepEqual(minimize({ x: scalar }), { x: "Zyc" });
assert.deepEqual(minimize({ ...scalar, description: "Keep me" }), {
  ...scalar,
  description: "Keep me",
});
assert.deepEqual(minimize({ type: { blueId: "custom" }, value: "Zyc" }), {
  type: { blueId: "custom" },
  value: "Zyc",
});
assert.deepEqual(minimize({ blueId: textId }), { blueId: textId });
assert.deepEqual(scalar, { type: { blueId: textId }, value: "Zyc" });
const values = [
  {
    name: "synthetic 128-order document",
    value: {
      orders: Array.from({ length: 128 }, () => ({ blueId: textId })),
      entries: Array.from({ length: 128 }, () => scalar),
    },
  },
];
try {
  values.push({
    name: "live Agreement exact state",
    value: JSON.parse(
      JSON.parse(
        await readFile(
          "../.local/ui-content-5rLRcb71Z7daqiM28Vau544rnhCiBsRiqDQXEemGkctD.json",
          "utf8",
        ),
      ).text,
    ),
  });
} catch {}
const results = values.map(({ name, value }) => {
  const timings = {};
  for (const simple of [false, true]) {
    const samples = [];
    for (let i = 0; i < 30; i++) {
      const start = performance.now();
      stringify(simple ? minimize(value) : value, { lineWidth: 0 });
      samples.push(performance.now() - start);
    }
    samples.sort((a, b) => a - b);
    timings[simple ? "simplifiedYamlMedianMs" : "fullYamlMedianMs"] =
      samples[15];
  }
  const start = performance.now();
  for (let i = 0; i < 100; i++) minimize(value);
  timings.minimizeOnlyMeanMs = (performance.now() - start) / 100;
  return { name, ...timings };
});
await writeFile(
  "../reports/core-ui-presentation-performance.json",
  JSON.stringify({ assertions: "passed", results }, null, 2) + "\n",
);
console.log(JSON.stringify(results, null, 2));
