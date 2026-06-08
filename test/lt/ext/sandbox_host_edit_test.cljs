(ns lt.ext.sandbox-host-edit-test
  "C — extension language-feature breadth: document formatting (TextEdit[] → CM6
  change specs via the host's local offsetAt) + signature help (pure data), through
  the assembled sandbox host. Formatting proves the reusable edit-apply primitive
  (the host converts edits to {:from :to :insert} so main applies directly)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.membrane :as m]
            [lt.ext.sandbox-host :as host]
            [lt.ext.vscode.commands :as commands]
            [lt.ext.vscode.languages :as languages]
            [lt.sec.gate :as gate]))

(def ^:private edit-ext
  "const vscode = require('vscode');
   exports.activate = (ctx) => {
     vscode.languages.registerDocumentFormattingEditProvider('*', {
       provideDocumentFormattingEdits: (doc, opts) => [
         { range: { start: { line: 0, character: 0 }, end: { line: 0, character: 5 } }, newText: 'HELLO' }
       ]
     });
     vscode.languages.registerSignatureHelpProvider('*', {
       provideSignatureHelp: (doc, pos) => ({
         activeSignature: 0, activeParameter: 1,
         signatures: [ { label: 'greet(name, n)', documentation: 'greets',
                         parameters: [ { label: 'name' }, { label: 'n' } ] } ]
       })
     });
     return { ok: true };
   };")

(deftest formatting-and-signature-help-through-the-host
  (gate/reset-gate!)
  (commands/reset-registry!)
  (languages/reset-languages!)
  (let [[main-t host-t] (m/loopback)
        main    (m/endpoint main-t)
        host-ep (m/endpoint host-t)]
    (host/start! host-ep "ext.edit")
    (async done
      (let [act ((:request main) {:t :activate :id "ext.edit" :code edit-ext})]
        (is (true? (.-ok (:data act))) "edit extension activated"))
      ((:notify main) {:t :doc :op :open :uri "file:///y.txt" :text "hello world"})
      ;; formatting → TextEdit converted to a CM6 change spec on the host (offsetAt)
      (let [res ((:request main) {:t :invoke :feature :format :uri "file:///y.txt" :line 0 :character 0})
            chs (:data res)]
        (is (= [{:from 0 :to 5 :insert "HELLO"}] chs)
            "TextEdit → {:from :to :insert} via the host's local offsetAt"))
      ;; signature help → serializable data
      (let [res ((:request main) {:t :invoke :feature :signature :uri "file:///y.txt" :line 0 :character 6})
            sh  (:data res)]
        (is (= 1 (:active-parameter sh)) "active parameter crossed back")
        (is (= "greet(name, n)" (:label (first (:signatures sh)))) "signature label crossed back")
        (is (= ["name" "n"] (mapv :label (:parameters (first (:signatures sh))))) "parameters mapped"))
      (done))))
