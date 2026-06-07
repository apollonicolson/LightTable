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
  // After the flip the startup editor is CM6 (.cm-content), not CM5 (.CodeMirror).
  await win.waitForSelector('.cm-content, .CodeMirror');
  await expect.poll(() => win.evaluate(() => !!(window.__lt_test && window.__lt_test.active()))).toBe(true);
  // Open a LIVE CM6 editor in a tab and make it active.
  expect(await lt('openCm6', '')).toBe(true);
  // The active editor is now CM6 (backendKind confirms it). After the flip the
  // startup editor is ALSO CM6, so a bare '.cm-editor' wait is ambiguous — the
  // backendKind check is the authoritative readiness signal.
  expect(await lt('backendKind')).toBe('cm6');
});

test.afterAll(async () => {
  if (!app) return;
  const proc = app.process();
  await Promise.race([app.close().catch(() => {}), new Promise((r) => setTimeout(r, 3000))]);
  try { proc.kill('SIGKILL'); } catch (_) { /* already gone */ }
});

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

test('CM6 live — keymap (real keyboard editing: typing / Enter / Backspace)', async () => {
  await lt('setVal', '');
  await lt('focus');                          // focus the ACTIVE CM6 editor (post-flip
                                              // there are several .cm-content elements)
  await win.keyboard.type('abc');
  await win.keyboard.press('Enter');
  await win.keyboard.type('d');
  expect(await lt('val')).toBe('abc\nd');     // defaultKeymap: Enter inserts newline
  await win.keyboard.press('Backspace');
  expect(await lt('val')).toBe('abc\n');       // defaultKeymap: Backspace deletes
});

test('CM6 live — modes (set-mode applies Lezer syntax highlighting)', async () => {
  await lt('setVal', 'function foo() { return 42; }');
  const spans = () => win.evaluate(() => document.querySelectorAll('.cm-content .cm-line span').length);
  expect(await spans()).toBe(0);              // no language → plain text, no token spans
  await lt('setMode', 'javascript');
  await win.waitForFunction(() => document.querySelectorAll('.cm-content .cm-line span').length > 0);
  expect(await spans()).toBeGreaterThan(0);   // Lezer JS highlighting wraps tokens
  expect(await lt('val')).toBe('function foo() { return 42; }'); // doc undisturbed
});

test('CM6 live — modes: clojure via legacy-modes bridge (the default content)', async () => {
  await lt('setVal', '(defn foo [x] (+ x 1))');
  const spans = () => win.evaluate(() => document.querySelectorAll('.cm-content .cm-line span').length);
  expect(await spans()).toBe(0);
  await lt('setMode', 'clojure');
  await win.waitForFunction(() => document.querySelectorAll('.cm-content .cm-line span').length > 0);
  expect(await spans()).toBeGreaterThan(0);   // StreamLanguage(clojure) highlights
  expect(await lt('val')).toBe('(defn foo [x] (+ x 1))');
});

test('CM6 live — commands (pool.cljs bindings → CM6: deleteLine / goLineEnd)', async () => {
  await lt('setVal', 'one\ntwo\nthree');
  await lt('moveCursor', { line: 1, ch: 0 });
  await lt('execCommand', 'deleteLine');
  expect(await lt('val')).toBe('one\nthree');   // CM6 deleteLine
  await lt('moveCursor', { line: 0, ch: 0 });
  await lt('execCommand', 'goLineEnd');          // cursor-motion (needs layout)
  expect(await lt('cursor')).toEqual({ line: 0, ch: 3 });
});

test('CM6 live — events (edits raise :change to LightTable behaviors)', async () => {
  const before = await lt('changeCount');
  await lt('replace', { line: 0, ch: 0 }, { line: 0, ch: 0 }, 'Q');
  expect(await lt('changeCount')).toBeGreaterThan(before);
});

test('CM6 live — set-options via compartments (lineNumbers toggles, preserves doc)', async () => {
  const gutter = () => win.evaluate(() => !!document.querySelector('.cm-lineNumbers'));
  expect(await gutter()).toBe(false);
  await lt('setOptions', { lineNumbers: true });
  expect(await gutter()).toBe(true);
  // reconfiguring an option does NOT disturb the document
  expect(await lt('val')).toBe('ab\ncde\nf');
  await lt('setOptions', { lineNumbers: false });
  expect(await gutter()).toBe(false);
});

