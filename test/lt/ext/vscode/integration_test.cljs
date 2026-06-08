(ns lt.ext.vscode.integration-test
  "Phase 2 gate (ADR 0011): a real fixture extension `require('vscode')`s the
  injected shim — constructs Position/Range/Uri, registers a command — and the host
  can executeCommand it. End-to-end through the module-loader injection, no editor."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.host :as host]
            [lt.ext.vscode.api :as api]
            [lt.ext.vscode.commands :as c]))

(deftest extension-drives-the-vscode-shim
  (c/reset-registry!)
  (host/install-vscode! (api/make-vscode))
  (let [desc    (host/read-manifest "test/fixtures/cmd-extension")
        active  (host/activate! desc)
        api-obj (:api active)]
    ;; value types worked INSIDE the extension via require('vscode')
    (is (= 1 (.-posLine api-obj)))
    (is (= 4 (.-posChar api-obj)))
    (is (true? (.-rangeContains api-obj)) "range.contains(pos) inside the ext")
    (is (= "file" (.-uriScheme api-obj)))
    (is (= "/proj/x.clj" (.-uriFsPath api-obj)))
    (is (true? (.-isRange api-obj)) "instanceof vscode.Range holds across the overload")
    ;; the command the extension registered is callable through the registry
    (async done
      (.then (c/execute-command "cmd.hello" "ext")
             (fn [r]
               (is (= "hello ext" r))
               (host/deactivate! active)
               (is (not (c/has-command? "cmd.hello")) "deactivate disposed the registration")
               (done))))))
