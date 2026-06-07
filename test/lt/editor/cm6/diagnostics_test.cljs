(ns lt.editor.cm6.diagnostics-test
  "Falsifier gate for lt.editor.cm6.diagnostics: LSP diagnostic ranges map to the
  right CM6 offsets + severity classes, render into the layer, and track through
  edits (ADR 0010 slice 1). Pure EditorState — no view, no server."
  (:require [clojure.test :refer [deftest is]]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.diagnostics :as diag]))

(defn- state [s] (cm6/make-state s #js [(:field diag/layer)]))

;; LSP diagnostic: 0-based {:line :character} range.
(defn- d [l0 c0 l1 c1 severity]
  {:range {:start {:line l0 :character c0} :end {:line l1 :character c1}}
   :severity severity :message "x"})

(deftest range-maps-lsp-to-offset
  (let [s (state "abc\ndefg")]
    ;; line1 ch1..line1 ch3 = "ef" → offsets 5..7
    (is (= {:from 5 :to 7} (diag/diagnostic-range s (d 1 1 1 3 1))))
    (is (= {:from 0 :to 3} (diag/diagnostic-range s (d 0 0 0 3 2))))))

(deftest severity-classes
  (is (= "cm-diag-error"   (diag/severity-class 1)))
  (is (= "cm-diag-warning" (diag/severity-class 2)))
  (is (= "cm-diag-info"    (diag/severity-class 3)))
  (is (= "cm-diag-hint"    (diag/severity-class 4))))

(deftest renders-into-layer
  (let [s (-> (state "abc\ndefg")
              (diag/apply-diagnostics [(d 0 0 0 3 1) (d 1 1 1 3 2)]))]
    (is (= 2 (diag/count-diagnostics s)) "two diagnostics → two marks")
    (is (= {:from 0 :to 3} ((:tracked-range diag/layer) s [:lt.editor.cm6.diagnostics/diag 0])))
    (is (= {:from 5 :to 7} ((:tracked-range diag/layer) s [:lt.editor.cm6.diagnostics/diag 1])))))

(deftest replace-clears-previous
  (let [s (-> (state "abcdef")
              (diag/apply-diagnostics [(d 0 0 0 3 1) (d 0 3 0 6 2)])
              (diag/apply-diagnostics [(d 0 0 0 2 1)]))]
    (is (= 1 (diag/count-diagnostics s)) "re-applying replaces, not appends")))

(deftest tracks-through-edit
  (let [s (-> (state "abcdef")
              (diag/apply-diagnostics [(d 0 2 0 4 1)])      ; marks "cd" → 2..4
              (.update #js {:changes #js {:from 0 :to 0 :insert "XX"}})
              (.-state))]
    (is (= {:from 4 :to 6} ((:tracked-range diag/layer) s [:lt.editor.cm6.diagnostics/diag 0]))
        "diagnostic decoration shifts with an insert before it")))

(deftest zero-width-diagnostic-squiggles-one-char
  (let [s (-> (state "abcdef") (diag/apply-diagnostics [(d 0 2 0 2 1)]))]
    (is (= {:from 2 :to 3} ((:tracked-range diag/layer) s [:lt.editor.cm6.diagnostics/diag 0]))
        "empty LSP range → a one-char mark (marks can't be zero-width)")))
