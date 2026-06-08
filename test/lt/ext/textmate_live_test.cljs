(ns lt.ext.textmate-live-test
  "C — the live TextMate highlighter as a CM6 StateField: holds the mark DecorationSet
  and recomputes on doc change. Tested via EditorState (no view → no jsdom measure
  noise). The remaining bits are theme CSS for the tok-* classes + the editor install
  seam (Electron)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.textmate :as tm]
            [lt.ext.textmate-highlight :as hl]
            ["@codemirror/state" :as cm-state]))

(def ^:private EditorState (.-EditorState cm-state))

(def ^:private toy-grammar
  {:scopeName "source.toy"
   :patterns  [{:name "keyword.control.toy" :match "\\b(def|if)\\b"}]})

(deftest highlight-statefield-recomputes-on-edit
  (async done
    (-> (tm/load-onig!)
        (.then
         (fn [onig-lib]
           (let [reg (tm/make-registry onig-lib "source.toy"
                                       (tm/parse-grammar (js/JSON.stringify (clj->js toy-grammar))))]
             (.then
              (.loadGrammar reg "source.toy")
              (fn [grammar]
                (let [field (hl/highlight-extension grammar)
                      st0   (.create EditorState #js {:doc "def x" :extensions #js [field]})]
                  (is (= 1 (.-size (.field st0 field))) "one keyword highlighted at create (def)")
                  ;; insert ' if y' → a second keyword; the field recomputes on docChanged
                  (let [tr  (.update st0 #js {:changes #js {:from 5 :insert " if y"}})
                        st1 (.-state tr)]
                    (is (= 2 (.-size (.field st1 field)))
                        "after the edit, both def + if are highlighted — StateField recomputed"))
                  (done))))))))))
