// Phase-5-prep fixture: exercises the GATED vscode.workspace.fs. Reads go through
// the capability gate — denied by default, allowed after a grant.
const vscode = require('vscode');
function activate(context) {
  return {
    tryRead: (uriStr) =>
      vscode.workspace.fs.readFile(vscode.Uri.file(uriStr))
        .then((bytes) => new TextDecoder().decode(bytes))
        .catch((e) => 'DENIED:' + e.message),
  };
}
function deactivate() {}
module.exports = { activate, deactivate };
