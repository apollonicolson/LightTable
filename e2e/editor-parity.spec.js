// CM6 characterization PARITY SUITE (M5 gate, ADR 0008).
//
// Drives lt.objs.editor's PUBLIC seam (via window.__lt_test, installed only when
// LT_TEST_BRIDGE is set) on the running CM5 editor and pins its OBSERVABLE
// behavior. These assertions ARE the parity contract: CM6 reaches parity when
// its backend, wired behind the same seam, passes this same suite unchanged.
//
// Backend-agnostic by construction — it never touches a CM5 (or CM6) API
// directly, only the lt.objs.editor seam. value -> cursor -> selection
// capabilities (the slices built so far); marks/modes/keymaps follow.
//
// Prereq: release build (the test:e2e:parity script builds it first).
const path = require('path');
const { test, expect, _electron: electron } = require('@playwright/test');

const ROOT = __dirname + '/..';
const ELECTRON_BIN = path.join(ROOT, 'deploy/electron/node_modules/electron/dist/electron');
const APP_DIR = path.join(ROOT, 'deploy/core');
const FIXTURE = path.join(__dirname, 'fixtures/sample.clj');

let app, win;

// Call a seam fn in the renderer: lt('replace', from, to, 'x')
const lt = (fn, ...args) =>
  win.evaluate(([f, a]) => window.__lt_test[f].apply(null, a), [fn, args]);

test.beforeAll(async () => {
  app = await electron.launch({
    executablePath: ELECTRON_BIN,
    args: ['--no-sandbox', '--user-data-dir=/tmp/lt-pw-parity', APP_DIR, FIXTURE],
    env: { ...process.env, LT_TEST_BRIDGE: '1' },
  });
  win = await app.firstWindow();
  await win.waitForSelector('.CodeMirror');
  // The bridge is installed at load and the fixture opened → an editor is active.
  await expect.poll(() => win.evaluate(() => !!(window.__lt_test && window.__lt_test.active())))
    .toBe(true);
});

test.afterAll(async () => { if (app) await app.close(); });

// Each test seeds a known document via the seam, so assertions are deterministic
// and independent of the fixture content.
test.beforeEach(async () => { await lt('setVal', 'ab\ncde\nf'); });

test('value capability — setVal / val / lines', async () => {
  expect(await lt('val')).toBe('ab\ncde\nf');
  expect(await lt('lineCount')).toBe(3);
  expect(await lt('line', 1)).toBe('cde');
  expect(await lt('firstLine')).toBe(0);
  expect(await lt('lastLine')).toBe(2);
});

test('cursor capability — setVal resets cursor; moveCursor / cursor round-trip', async () => {
  // CM5 setValue loses the cursor → {line 0, ch 0}.
  expect(await lt('cursor')).toEqual({ line: 0, ch: 0 });
  await lt('moveCursor', { line: 1, ch: 2 });
  expect(await lt('cursor')).toEqual({ line: 1, ch: 2 });
  // getChar is offset/position based around the cursor.
  expect(await lt('getChar', -1)).toBe('d');
  expect(await lt('getChar', 1)).toBe('e');
});

test('selection capability — set / read / bounds / replaceSelection', async () => {
  expect(await lt('somethingSelected')).toBe(false);
  await lt('setSelection', { line: 0, ch: 0 }, { line: 0, ch: 2 });
  expect(await lt('somethingSelected')).toBe(true);
  expect(await lt('selectionText')).toBe('ab');
  expect(await lt('selectionBounds')).toEqual({ from: { line: 0, ch: 0 }, to: { line: 0, ch: 2 } });
  await lt('replaceSelection', 'ZZ');
  expect(await lt('val')).toBe('ZZ\ncde\nf');
});

test('edit capability — position replace + insert-at-cursor', async () => {
  await lt('replace', { line: 1, ch: 0 }, { line: 1, ch: 2 }, 'XY');
  expect(await lt('val')).toBe('ab\nXYe\nf');
  await lt('moveCursor', { line: 0, ch: 2 });
  await lt('insertAtCursor', '!!');
  expect(await lt('val')).toBe('ab!!\nXYe\nf');
});

test('history capability — undo / redo restore the document', async () => {
  await lt('replace', { line: 0, ch: 2 }, { line: 0, ch: 2 }, 'XYZ');
  expect(await lt('val')).toBe('abXYZ\ncde\nf');
  await lt('undo');
  expect(await lt('val')).toBe('ab\ncde\nf');
  await lt('redo');
  expect(await lt('val')).toBe('abXYZ\ncde\nf');
});
