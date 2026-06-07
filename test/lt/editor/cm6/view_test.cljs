(ns lt.editor.cm6.view-test
  "The CM6 EditorView mounts, dispatches edits, and exposes its state to the cm6
  read accessors — under jsdom (the node-test harness). Pixel-measurement is not
  covered here (needs a real browser); state + DOM lifecycle is."
  (:require [clojure.test :refer [deftest is]]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.view :as view]))

(defn- host []
  (let [el (.createElement js/document "div")]
    (.appendChild (.-body js/document) el)
    el))

(deftest view-mounts-and-exposes-state
  (let [v (view/create-view (host) {:doc "hello"})]
    (is (= "hello" (cm6/doc-string (view/view-state v))) "view-state feeds cm6 accessors")
    (is (.contains (.-body js/document) (view/dom v)) "view is attached to the DOM")
    (view/destroy! v)))

(deftest dispatch-edits-through-history
  (let [v (view/create-view (host) {:doc "hello"})]
    (view/dispatch! v #js {:changes #js {:from 5 :to 5 :insert " world"}})
    (is (= "hello world" (cm6/doc-string (view/view-state v))) "dispatch applies the edit")
    ;; the edit threaded through the history extension, so undo works on the view's state
    (let [undone (cm6/undo (view/view-state v))]
      (is (= "hello" (cm6/doc-string undone)) "dispatched edits are undoable"))
    (view/destroy! v)))

(deftest set-state-swaps-wholesale
  (let [v (view/create-view (host) {:doc "original"})]
    (view/set-state! v (cm6/make-state "swapped"))
    (is (= "swapped" (cm6/doc-string (view/view-state v))) "set-state! replaces the doc")
    (view/destroy! v)))

(deftest destroy-detaches-from-dom
  (let [h (host)
        v (view/create-view h {:doc "x"})]
    (is (.contains h (view/dom v)))
    (view/destroy! v)
    (is (not (.contains h (view/dom v))) "destroy! removes the view element")))
