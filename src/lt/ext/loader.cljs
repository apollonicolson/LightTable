(ns lt.ext.loader
  "Node-free CommonJS loader for Tier-A (sandboxed, no-Node) extension hosting
  (ADR 0012 phase 5b). Evaluates an extension's module source in a function scope
  with injected `require` / `module` / `exports` — no Node module system, no fs, no
  `Module._load` patch. The injected require returns the `vscode` shim; a *web*
  extension (uses only `vscode` + web APIs) needs nothing else, so it's the cleanest
  first sandboxed target. (The Node-surface shim that routes `fs`/`child_process`
  through the membrane is 5c.)

  This is how VSCode's own web-extension host loads extensions (eval-in-scope, not
  the Node loader). Pure JS evaluation (`new Function`); node-loadable + tested.
  In the real sandboxed renderer the host page's CSP must allow `unsafe-eval` — the
  OS sandbox, not CSP, is the security boundary.")

(defn make-require
  "A `require` for a sandboxed extension: `'vscode'` → the shim; anything else
  throws (Tier A has no Node). 5c replaces the throw with the brokered Node-surface
  shim routed over the membrane."
  [vscode-shim]
  (fn [id]
    (case id
      "vscode" vscode-shim
      (throw (js/Error. (str "module '" id "' is not available in the sandbox (no Node)"))))))

(defn load-commonjs
  "Evaluate CommonJS `code` with an injected `require`; return its `module.exports`.
  No Node — just a function scope."
  [code require-fn]
  (let [module #js {:exports #js {}}
        f      (js/Function. "require" "module" "exports" code)]
    (.call f js/undefined require-fn module (.-exports module))
    (.-exports module)))

(defn activate-extension
  "Load + activate a Tier-A extension from source. `context` is the ExtensionContext
  (built main-side). Returns `{:module :api}` (the activate() return value)."
  [code require-fn context]
  (let [m (load-commonjs code require-fn)]
    {:module m
     :api    (when (fn? (.-activate m)) ((.-activate m) context))}))
