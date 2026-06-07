(ns lt.editor.cm6.document-test
  "The falsifier gate for the CM6 linked-document design (ADR 0009): two views over
  one logical doc — a primary (history owner) + a history-free sibling. Edits in
  either must appear in both, and undo (routed to the primary) must revert in
  order across both views. This is the test the red-team said the design stands or
  falls on; the flag flip to :cm6 is gated on it."
  (:require [clojure.test :refer [deftest is]]
            ["@codemirror/commands" :as cm-commands]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.view :as view]
            [lt.editor.cm6.document :as doc]))

(def ^:private isolate (.-isolateHistory cm-commands))

(defn- host []
  (doto (.createElement js/document "div")
    (->> (.appendChild (.-body js/document)))))

(defn- text [v] (cm6/doc-string (view/view-state v)))
;; isolateHistory "before" simulates a DISTINCT user action (a fresh undo group) —
;; rapid same-group edits coalesce in CM6 (correct for typing), so the test marks
;; each append as its own action to exercise cross-view shared-history boundaries.
(defn- append! [v s]
  (view/dispatch! v #js {:changes #js {:from (cm6/doc-length (view/view-state v)) :insert s}
                         :annotations (.of isolate "before")}))

(deftest edits-forward-both-directions
  (let [d (doc/make-doc)
        main (doc/attach! d (host) {:primary? true :doc-string "shared"})
        sib  (doc/attach! d (host) {})]
    (is (= "shared" (text main)))
    (is (= "shared" (text sib)) "sibling seeds from canonical")
    (append! main "-A")
    (is (= "shared-A" (text main)))
    (is (= "shared-A" (text sib)) "edit in primary appears in sibling")
    (append! sib "-B")
    (is (= "shared-A-B" (text sib)))
    (is (= "shared-A-B" (text main)) "edit in sibling appears in primary")))

(deftest shared-undo-across-views
  (let [d (doc/make-doc)
        main (doc/attach! d (host) {:primary? true :doc-string "shared"})
        sib  (doc/attach! d (host) {})]
    (append! main "-A")            ; "shared-A"
    (append! sib "-B")             ; "shared-A-B"  (sibling edit → recorded in primary history)
    (doc/undo! d)
    (is (= "shared-A" (text main)) "undo reverts the sibling's edit (shared history)")
    (is (= "shared-A" (text sib)) "...in both views")
    (doc/undo! d)
    (is (= "shared" (text main)) "second undo reverts the primary's edit, in order")
    (is (= "shared" (text sib)))
    (doc/redo! d)
    (is (= "shared-A" (text main)) "redo reapplies")
    (is (= "shared-A" (text sib)))))

(deftest canonical-state-is-the-primary
  (let [d (doc/make-doc)
        main (doc/attach! d (host) {:primary? true :doc-string "x"})]
    (is (= "x" (cm6/doc-string (doc/canonical-state d))))
    (append! main "y")
    (is (= "xy" (doc/canonical-text d)) "canonical tracks the primary view's state")))
