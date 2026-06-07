(ns lt.editor.cm6.search-test
  "Falsifier gate for lt.editor.cm6.search: match offsets, case sensitivity,
  next/prev wrapping, regexp, and single-transaction replace-all — all over a
  pure EditorState (node-safe; no DOM)."
  (:require [clojure.test :refer [deftest is]]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.search :as search]))

;; "cat" at 4 (lowercase), "CAT" at 27. "the" at 0, 15, 23.
(def ^:private doc "the cat sat on the mat\nthe CAT ran")

(deftest match-ranges-case
  (let [s (cm6/make-state doc)]
    (is (= [{:from 4 :to 7} {:from 27 :to 30}]
           (search/match-ranges s "cat"))
        "default is case-insensitive: both cat and CAT")
    (is (= [{:from 4 :to 7}]
           (search/match-ranges s "cat" {:case-sensitive? true}))
        "case-sensitive: only the lowercase cat")
    (is (= [] (search/match-ranges s "" )) "empty query → no matches")
    (is (= [] (search/match-ranges s "zzz")) "no match → empty")))

(deftest next-match-wraps
  (let [s (cm6/make-state doc)]
    (is (= {:from 4 :to 7} (search/next-match s "cat" 0)) "first match from start")
    (is (= {:from 27 :to 30} (search/next-match s "cat" 5)) "next after the first")
    (is (= {:from 4 :to 7} (search/next-match s "cat" 28)) "wraps past the last → first")
    (is (nil? (search/next-match s "zzz" 0)) "no matches → nil")))

(deftest prev-match-wraps
  (let [s (cm6/make-state doc)]
    (is (= {:from 4 :to 7} (search/prev-match s "cat" 27)) "match before pos")
    (is (= {:from 27 :to 30} (search/prev-match s "cat" 4)) "none before → wraps to last")
    (is (nil? (search/prev-match s "zzz" 99)) "no matches → nil")))

(deftest regexp-matches
  (let [s (cm6/make-state doc)]
    (is (= [{:from 4 :to 7} {:from 27 :to 30}]
           (search/match-ranges s "c.t" {:regexp? true}))
        "c.t matches cat and CAT (ignoreCase default), not sat/mat")))

(deftest replace-all-replaces-every-match
  (let [s (cm6/make-state doc)
        s' (search/replace-all s "cat" "dog")]
    (is (= "the dog sat on the mat\nthe dog ran" (cm6/doc-string s'))
        "every match replaced")
    (is (identical? s (search/replace-all s "zzz" "dog"))
        "no match → state returned unchanged")))

(deftest replace-all-single-undo
  (let [s (cm6/make-state doc)
        s' (search/replace-all s "cat" "dog")]
    (is (= doc (cm6/doc-string (cm6/undo s')))
        "one undo reverts the whole replace-all (single transaction)")))
