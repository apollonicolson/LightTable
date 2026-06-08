// Phase-3b fixture: exercises vscode.window + vscode.workspace.
const vscode = require('vscode');

function activate(context) {
  vscode.window.showInformationMessage('hi from ext');

  const tabSize = vscode.workspace.getConfiguration('editor').get('tabSize', 4);

  let changes = 0;
  context.subscriptions.push(
    vscode.workspace.onDidChangeTextDocument((e) => { changes += e.contentChanges.length; })
  );

  return { tabSize, getChanges: () => changes };
}

function deactivate() {}
module.exports = { activate, deactivate };
