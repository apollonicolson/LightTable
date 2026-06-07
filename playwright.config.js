// Playwright config for LightTable Electron E2E.
// Drives the REAL editor (our Electron 42 binary + deploy/core) — the repeatable
// form of the manual CDP rank-1 verification. Run: npm run test:e2e
// (the script builds `shadow-cljs release app` first so the bundle exists).
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
});
