// Phase-2 fixture: exercises the injected `vscode` shim — value types + commands.
const vscode = require('vscode');

function activate(context) {
  const pos = new vscode.Position(1, 4);
  const range = new vscode.Range(0, 0, 2, 3);
  const uri = vscode.Uri.file('/proj/x.clj');

  const disp = vscode.commands.registerCommand('cmd.hello', (who) => 'hello ' + (who || 'world'));
  context.subscriptions.push(disp);

  return {
    posLine: pos.line,
    posChar: pos.character,
    rangeContains: range.contains(pos),     // (1,4) within (0,0)-(2,3)
    uriScheme: uri.scheme,
    uriFsPath: uri.fsPath,
    isRange: range instanceof vscode.Range,
  };
}

function deactivate() {}
module.exports = { activate, deactivate };
