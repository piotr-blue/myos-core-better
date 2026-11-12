import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

for (const [canonical, legacy] of [
  ['https://dev.myos.blue', 'https://dev.beta.myos.blue'],
  ['https://myos.blue', 'https://beta.myos.blue'],
]) {
  test(`developer home link retains either approved alias for ${canonical}`, async () => {
    const compiled = (await build({
      stdin: { contents: 'import { createRoot } from "react-dom/client"; import { DevLayout } from "./src/components/dev-layout"; createRoot(document.getElementById("root")).render(<DevLayout page="settings" navigate={() => {}}>Settings</DevLayout>);', resolveDir: process.cwd(), loader: 'tsx' },
      bundle: true, write: false, format: 'iife', platform: 'browser', jsx: 'automatic', loader: { '.css': 'empty' },
      define: { 'process.env.NODE_ENV': '"production"', __MYOS_PORTAL_CONFIG__: JSON.stringify({ base: '/developers/app/', api: '', web: canonical }) },
    })).outputFiles[0].text;
    for (const origin of [canonical, legacy]) {
      const dom = new JSDOM('<div id="root"></div>', { url: `${origin}/developers/app/#settings`, runScripts: 'outside-only', pretendToBeVisual: true });
      try {
        dom.window.eval(compiled);
        const deadline = Date.now() + 2000;
        while (!dom.window.document.querySelector('[aria-label="MyOS Dev home"]') && Date.now() < deadline)
          await new Promise(resolve => setTimeout(resolve, 5));
        assert.equal(dom.window.document.querySelector('[aria-label="MyOS Dev home"]')?.href, `${origin}/dev/`);
      } finally { dom.window.close(); }
    }
  });
}
