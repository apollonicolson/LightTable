(ns lt.object.resolve-test
  "Characterization gate for BOT's behavior-resolution core (lt.object.resolve).

  These tests pin the EXACT observable output of the resolution semantics —
  specificity ordering, dedup, negation, :exclusive, trigger grouping — as they
  behaved in the historical in-place lt.object implementation. The M3 res-substrate
  migration re-expresses these fns on `res`; this suite must stay green across that
  swap. Expected values were hand-traced from the pre-migration code."
  (:require [clojure.test :refer [deftest is testing]]
            [lt.object.resolve :as r]))

;; Synthetic registries — the shape lt.object holds in its behaviors/tags/
;; negated-tags atoms. behaviors: name -> {:triggers [...] :exclusive ...}.
(def behaviors
  {:a    {:triggers [:t1]}
   :b    {:triggers [:t1 :t2]}
   :c    {:triggers [:t2]}
   :exa  {:triggers [:t1] :exclusive [:a]}})

(deftest behavior-name-and-lookup
  (is (= :a (r/behavior-name :a)) "bare keyword ref")
  (is (= :a (r/behavior-name [:a 1 2])) "coll ref -> head is the name")
  (is (= {:triggers [:t1]} (r/lookup-behavior behaviors :a)) "lookup by bare ref")
  (is (= {:triggers [:t1]} (r/lookup-behavior behaviors [:a 9])) "lookup by coll ref"))

(deftest specificity-sort-orders-by-dotted-segments
  ;; more dotted segments = more specific; default direction = most-specific-first
  (is (= [:base.more :base]
         (vec (r/specificity-sort [:base :base.more])))
      "default: most specific (more dots) first")
  (is (= [:base :base.more]
         (vec (r/specificity-sort [:base :base.more] :asc)))
      "dir truthy: ascending (least specific first)"))

(deftest tags->behaviors-concats-in-specificity-order-then-reverses
  ;; tags :base.more contributes [:b :c], :base contributes [:a].
  ;; specificity order [:base.more :base] -> duped (:b :c :a) -> final reversed.
  (is (= [:a :c :b]
         (vec (r/tags->behaviors {:base [:a] :base.more [:b :c]}
                                 behaviors {} [:base :base.more])))
      "de-duped behaviors come back in reverse-of-specificity-concat order"))

(deftest tags->behaviors-honors-negation
  ;; negated-tags marks :a negated for :base, so :a is dropped before collection.
  (is (= [:b]
         (vec (r/tags->behaviors {:base [:a :b]} behaviors {:base [:a]} [:base])))
      "negated behavior is excluded from the result"))

(deftest tags->behaviors-honors-exclusive
  ;; :exa (in more-specific tag, processed first) is :exclusive [:a]; it marks :a
  ;; seen so the later :a contribution is suppressed.
  (is (= [:exa]
         (vec (r/tags->behaviors {:base [:a] :base.more [:exa]}
                                 behaviors {} [:base :base.more])))
      "exclusive behavior suppresses a later-listed excluded behavior")
  (testing "exclusive only suppresses behaviors NOT yet collected"
    ;; here :a is in the more-specific tag, so it is collected before :exa runs;
    ;; :exa's exclusion has no retroactive effect. Result is reversed at the end,
    ;; so collection order (:a :exa) comes back as [:exa :a].
    (is (= [:exa :a]
           (vec (r/tags->behaviors {:base [:exa] :base.more [:a]}
                                   behaviors {} [:base :base.more])))
        "already-collected :a survives :exa's exclusion")))

(deftest plain-duplicate-across-tags-is-not-deduped
  ;; the seen-set is only written for :exclusive/negated behaviors, so a plain
  ;; behavior contributed by two matching tags appears twice (BOT preserves this).
  (is (= [:a :a]
         (vec (r/tags->behaviors {:base [:a] :base.more [:a]}
                                 behaviors {} [:base :base.more])))
      "a plain behavior in two tags is NOT deduped"))

(deftest equal-specificity-tiebreak-is-reverse-lexicographic
  ;; both tags have one dotted segment; specificity-sort breaks the tie by string,
  ;; descending (sort asc then reverse) → :bbb before :aaa → output [:x :y].
  (is (= [:x :y]
         (vec (r/tags->behaviors {:aaa [:x] :bbb [:y]}
                                 {:x {:triggers [:t1]} :y {:triggers [:t1]}}
                                 {} [:aaa :bbb])))
      "equal-specificity tags order by reverse-lex tag string"))

(deftest coll-form-behavior-refs-resolve-by-head-name
  ;; a behavior ref can be [name & args]; it resolves via its head and passes through.
  (is (= [[:a 1 2]]
         (vec (r/tags->behaviors {:base [[:a 1 2]]} behaviors {} [:base])))
      "coll-form ref passes through, looked up by head name"))

(deftest triggers-groups-behaviors-by-declared-trigger
  (is (= {:t1 [:a :b] :t2 [:b :c]}
         (r/triggers behaviors [:a :b :c]))
      "each behavior fans out to every trigger it declares, order preserved"))

(deftest trigger->behaviors-end-to-end
  ;; full path: tags -> de-duped behaviors -> grouped by trigger -> pick :t1
  (is (= [:a :b]
         (vec (r/trigger->behaviors {:base [:a] :base.more [:b :c]}
                                    behaviors {} :t1 [:base :base.more])))
      ":t1 listeners from the resolved behavior set")
  (is (= [:c :b]
         (vec (r/trigger->behaviors {:base [:a] :base.more [:b :c]}
                                    behaviors {} :t2 [:base :base.more])))
      ":t2 listeners from the resolved behavior set"))
