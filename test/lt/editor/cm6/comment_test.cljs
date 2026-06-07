(ns lt.editor.cm6.comment-test
  "Falsifier gate for lt.editor.cm6.comment: toggle line/block/auto over a state
  carrying a language that defines commentTokens (javascript). Pure state→state."
  (:require [clojure.test :refer [deftest is]]
            ["@codemirror/lang-javascript" :as cm-js]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.modes :as modes]
            [lt.editor.cm6.comment :as comment]))

(defn- js-state [s] (cm6/make-state s #js [((.-javascript cm-js))]))
;; Build a clojure state through the REAL modes module, so this also gates that
;; cm6.modes attaches commentTokens to the legacy clojure language.
(defn- clj-state [s]
  (cm6/make-state s #js [(modes/initial (modes/make-compartment) "clojure")]))

(deftest toggle-line-roundtrip
  (let [s (js-state "var x = 1;")
        commented (comment/toggle-line s)]
    (is (= "// var x = 1;" (cm6/doc-string commented)) "adds a line comment")
    (is (= "var x = 1;" (cm6/doc-string (comment/toggle-line commented)))
        "toggling again removes it")))

(deftest toggle-prefers-line
  (let [s (js-state "var x = 1;")]
    (is (= "// var x = 1;" (cm6/doc-string (comment/toggle s)))
        "auto-toggle uses the line comment when the language has one")))

(deftest toggle-block-wraps
  (let [s (cm6/set-selection (js-state "var x = 1;") {:line 0 :ch 0} {:line 0 :ch 5})
        b (comment/toggle-block s)]
    (is (re-find #"/\*" (cm6/doc-string b)) "block comment adds /* */ markers")))

(deftest line-and-block-add
  (let [s (js-state "var x = 1;")
        c (comment/line s)]
    (is (= "// var x = 1;" (cm6/doc-string c)) "line adds a comment")
    (is (= "// var x = 1;" (cm6/doc-string (comment/line c)))
        "line is idempotent when already commented (does NOT remove — unlike toggle)")))

(deftest clojure-mode-comments
  (let [s (clj-state "(defn f [] 1)")
        c (comment/toggle-line s)]
    (is (= ";; (defn f [] 1)" (cm6/doc-string c))
        "cm6.modes attaches commentTokens → clojure line comment works")
    (is (= "(defn f [] 1)" (cm6/doc-string (comment/toggle-line c)))
        "toggles back off")))

(deftest no-language-is-no-op
  (let [s (cm6/make-state "var x = 1;")]
    (is (= "var x = 1;" (cm6/doc-string (comment/toggle-line s)))
        "no language → no commentTokens → state returned unchanged")))
