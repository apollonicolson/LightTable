(ns lt.ext.vscode.document-test
  "Phase 3 gate (ADR 0011): the TextDocument value type + the doc-sync crux
  (CM6 ChangeSet → TextDocumentContentChangeEvent[])."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.ext.vscode.document :as doc]
            [lt.ext.vscode.types :as t]
            [lt.editor.cm6 :as cm6]))

(deftest text-document-basics
  (let [d (doc/make-text-document {:uri "file:///a.clj" :languageId "clojure"
                                   :version 3 :text "abc\ndefg\nh"})]
    (is (= "clojure" (.-languageId d)))
    (is (= 3 (.-version d)))
    (is (= 3 (.-lineCount d)))
    (is (= "abc\ndefg\nh" (.getText d)))
    (is (= "defg" (.-text (.lineAt d 1))))
    (is (= 4 (.offsetAt d (t/->Position 1 0))) "line 1 starts after 'abc\\n'")
    (let [p (.positionAt d 6)]
      (is (= 1 (.-line p))) (is (= 2 (.-character p))) "offset 6 = line1 char2")
    (is (= "bc\nd" (.getText d (t/make-range 0 1 1 1))) "getText(range)")))

(deftest empty-and-clamping
  (let [d (doc/make-text-document {:uri "file:///e" :text ""})]
    (is (= 1 (.-lineCount d)))
    (is (= "" (.getText d)))
    (let [p (.positionAt d 999)] (is (= 0 (.-line p))) (is (= 0 (.-character p)))
      "out-of-range offset clamps")))

(deftest doc-sync-from-cm6-changeset
  (let [text  "abc\ndef"
        old   (doc/make-text-document {:uri "file:///a" :text text})
        state (cm6/make-state text)
        ;; replace 'b' (offset 1..2) with "XY"
        tr    (.update state #js {:changes #js {:from 1 :to 2 :insert "XY"}})
        evs   (vec (array-seq (doc/content-changes old (.-changes tr))))]
    (is (= 1 (count evs)))
    (let [e (first evs)]
      (is (= 1 (.-rangeOffset e)))
      (is (= 1 (.-rangeLength e)) "replaced one char")
      (is (= "XY" (.-text e)))
      (is (= 0 (.-line (.-start (.-range e)))))
      (is (= 1 (.-character (.-start (.-range e)))) "range is in the OLD doc")
      (is (= 2 (.-character (.-end (.-range e))))))))
