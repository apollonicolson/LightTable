(ns lt.lsp.completion-test
  "Falsifier gate for lt.lsp.completion (ADR 0010 slice 3): LSP completion results
  map to hint items (JS objects with .completion/.text), with insertText fallback,
  textEdit fallback, and sortText ordering."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.lsp.completion :as comp]))

(defn- completions [result] (vec (array-seq (comp/lsp-items->hints result))))
(defn- field [h k] (aget h k))

(deftest maps-completionlist-items
  (let [hs (completions {:isIncomplete false
                         :items [{:label "defn" :kind 3 :insertText "defn"}]})]
    (is (= 1 (count hs)))
    (is (= "defn" (field (first hs) "completion")) "insertText is what gets inserted")
    (is (= "defn" (field (first hs) "text")) "label is the display text")
    (is (= "function" (field (first hs) "kind")) "kind 3 → function")))

(deftest accepts-bare-item-vector
  (let [hs (completions [{:label "x"} {:label "y"}])]
    (is (= 2 (count hs)))
    (is (= "x" (field (first hs) "completion")) "no insertText → label is inserted")))

(deftest sorts-by-sorttext-then-label
  (let [hs (completions [{:label "defn" :sortText "1"}
                         {:label "def" :sortText "0"}])]
    (is (= ["def" "defn"] (map #(field % "text") hs))
        "sortText '0' orders before '1' regardless of input order")))

(deftest textedit-newtext-fallback
  (let [hs (completions [{:label "foo" :textEdit {:newText "foobar"}}])]
    (is (= "foobar" (field (first hs) "completion"))
        "textEdit.newText wins over label when no insertText")))

(deftest empty-and-nil-are-safe
  (is (= [] (completions nil)))
  (is (= [] (completions {:items []})))
  (is (= [] (completions []))))
