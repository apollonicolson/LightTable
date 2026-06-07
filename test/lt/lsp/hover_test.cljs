(ns lt.lsp.hover-test
  "Falsifier gate for lt.lsp.hover (ADR 0010 slice 4): every LSP hover contents
  shape maps to the right display text."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.lsp.hover :as hover]))

(deftest markup-content
  (is (= "# Doc\nbody"
         (hover/hover->text {:contents {:kind "markdown" :value "# Doc\nbody"}}))))

(deftest bare-string
  (is (= "just text" (hover/hover->text {:contents "just text"}))))

(deftest marked-string-map
  (is (= "(defn f [])"
         (hover/hover->text {:contents {:language "clojure" :value "(defn f [])"}}))))

(deftest array-of-marked-strings
  (is (= "(defn f [])\n\ndocs"
         (hover/hover->text {:contents [{:language "clojure" :value "(defn f [])"}
                                        "docs"]}))))

(deftest array-skips-blanks
  (is (= "a\n\nb"
         (hover/hover->text {:contents ["a" "" {:value "b"} {:value ""}]}))))

(deftest empty-and-nil-are-nil
  (is (nil? (hover/hover->text nil)))
  (is (nil? (hover/hover->text {:contents ""})))
  (is (nil? (hover/hover->text {:contents {:value ""}})))
  (is (nil? (hover/hover->text {}))))
