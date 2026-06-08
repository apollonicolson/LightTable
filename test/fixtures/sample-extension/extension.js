// Minimal sample VSCode extension for the phase-1 host skeleton test.
// No `require('vscode')` yet — the API shim is a later phase; this exercises the
// manifest → activate(context) → subscriptions → deactivate lifecycle.
const calls = { activated: 0, deactivated: 0, disposed: 0 };

function activate(context) {
  calls.activated++;
  context.subscriptions.push({ dispose() { calls.disposed++; } });
  return { calls, ping: () => 'pong' };
}

function deactivate() { calls.deactivated++; }

module.exports = { activate, deactivate, calls };
