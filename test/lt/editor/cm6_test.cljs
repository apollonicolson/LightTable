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

(deftest cursor-is-the-empty-selection
  (let [s0 (cm6/make-state "ab\ncde\nf")]
    (is (= {:line 0 :ch 0} (cm6/cursor s0)) "fresh state cursor at doc start")
    (let [s1 (cm6/move-cursor s0 {:line 1 :ch 2})]
      (is (= 5 (cm6/cursor-offset s1)) "cursor offset after 'ab\\ncd'")
      (is (= {:line 1 :ch 2} (cm6/cursor s1)))
      (is (not (cm6/selection? s1)) "move-cursor leaves an empty selection"))
    (let [s2 (cm6/select-all s0)]
      (is (cm6/selection? s2))
      (is (= "ab\ncde\nf" (cm6/selected-text s2)) "select-all spans the whole doc"))))

(deftest whole-buffer-value
  (let [s0 (-> (cm6/make-state "hello world") (cm6/move-cursor {:line 0 :ch 5}))]
    (let [s1 (cm6/set-val s0 "new text")]
      (is (= "new text" (cm6/doc-string s1)))
      (is (= 0 (cm6/cursor-offset s1)) "set-val resets the cursor (CM5 setValue)"))
    (let [s2 (cm6/set-val-keep-cursor s0 "longer replacement text")]
      (is (= "longer replacement text" (cm6/doc-string s2)))
      (is (= 5 (cm6/cursor-offset s2)) "set-val-keep-cursor preserves the offset"))
    (let [s3 (cm6/set-val-keep-cursor (cm6/move-cursor s0 {:line 0 :ch 11}) "hi")]
      (is (= 2 (cm6/cursor-offset s3)) "cursor clamps to the new (shorter) length"))))

(deftest insert-and-replace-selection
  (let [s0 (cm6/make-state "hello world")]
    (let [s1 (-> s0 (cm6/move-cursor {:line 0 :ch 5}) (cm6/insert-at-cursor " there"))]
      (is (= "hello there world" (cm6/doc-string s1)))
      (is (= 11 (cm6/cursor-offset s1)) "cursor lands after the inserted text"))
    (let [s2 (-> s0 (cm6/set-selection {:line 0 :ch 0} {:line 0 :ch 5})
                 (cm6/replace-selection "goodbye"))]
      (is (= "goodbye world" (cm6/doc-string s2)))
      (is (= 7 (cm6/cursor-offset s2)) "cursor at end of replacement")
      (is (not (cm6/selection? s2)) "selection collapses after replace"))))

(deftest line-edits-and-accessors
  (let [s (cm6/make-state "ab\ncde\nf")]
    (is (= 0 (cm6/first-line s)))
    (is (= 2 (cm6/last-line s)))
    (let [s1 (cm6/set-line s 1 "XYZW")]
      (is (= "ab\nXYZW\nf" (cm6/doc-string s1)) "set-line replaces just that line"))))

(deftest get-char-around-cursor
  (let [s (-> (cm6/make-state "hello world") (cm6/move-cursor {:line 0 :ch 5}))]
    (is (= "o" (cm6/get-char s -1)) "1 char before the cursor (offset 5 → 'o')")
    (is (= "hello" (cm6/get-char s -5)))
    (is (= " " (cm6/get-char s 1)) "1 char after the cursor")
    (is (= " worl" (cm6/get-char s 5)))))

(deftest undo-redo-restores-document
  (let [s0 (cm6/make-state "hello")
        s1 (cm6/replace-range s0 5 5 " world")]
    (is (= "hello world" (cm6/doc-string s1)))
    (let [undone (cm6/undo s1)]
      (is (= "hello" (cm6/doc-string undone)) "undo reverts the edit")
      (let [redone (cm6/redo undone)]
        (is (= "hello world" (cm6/doc-string redone)) "redo re-applies it")))
    (is (= "hello" (cm6/doc-string (cm6/undo s0)))
        "undo with empty history is a no-op")))

(deftest generation-and-dirty
  (let [s0 (cm6/make-state "hello")
        g0 (cm6/->generation s0)]
    (is (not (cm6/dirty? s0 g0)) "clean against its own generation")
    (let [s1 (cm6/replace-range s0 5 5 "!")]
      (is (cm6/dirty? s1 g0) "an edit makes it dirty vs the old generation")
      (is (not (cm6/dirty? s1 (cm6/->generation s1))) "clean vs the new generation")
      (let [s2 (cm6/move-cursor s1 {:line 0 :ch 0})]
        (is (= (cm6/->generation s1) (cm6/->generation s2))
            "a pure cursor move does not bump the generation")))))
