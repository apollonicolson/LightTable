// CM6 LIVE-editor parity (M5 swap criterion, ADR 0008).
//
// editor-parity.spec.js pins lt.objs.editor behavior on the live CM5 editor.
// This runs the SAME assertions against a LIVE CM6 editor — opened in a real tab
// via the bridge's openCm6 (object* :init builds a CM6 EditorView, :backend is a
// Cm6Backend, the seam drives it). Identical seam calls, identical results on
// both backends in the real app = CM6 has reached parity for these capabilities.
//
// Prereq: release build with LT_TEST_BRIDGE. test:e2e:cm6 builds first.
const path = require('path');
const { test, expect, _electron: electron } = require('@playwright/test');

const ROOT = __dirname + '/..';
const ELECTRON_BIN = path.join(ROOT, 'deploy/electron/node_modules/electron/dist/electron');
const APP_DIR = path.join(ROOT, 'deploy/core');
const FIXTURE = path.join(__dirname, 'fixtures/sample.clj');

let app, win;
const lt = (fn, ...args) =>
  win.evaluate(([f, a]) => window.__lt_test[f].apply(null, a), [fn, args]);

test.beforeAll(async () => {
  app = await electron.launch({
    executablePath: ELECTRON_BIN,
    args: ['--no-sandbox', '--user-data-dir=/tmp/lt-pw-cm6', APP_DIR, FIXTURE],
    env: { ...process.env, LT_TEST_BRIDGE: '1' },
  });
  win = await app.firstWindow();
  await win.waitForSelector('.CodeMirror');
  await expect.poll(() => win.evaluate(() => !!(window.__lt_test && window.__lt_test.active()))).toBe(true);
  // Open a LIVE CM6 editor in a tab and make it active.
  expect(await lt('openCm6', '')).toBe(true);
  // The active editor is now CM6, and CM6's view DOM rendered (.cm-editor).
  expect(await lt('backendKind')).toBe('cm6');
  await win.waitForSelector('.cm-editor');
});

test.afterAll(async () => { if (app) await app.close(); });

test.beforeEach(async () => { await lt('setVal', 'ab\ncde\nf'); });

test('CM6 live — value / lines', async () => {
  expect(await lt('val')).toBe('ab\ncde\nf');
  expect(await lt('lineCount')).toBe(3);
  expect(await lt('line', 1)).toBe('cde');
  expect(await lt('firstLine')).toBe(0);
  expect(await lt('lastLine')).toBe(2);
});

test('CM6 live — cursor', async () => {
  expect(await lt('cursor')).toEqual({ line: 0, ch: 0 });
  await lt('moveCursor', { line: 1, ch: 2 });
  expect(await lt('cursor')).toEqual({ line: 1, ch: 2 });
  expect(await lt('getChar', -1)).toBe('d');
  expect(await lt('getChar', 1)).toBe('e');
});

test('CM6 live — selection', async () => {
  expect(await lt('somethingSelected')).toBe(false);
  await lt('setSelection', { line: 0, ch: 0 }, { line: 0, ch: 2 });
  expect(await lt('somethingSelected')).toBe(true);
  expect(await lt('selectionText')).toBe('ab');
  expect(await lt('selectionBounds')).toEqual({ from: { line: 0, ch: 0 }, to: { line: 0, ch: 2 } });
  await lt('replaceSelection', 'ZZ');
  expect(await lt('val')).toBe('ZZ\ncde\nf');
});

test('CM6 live — edit (replace + insert-at-cursor)', async () => {
  await lt('replace', { line: 1, ch: 0 }, { line: 1, ch: 2 }, 'XY');
  expect(await lt('val')).toBe('ab\nXYe\nf');
  await lt('moveCursor', { line: 0, ch: 2 });
  await lt('insertAtCursor', '!!');
  expect(await lt('val')).toBe('ab!!\nXYe\nf');
});

test('CM6 live — history (undo / redo)', async () => {
  await lt('replace', { line: 0, ch: 2 }, { line: 0, ch: 2 }, 'XYZ');
  expect(await lt('val')).toBe('abXYZ\ncde\nf');
  await lt('undo');
  expect(await lt('val')).toBe('ab\ncde\nf');
  await lt('redo');
  expect(await lt('val')).toBe('abXYZ\ncde\nf');
});
