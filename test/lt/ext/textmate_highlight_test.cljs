(ns lt.ext.textmate-highlight-test
  "C — whole-document TextMate highlighting: multi-line tokenization with rule-state
  threaded across lines, spans offset to absolute document positions. Proves a block
  construct started on one line keeps highlighting on the next (the thing per-line
  tokenization without state-threading gets wrong)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.textmate :as tm]
            [lt.ext.textmate-highlight :as hl]))

(def ^:private toy-grammar
  {:scopeName "source.toy"
   :patterns  [{:name "keyword.control.toy" :match "\\b(def|if)\\b"}
               {:name "comment.block.toy" :begin "/\\*" :end "\\*/"}]})

(deftest whole-document-spans-thread-block-state
  (async done
    (-> (tm/load-onig!)
        (.then
         (fn [onig-lib]
           (let [reg (tm/make-registry onig-lib "source.toy"
                                       (tm/parse-grammar (js/JSON.stringify (clj->js toy-grammar))))]
             (.then
              (.loadGrammar reg "source.toy")
              (fn [grammar]
                ;; line 0: `def x`  | lines 1-2: a block comment spanning two lines
                (let [text  "def x\n/* multi\nline */ def y"
                      spans (hl/spans-for-text grammar text)
                      cls-at (fn [pos] (some #(when (and (<= (:from %) pos) (< pos (:to %))) (:class %)) spans))]
                  ;; `def` on line 0 starts at offset 0
                  (is (= "tok-keyword" (cls-at 0)) "line-0 keyword highlighted")
                  ;; offset of the block comment: "def x\n" = 6; "/* multi" highlighted as comment
                  (is (= "tok-comment" (cls-at 6)) "block comment opens on line 1")
                  ;; line 2 starts at 6 + len("/* multi")+1 = 6+8+1 = 15; "line */" still in the comment
                  (is (= "tok-comment" (cls-at 15)) "comment STILL active on line 2 — rule state threaded across lines")
                  ;; after `*/ ` on line 2, `def y` is keyword again
                  (is (= "tok-keyword" (cls-at (.indexOf text "def y"))) "keyword after the block closes")
                  (done))))))))))
