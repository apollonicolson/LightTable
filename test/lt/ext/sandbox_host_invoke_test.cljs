(ns lt.ext.sandbox-host-invoke-test
  "Phase 5b-2: the membrane INVOKE path through the assembled sandbox host (the other
  half of the protocol). A no-Node extension registers completion + hover providers;
  the editor syncs a document (`:doc`) and asks for features (`:invoke`); the host
  runs the providers on its LOCAL mirror and the mapped, serializable results cross
  back. Completes the host dispatcher's language-feature half."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.membrane :as m]
            [lt.ext.sandbox-host :as host]
            [lt.ext.vscode.commands :as commands]
            [lt.ext.vscode.languages :as languages]
            [lt.sec.gate :as gate]))

(def ^:private lang-ext
  "const vscode = require('vscode');
   exports.activate = (ctx) => {
     vscode.languages.registerCompletionItemProvider('*', {
       provideCompletionItems: (doc, pos) => [{ label: 'hello', insertText: 'hello' }, { label: 'help' }]
     });
     vscode.languages.registerHoverProvider('*', {
       provideHover: (doc, pos) => ({ contents: 'docs for ' + doc.getText().slice(0, 3) })
     });
     return { ok: true };
   };")

(deftest invoke-completion-and-hover-through-the-host
  (gate/reset-gate!)
  (commands/reset-registry!)
  (languages/reset-languages!)
  (let [[main-t host-t] (m/loopback)
        main    (m/endpoint main-t)
        host-ep (m/endpoint host-t)]
    (host/start! host-ep "ext.lang")
    (async done
      ;; activate (request/reply) — registers the providers in the host
      (let [act ((:request main) {:t :activate :id "ext.lang" :code lang-ext})]
        (is (true? (.-ok (:data act))) "language extension activated (no Node)"))
      ;; sync a document into the host's local mirror
      ((:notify main) {:t :doc :op :open :uri "file:///x.txt" :text "abcdef"})
      ;; invoke completion — provider runs in the host, serializable data crosses back
      (let [res ((:request main) {:t :invoke :feature :completion :uri "file:///x.txt" :line 0 :character 0})]
        (is (= ["hello" "help"] (mapv :text (:data res)))
            "completion provider ran in the host; results crossed back as clj data")
        (is (= "hello" (:completion (first (:data res)))) "insertText mapped"))
      ;; invoke hover — runs against the host's LOCAL doc mirror (no per-read RPC)
      (let [res ((:request main) {:t :invoke :feature :hover :uri "file:///x.txt" :line 0 :character 0})]
        (is (= "docs for abc" (:data res))
            "hover provider read the host-local document mirror"))
      (done))))
