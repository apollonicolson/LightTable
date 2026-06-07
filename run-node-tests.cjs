#!/usr/bin/env node
// DOM polyfill via jsdom, then run the compiled :test bundle. This lets
// DOM-coupled CLJS (lt.object, lt.util.dom, singultus-built nodes) be unit-tested
// headless in node — closing the gap where bare `node target/node-tests.js`
// can't load anything that touches `document`/`window`. Pure tests are
// unaffected (the polyfilled globals are harmless to them).
//
// Pattern borrowed from ~/GitHub/hbtcomputers.com.au/forma/run-node-tests.cjs.
// Usage: npx shadow-cljs compile test && node run-node-tests.cjs
//
// Limits (same as forma): jsdom has no real canvas 2D context, no EventSource,
// and no layout — tests needing those belong in a :browser-test build run in
// Electron, not here.
const { JSDOM } = require('jsdom');
const dom = new JSDOM('<!DOCTYPE html><html><head></head><body></body></html>', {
  pretendToBeVisual: true,
  runScripts: 'outside-only',
  url: 'http://localhost/',
});
// Polyfill the globals the editor's DOM/effect code reaches for at load + run.
global.window = dom.window;
global.document = dom.window.document;
global.navigator = dom.window.navigator;
global.location = dom.window.location;
for (const k of [
  'Element', 'HTMLElement', 'Node', 'NodeList', 'HTMLCollection',
  'DocumentFragment', 'Text', 'Comment', 'Event', 'CustomEvent',
  'KeyboardEvent', 'MouseEvent', 'MutationObserver', 'getComputedStyle',
  'NodeFilter', 'DOMParser', 'XMLSerializer',
]) {
  if (dom.window[k] !== undefined) global[k] = dom.window[k];
}
// rAF shim (some effect/render code schedules on it)
global.requestAnimationFrame = (cb) => setTimeout(() => cb(Date.now()), 16);
global.cancelAnimationFrame = (id) => clearTimeout(id);

require('./target/node-tests.js');
