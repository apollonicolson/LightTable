(ns lt.editor.seam-parity-test
  "The CM6 side of the ADR 0008 parity gate, headless.

  e2e/editor-parity.spec.js pins lt.objs.editor's observable behavior on the live
  CM5 editor (rank-1, Electron). This proves the CM6 backend produces the SAME
  observable results for the same operations — exercising the IEditorBackend
  protocol (what lt.objs.editor's capability fns delegate to) on a Cm6Backend over
  a jsdom EditorView.

  Why the protocol and not lt.objs.editor directly: lt.objs.editor can't load in
  node — lt.objs.context registers a behavior at load that reaches a browser-only
  global (Cowboy, via lt.util.js/debounce). So the seam fns themselves are
  verified through the live CM5 editor (Electron parity suite); here we verify the
  backend they delegate to. editor.cljs's delegation is one-line `(be/-x (backend
  e) …)` per fn, so backend-correct + CM5-seam-unchanged ⇒ CM6-seam-correct."
  (:require [clojure.test :refer [deftest is]]
            [lt.editor.backend :as be]
            [lt.editor.cm6.view :as view]))

(defn- cm6-backend
  "A Cm6Backend over a live (jsdom) EditorView — what editor/backend returns for a
  CM6 editor."
  []
  (let [host (doto (.createElement js/document "div")
               (->> (.appendChild (.-body js/document))))]
    (be/cm6-backend (view/create-view host {:doc ""}))))

(deftest backend-value-and-lines
  (let [b (cm6-backend)]
    (be/-set-val b "ab\ncde\nf")
    (is (= "ab\ncde\nf" (be/-value b)))
    (is (= 3 (be/-line-count b)))
    (is (= "cde" (be/-line b 1)))
    (is (= 0 (be/-first-line b)))
    (is (= 2 (be/-last-line b)))))

(deftest backend-cursor
  (let [b (cm6-backend)]
    (be/-set-val b "ab\ncde\nf")
    (is (= {:line 0 :ch 0} (be/-cursor b nil)) "set-val resets the cursor")
    (be/-move-cursor b {:line 1 :ch 2})
    (is (= {:line 1 :ch 2} (be/-cursor b nil)))
    (is (= "d" (be/-get-char b -1)))
    (is (= "e" (be/-get-char b 1)))))

(deftest backend-selection
  (let [b (cm6-backend)]
    (be/-set-val b "ab\ncde\nf")
    (is (not (be/-selection? b)))
    (be/-set-selection b {:line 0 :ch 0} {:line 0 :ch 2})
    (is (be/-selection? b))
    (is (= "ab" (be/-selection b)))
    (is (= {:from {:line 0 :ch 0} :to {:line 0 :ch 2}} (be/-selection-bounds b)))
    ;; CM5 -cursor "start"/"end" address the selection ends — needed by selection-bounds parity.
    (is (= {:line 0 :ch 0} (be/-cursor b "start")))
    (is (= {:line 0 :ch 2} (be/-cursor b "end")))
    (be/-replace-selection b "ZZ" :end)
    (is (= "ZZ\ncde\nf" (be/-value b)))))

(deftest backend-edit-and-history
  (let [b (cm6-backend)]
    (be/-set-val b "ab\ncde\nf")
    (be/-replace b {:line 1 :ch 0} {:line 1 :ch 2} "XY")
    (is (= "ab\nXYe\nf" (be/-value b)))
    (be/-move-cursor b {:line 0 :ch 2})
    (be/-insert-at-cursor b "!!")
    (is (= "ab!!\nXYe\nf" (be/-value b)))
    (let [g (be/-generation b)]
      (be/-replace b {:line 0 :ch 0} {:line 0 :ch 0} "Z")
      (is (be/-dirty? b g) "edit dirties vs the prior generation"))
    (be/-undo b)
    (is (= "ab!!\nXYe\nf" (be/-value b)) "undo reverts the last edit")
    (be/-redo b)
    (is (= "Zab!!\nXYe\nf" (be/-value b)) "redo reapplies it")))
