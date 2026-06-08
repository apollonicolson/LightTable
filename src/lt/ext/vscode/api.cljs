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
  "Build the `vscode` shim object. Shared across extensions (per-extension state
  arrives via the ExtensionContext, not this module — matching VSCode)."
  []
  #js {:Position      types/Position
       :Range         types/make-range
       :Uri           types/Uri
       :Disposable    types/Disposable
       :EventEmitter  types/event-emitter
       :commands      (commands/ns-object)
       :window        (window/ns-object)
       :workspace     (workspace/ns-object)
       :languages     (languages/ns-object)
       ;; language-feature value types
       :CompletionItem      types/completion-item
       :CompletionItemKind  types/CompletionItemKind
       :Diagnostic          types/diagnostic
       :DiagnosticSeverity  types/DiagnosticSeverity
       :Hover               types/hover
       :Location            types/location
       :MarkdownString      types/markdown-string})
