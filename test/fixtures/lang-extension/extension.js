// Phase-4 fixture: a language provider drives completions + diagnostics through
// the CM6 renderers (the convergence with ADR 0010 slices 1-4).
const vscode = require('vscode');

function activate(context) {
  context.subscriptions.push(
    vscode.languages.registerCompletionItemProvider('clojure', {
      provideCompletionItems(doc, pos) {
        const a = new vscode.CompletionItem('defn', vscode.CompletionItemKind.Function);
        a.insertText = 'defn';
        const b = new vscode.CompletionItem('defmacro');
        return [a, b];
      },
    })
  );

  const dc = vscode.languages.createDiagnosticCollection('lang-ext');
  return {
    setDiag(uriStr) {
      const uri = vscode.Uri.parse(uriStr);
      const d = new vscode.Diagnostic(new vscode.Range(0, 0, 0, 3), 'ext diag',
                                      vscode.DiagnosticSeverity.Error);
      dc.set(uri, [d]);
    },
  };
}

function deactivate() {}
module.exports = { activate, deactivate };
