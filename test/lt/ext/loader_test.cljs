(ns lt.ext.loader-test
  "Phase 5b gate (ADR 0012): a web extension runs with ZERO Node — loaded via a
  function scope with an injected `require` that yields only the `vscode` shim, and
  it drives the editor through that shim (commands). Tier-A's defining capability."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.ext.loader :as loader]
            [lt.ext.vscode.api :as api]
            [lt.ext.vscode.commands :as commands]))

(def ^:private web-ext-code
  "const vscode = require('vscode');
   exports.activate = (ctx) => {
     const pos = new vscode.Position(1, 2);
     ctx.subscriptions.push(
       vscode.commands.registerCommand('web.hi', (who) => 'hi ' + (who || 'world') + ' @' + pos.line));
     return { ok: true, line: pos.line };
   };
   exports.deactivate = () => {};")

(deftest web-extension-runs-with-no-node
  (commands/reset-registry!)
  (let [shim (api/make-vscode "ext.web")
        ctx  #js {:subscriptions #js []}
        {:keys [api]} (loader/activate-extension web-ext-code (loader/make-require shim) ctx)]
    (is (true? (.-ok api)) "activate ran in a function scope — no Node, only injected vscode")
    (is (= 1 (.-line api)) "vscode value types worked (new vscode.Position)")
    (is (= 1 (.-length (.-subscriptions ctx))) "a disposable was registered")
    (is (commands/has-command? "web.hi") "the command reached the shared registry via the shim")))

(deftest only-vscode-is-available
  (let [req (loader/make-require (api/make-vscode "ext.web"))]
    (is (some? (req "vscode")) "vscode resolves")
    (is (thrown? js/Error (req "fs")) "no Node in the sandbox — fs is denied (5c brokers it)")
    (is (thrown? js/Error (req "child_process")) "no ambient child_process")))
