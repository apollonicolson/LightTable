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

(deftest positions-lines-and-ranges
  ;; "ab\ncde\nf": line0=ab(0..2) \n(2) line1=cde(3..6) \n(6) line2=f(7)
  (let [s (cm6/make-state "ab\ncde\nf")]
    (is (= 3 (cm6/line-count s)))
    (is (= "cde" (cm6/line-text s 1)))
    (is (= 3 (cm6/line-length s 1)))
    (is (= 3 (cm6/pos->offset s {:line 1 :ch 0})) "line 1 starts after 'ab\\n'")
    (is (= {:line 1 :ch 1} (cm6/offset->pos s 4)) "offset 4 → line 1, ch 1")
    (is (= "cd" (cm6/range-text s {:line 1 :ch 0} {:line 1 :ch 2})) "range across positions")))

(deftest selection-lives-in-state
  (let [s0 (cm6/make-state "hello world")
        s1 (cm6/set-selection s0 {:line 0 :ch 0} {:line 0 :ch 5})]
    (is (not (cm6/selection? s0)) "fresh state has empty selection")
    (is (cm6/selection? s1))
    (is (= "hello" (cm6/selected-text s1)))
    (is (= {:from {:line 0 :ch 0} :to {:line 0 :ch 5}} (cm6/selection-bounds s1)))))

(deftest position-based-replace
  (let [s (-> (cm6/make-state "hello world")
              (cm6/replace {:line 0 :ch 0} {:line 0 :ch 5} "goodbye"))]
    (is (= "goodbye world" (cm6/doc-string s)) "replace by {:line :ch} positions")))
