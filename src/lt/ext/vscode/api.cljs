(ns lt.ext.vscode.api
  "Phase 2 of the VSCode extension host (ADR 0011): assemble the `vscode` module
  object injected into extensions (what `require('vscode')` returns). Phase 2 = the
  core value types + commands; window/workspace/languages namespaces are added in
  later phases. Node-loadable + tested."
  (:require [lt.ext.vscode.types :as types]
            [lt.ext.vscode.commands :as commands]
            [lt.ext.vscode.window :as window]
            [lt.ext.vscode.workspace :as workspace]
            [lt.ext.vscode.languages :as languages]))

(defn make-vscode
  "Build the `vscode` shim object for an extension `principal` (used by the gated
  effect APIs, e.g. workspace.fs). The no-arg form is principal-less (effectful
  APIs default-deny). Per-extension state arrives via the ExtensionContext."
  ([] (make-vscode nil))
  ([principal]
  #js {:Position      types/Position
       :Range         types/make-range
       :Uri           types/Uri
       :Disposable    types/Disposable
       :EventEmitter  types/event-emitter
       :commands      (commands/ns-object)
       :window        (window/ns-object)
       :workspace     (workspace/ns-object principal)
       :languages     (languages/ns-object)
       ;; language-feature value types
       :CompletionItem      types/completion-item
       :CompletionItemKind  types/CompletionItemKind
       :Diagnostic          types/diagnostic
       :DiagnosticSeverity  types/DiagnosticSeverity
       :Hover               types/hover
       :Location            types/location
       :MarkdownString      types/markdown-string
       :SnippetString       types/snippet-string
       :WorkspaceEdit       types/work-space-edit
       :CodeAction          types/code-action
       :CodeActionKind      types/CodeActionKind}))
