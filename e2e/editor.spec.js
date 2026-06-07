// E2E: launch the real LightTable editor (Electron 42 + deploy/core) and prove
// the rewired raise* (res resolution + multimethod dispatch + opt-in tap) drives
// it end-to-end — init/skin behaviors, render gate, and a file opening into
// CodeMirror. This is the repeatable form of the manual CDP rank-1 check.
//
// Prereq: a release build (deploy/core/lighttable/js/bootstrap.js). The
// `test:e2e` npm script builds it first.
const path = require('path');
const { test, expect, _electron: electron } = require('@playwright/test');

const ROOT = __dirname + '/..';
const ELECTRON_BIN = path.join(ROOT, 'deploy/electron/node_modules/electron/dist/electron');
const APP_DIR = path.join(ROOT, 'deploy/core');
const FIXTURE = path.join(__dirname, 'fixtures/sample.clj');

test('editor renders and opens a file (rewired raise* drives init + open)', async () => {
  const app = await electron.launch({
    executablePath: ELECTRON_BIN,
    args: ['--no-sandbox', '--user-data-dir=/tmp/lt-pw', APP_DIR, FIXTURE],
  });
  try {
    const win = await app.firstWindow();

    // Render gate: the :show/skin behaviors (fired through raise*) reveal #wrapper.
    await win.waitForFunction(() => {
      const w = document.querySelector('#wrapper');
      return w && getComputedStyle(w).opacity === '1';
    });

    // init/skin behaviors fired → body carries the active skin classes.
    await expect.poll(() => win.evaluate(() => document.body.className))
      .toContain('active');

    // The opener→editor→CodeMirror chain (deep raise* usage) rendered the file.
    await win.waitForSelector('.CodeMirror');
    const cmText = await win.evaluate(
      () => (document.querySelector('.CodeMirror').textContent || ''));
    expect(cmText).toContain('sample.demo');

    // Canvas populated + statusbar live (more init behaviors).
    const canvasKids = await win.evaluate(
      () => { const c = document.querySelector('#canvas'); return c ? c.childElementCount : -1; });
    expect(canvasKids).toBeGreaterThan(0);
    expect(await win.evaluate(() => !!document.querySelector('#statusbar'))).toBe(true);
  } finally {
    await app.close();
  }
});