// ADR 0009 — first live consumer wiring: comment seam on CM6. Comment tokens come
// from the language (cm6.modes attaches commentTokens to the legacy clojure mode),
// and toggleComment/lineComment act on the live selection.
test('CM6 live — comment seam (clojure toggle + line, via the editor seam)', async () => {
  await lt('setVal', '(defn f [] 1)');
  await lt('setMode', 'clojure');
  await lt('setSelection', { line: 0, ch: 0 }, { line: 0, ch: 5 });
  await lt('toggleComment');
  expect(await lt('val')).toBe(';; (defn f [] 1)');   // commentTokens wired
  await lt('toggleComment');
  expect(await lt('val')).toBe('(defn f [] 1)');        // toggles back off
  // lineComment always adds; idempotent when already commented
  await lt('lineComment');
  expect(await lt('val')).toBe(';; (defn f [] 1)');
  await lt('lineComment');
  expect(await lt('val')).toBe(';; (defn f [] 1)');
});

// ADR 0009 — find/replace seam on CM6 (cm6.search + a highlight decoration layer).
test('CM6 live — find seam (search / next / prev / highlight / replace)', async () => {
  await lt('setVal', 'cat dog cat dog cat');
  await lt('moveCursor', { line: 0, ch: 0 });
  // search → first match at/after cursor, and ALL matches highlighted
  expect(await lt('search', 'cat', {})).toEqual({ from: 0, to: 3 });
  expect(await lt('selectionText')).toBe('cat');
  expect(await lt('searchMatchCount')).toBe(3);     // 3 "cat" highlighted
  // next wraps forward through the matches
  expect(await lt('findNext', 'cat', {})).toEqual({ from: 8, to: 11 });
  expect(await lt('findNext', 'cat', {})).toEqual({ from: 16, to: 19 });
  expect(await lt('findNext', 'cat', {})).toEqual({ from: 0, to: 3 }); // wrap
  // prev goes back
  expect(await lt('findPrev', 'cat', {})).toEqual({ from: 16, to: 19 });
  // clear removes the highlight
  await lt('clearSearch');
  expect(await lt('searchMatchCount')).toBe(0);
});

test('CM6 live — find seam (case-insensitive default + replace-all)', async () => {
  await lt('setVal', 'Cat cat CAT');
  expect(await lt('search', 'cat', {})).toEqual({ from: 0, to: 3 }); // case-insensitive
  expect(await lt('searchMatchCount')).toBe(3);
  // replace-all in one undo step
  expect(await lt('replaceSearch', 'cat', 'dog', {}, true)).toBe(3);
  expect(await lt('val')).toBe('dog dog dog');
  await lt('undo');
  expect(await lt('val')).toBe('Cat cat CAT');     // single undo reverts all
});

// ADR 0009 — eval result widgets on CM6 (cm6.results over a decoration layer).
// CM5 attached bookmarks/line-widgets to LineHandles and relocated them by hand;
// CM6 decorations auto-track, so a result follows its line through edits for free.
test('CM6 live — eval result widgets (render, auto-track through edits, remove)', async () => {
  await lt('setVal', 'one\ntwo\nthree');
  const widgets = () => win.evaluate(() => document.querySelectorAll('.cm6-eval-result').length);
  expect(await widgets()).toBe(0);
  // inline result on line 1 ("two")
  await lt('addResult', 'r1', 1, '=> 42', false);
  await win.waitForFunction(() => document.querySelectorAll('.cm6-eval-result').length > 0);
  expect(await widgets()).toBe(1);
  expect(await lt('resultPresent', 'r1')).toBe(true);
  expect(await lt('resultLine', 'r1')).toBe(1);
  // insert a line ABOVE → the result auto-tracks to its new line (no manual move)
  await lt('replace', { line: 0, ch: 0 }, { line: 0, ch: 0 }, 'zero\n');
  expect(await lt('resultLine', 'r1')).toBe(2);
  expect(await lt('resultPresent', 'r1')).toBe(true);
  // block result (underline/exception style) renders too
  await lt('addResult', 'r2', 0, 'boom', true);
  expect(await widgets()).toBe(2);
  // remove by id
  await lt('removeResult', 'r1', false);
  expect(await lt('resultPresent', 'r1')).toBe(false);
  expect(await widgets()).toBe(1);
});

