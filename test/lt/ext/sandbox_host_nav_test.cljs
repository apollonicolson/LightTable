(ns lt.ext.sandbox-host-nav-test
  "C — extension language-feature breadth: document symbols (outline) + references
  (find-all-refs) through the assembled sandbox host, same provider→invoke→data
  pattern as completion/hover/def. A no-Node extension registers both providers; the
  editor invokes over the membrane; serializable clj nav data crosses back."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.membrane :as m]
            [lt.ext.sandbox-host :as host]
            [lt.ext.vscode.commands :as commands]
            [lt.ext.vscode.languages :as languages]
            [lt.sec.gate :as gate]))

(def ^:private nav-ext
  "const vscode = require('vscode');
   exports.activate = (ctx) => {
     vscode.languages.registerDocumentSymbolProvider('*', {
       provideDocumentSymbols: (doc) => [
         { name: 'greet', kind: 11, range: { start: { line: 1, character: 0 }, end: { line: 1, character: 9 } },
           children: [ { name: 'n', kind: 13, range: { start: { line: 1, character: 13 }, end: { line: 1, character: 14 } } } ] }
       ]
     });
     vscode.languages.registerReferenceProvider('*', {
       provideReferences: (doc, pos, ctx) => [
         { uri: 'file:///x.clj', range: { start: { line: 1, character: 6 }, end: { line: 1, character: 11 } } },
         { uri: 'file:///x.clj', range: { start: { line: 2, character: 1 }, end: { line: 2, character: 6 } } }
       ]
     });
     return { ok: true };
   };")

(deftest document-symbols-and-references-through-the-host
  (gate/reset-gate!)
  (commands/reset-registry!)
  (languages/reset-languages!)
  (let [[main-t host-t] (m/loopback)
        main    (m/endpoint main-t)
        host-ep (m/endpoint host-t)]
    (host/start! host-ep "ext.nav")
    (async done
      (let [act ((:request main) {:t :activate :id "ext.nav" :code nav-ext})]
        (is (true? (.-ok (:data act))) "nav extension activated"))
      ((:notify main) {:t :doc :op :open :uri "file:///x.clj" :text "(ns x)\n(defn greet [n] n)\n(greet 1)"})
      ;; document symbols (hierarchical outline)
      (let [res ((:request main) {:t :invoke :feature :symbols :uri "file:///x.clj" :line 0 :character 0})
            syms (:data res)]
        (is (= "greet" (:name (first syms))) "document symbol crossed back")
        (is (= 1 (:line (first syms))) "symbol range mapped")
        (is (= "n" (:name (first (:children (first syms))))) "hierarchical children preserved"))
      ;; references (flattened locations)
      (let [res ((:request main) {:t :invoke :feature :references :uri "file:///x.clj" :line 1 :character 6})
            refs (:data res)]
        (is (= 2 (count refs)) "both references crossed back")
        (is (= [1 2] (mapv :line refs)) "reference locations mapped"))
      (done))))
