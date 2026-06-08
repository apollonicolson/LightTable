(ns lt.ext.vscode.languages-test
  "Phase 4 gate (ADR 0011): vscode.languages provider registry + invocation +
  selector matching + DiagnosticCollection + the result→CM6-renderer mappings."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.vscode.languages :as l]
            [lt.ext.vscode.types :as t]
            [lt.ext.vscode.document :as doc]))

(defn- doc-clj [text] (doc/make-text-document {:uri "file:///a.clj" :languageId "clojure" :text text}))
(defn- p0 [] (t/->Position 0 0))

(deftest completion-provider-invoked-and-mapped
  (l/reset-languages!)
  (.registerCompletionItemProvider
   (l/ns-object) "clojure"
   #js {:provideCompletionItems
        (fn [_doc _pos _t _c]
          (let [ci (t/completion-item "defn" (.-Function t/CompletionItemKind))]
            (set! (.-insertText ci) "defn")
            #js [ci]))})
  (async done
    (.then (l/provide-completions (doc-clj "x") (p0))
           (fn [items]
             (is (= 1 (.-length items)))
             (let [h (aget (l/completion-items->hints items) 0)]
               (is (= "defn" (.-completion h)))
               (is (= "defn" (.-text h))))
             (done)))))

(deftest selector-filters-by-language
  (l/reset-languages!)
  (.registerHoverProvider (l/ns-object) "python"
                          #js {:provideHover (fn [& _] (t/hover "py" nil))})
  (async done
    (.then (l/provide-hover (doc-clj "x") (p0))
           (fn [h] (is (nil? h) "python provider not invoked on a clojure doc") (done)))))

(deftest hover-mapped-to-text
  (l/reset-languages!)
  (.registerHoverProvider (l/ns-object) "clojure"
                          #js {:provideHover (fn [& _] (t/hover (t/markdown-string "**doc**") nil))})
  (async done
    (.then (l/provide-hover (doc-clj "x") (p0))
           (fn [h] (is (= "**doc**" (l/hover->text h))) (done)))))

(deftest definition-mapped-to-location
  (l/reset-languages!)
  (.registerDefinitionProvider
   (l/ns-object) "clojure"
   #js {:provideDefinition (fn [& _] (t/location (.file t/Uri "/p/t.clj") (t/make-range 4 2 4 8)))})
  (async done
    (.then (l/provide-definition (doc-clj "x") (p0))
           (fn [d]
             (is (= {:uri "file:///p/t.clj" :line 4 :character 2} (l/definition->location d)))
             (done)))))

(deftest diagnostic-collection-emits-lsp-shape
  (l/reset-languages!)
  (let [captured (atom nil)]
    (l/set-diagnostic-sink! (fn [uri diags] (reset! captured [uri diags])))
    (let [dc (.createDiagnosticCollection (l/ns-object) "x")
          d  (t/diagnostic (t/make-range 0 0 0 3) "boom" (.-Error t/DiagnosticSeverity))]
      (.set dc (.file t/Uri "/a.clj") #js [d])
      (is (= 1 (._count dc)))
      (let [[uri diags] @captured
            ld (first diags)]
        (is (= "file:///a.clj" uri))
        (is (= 1 (:severity ld)) "VSCode Error 0 → LSP 1")
        (is (= "boom" (:message ld)))
        (is (= 0 (get-in ld [:range :start :line])))
        (is (= 3 (get-in ld [:range :end :character]))))
      (.clear dc)
      (is (= 0 (._count dc))))))
