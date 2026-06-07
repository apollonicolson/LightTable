(ns lt.editor.cm6.commands-test
  "CM6 equivalents of the CM5 commands pool.cljs binds. Geometry-free commands
  (selectAll/delete/transpose) are checked here under jsdom; cursor-motion
  commands that need layout are covered by the live Electron suite."
  (:require [clojure.test :refer [deftest is]]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.view :as view]
            [lt.editor.cm6.commands :as commands]))

(defn- a-view [doc]
  (let [host (doto (.createElement js/document "div")
               (->> (.appendChild (.-body js/document))))]
    (view/create-view host {:doc doc})))

(deftest select-all-command
  (let [v (a-view "ab\ncde\nf")]
    (is (true? (commands/run v :selectAll)))
    (is (= "ab\ncde\nf" (cm6/selected-text (view/view-state v))))))

(deftest delete-and-transpose-commands
  (let [v (a-view "abc")]
    (view/move-cursor! v 2)                    ; cursor after "ab"
    (is (true? (commands/run v :delCharBefore)))
    (is (= "ac" (cm6/doc-string (view/view-state v))) "delCharBefore removed 'b'"))
  (let [v (a-view "ab")]
    (view/move-cursor! v 1)
    (is (true? (commands/run v :transposeChars)))
    (is (= "ba" (cm6/doc-string (view/view-state v))) "transposeChars swapped")))

;; deleteLine / cursor-motion need view layout (jsdom has none — "Window is not
;; defined") → exercised in the live Electron suite, not here.

(deftest unknown-command-is-false
  (let [v (a-view "x")]
    (is (false? (commands/run v :toggleOverwrite)) "no CM6 equivalent → false")))