// ADR 0009 — eval.cljs full path on CM6: the :editor.result manager (::inline-
// results) creates an ::inline-result whose CM6 branch uses cm6.results.
test('CM6 live — eval inline result via the eval manager (full path)', async () => {
  await lt('setVal', 'one\ntwo\nthree');
  const marks = () => win.evaluate(() => document.querySelectorAll('.result-mark').length);
  await lt('evalResult', '=> 42', 1);
  await win.waitForFunction(() => document.querySelectorAll('.result-mark').length > 0);
  expect(await marks()).toBe(1);
  // re-eval the SAME line replaces the previous result (manager keys by line) → still 1
  await lt('evalResult', '=> 43', 1);
  expect(await marks()).toBe(1);
  // a result on a different line coexists
  await lt('evalResult', '=> 99', 2);
  expect(await marks()).toBe(2);
});

// exception path renders a block widget below the line
test('CM6 live — eval exception via the eval manager (block widget)', async () => {
  await lt('setVal', 'boom\nok');
  const ex = () => win.evaluate(() => document.querySelectorAll('.inline-exception').length);
  await lt('evalException', 'NPE', 0);
  await win.waitForFunction(() => document.querySelectorAll('.inline-exception').length > 0);
  expect(await ex()).toBe(1);
});

// ADR 0009 — auto_complete on CM6: the inner-mode crash is the real blocker; this
// proves get-pattern/get-token work and the hint popup opens (no line-handle / no
// positionHint crash).
test('CM6 live — auto-complete (token extraction + hint popup, no inner-mode crash)', async () => {
  await lt('setVal', 'alphabet\nal');
  // get-pattern must not crash (inner-mode → nil on CM6) and get-token reads the word
  expect(await lt('hintPatternOk')).toBe(true);
  expect((await lt('hintTokenAt', 0, 8)).string).toBe('alphabet');
  // open the hint popup on the "al" token with seeded completions
  await lt('moveCursor', { line: 1, ch: 2 });
  await lt('seedHints', ['alpha', 'alphabet', 'beta']);
  await lt('showHint');
  await win.waitForFunction(() => window.__lt_test.hintActive() === true).catch(() => {});
  expect(await lt('hintActive')).toBe(true);          // popup opened, no crash
  await lt('execCommand', 'esc');                       // cleanup (best-effort)
});

// ADR 0009 — watches on CM6: watch-selection highlights a range (cm6.watches
// layer) + an inline result; unwatch-at-cursor finds the watch by tracked range.
test('CM6 live — watches (highlight + inline result, unwatch by range)', async () => {
  await lt('setVal', '(+ 1 2)');
  await lt('setMode', 'clojure');
  await lt('setSelection', { line: 0, ch: 0 }, { line: 0, ch: 7 });
  await lt('watch');
  expect(await lt('watchCount')).toBe(1);
  await win.waitForFunction(() => document.querySelectorAll('.watched').length > 0);
  expect(await win.evaluate(() => document.querySelectorAll('.watched').length)).toBeGreaterThan(0);
  // unwatch with the cursor inside the watched range
  await lt('moveCursor', { line: 0, ch: 3 });
  await lt('unwatchAtCursor');
  expect(await lt('watchCount')).toBe(0);
});

// ADR 0009 — doc-model: a CM6 editor backed by a doc seeds its content from the
// doc (the path the flip uses for file editors; save flows through the backend).
test('CM6 live — doc-model (CM6 editor seeds content from its doc + edits)', async () => {
  expect(await lt('openCm6Doc', 'seeded\nfrom\ndoc')).toBe(true);
  expect(await lt('backendKind')).toBe('cm6');
  expect(await lt('val')).toBe('seeded\nfrom\ndoc');   // content came from the doc
  await lt('replace', { line: 0, ch: 0 }, { line: 0, ch: 6 }, 'EDITED');
  expect(await lt('val')).toBe('EDITED\nfrom\ndoc');   // edits land in the CM6 view
});

// ADR 0009 step 4 — THE FLIP: a default editor (no :backend) is now CM6.
test('CM6 live — flip: default editor is CM6', async () => {
  expect(await lt('openDefault', 'default editor')).toBe(true);
  expect(await lt('backendKind')).toBe('cm6');         // CM6 is the default backend
  expect(await lt('val')).toBe('default editor');
});
