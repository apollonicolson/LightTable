(ns lt.editor.cm6-test
  "Slice-1 gate for the CM6 seam: @codemirror/state imports and its immutable
  state / transaction model runs under our shadow/node toolchain. Proves CM6 is
  adopted before the (multi-turn) editor.cljs port builds on it."
  (:require [clojure.test :refer [deftest is]]
            [lt.editor.cm6 :as cm6]))

(deftest immutable-state-and-transactions
  (let [s0 (cm6/make-state "hello")]
    (is (= "hello" (cm6/doc-string s0)) "state seeded with the document")
    (is (= 5 (cm6/doc-length s0)))
    (let [s1 (cm6/replace-range s0 5 5 " world")]
      (is (= "hello world" (cm6/doc-string s1)) "a transaction yields a new state")
      (is (= "hello" (cm6/doc-string s0))
          "the original state is unchanged — CM6 state is immutable/epochal"))))
