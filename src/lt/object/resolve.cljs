(ns lt.object.resolve
  "Behavior-resolution core of BOT, expressed on the res substrate.

  lt.object's `raise` does two things: (1) RESOLVE which behaviors apply to an
  object's tag set, for a trigger, in specificity order; (2) INVOKE them with
  side effects. This namespace is half (1) only — the resolution — pulled out of
  lt.object so it is DOM-free, registry-parameterized, and characterization-tested
  headlessly (test/lt/object/resolve_test.cljs).

  The resolution is driven through res's kernel (`res.core/select`), which keeps
  meaning out of the kernel and takes matching/ordering/filtering as ordinary
  functions:

    claim       = one (tag, behavior-ref) pairing: {:shape tag :value ref ...}
    :matcher    = tag membership: is the claim's tag in the object's tag-set?
    :order      = specificity (dotted-segment count) of the tag, then insertion
    reduce      = BOT-specific MEANING (negation / :exclusive / dedup), caller-owned

  This is the res model verbatim — source -> match -> resolve(matcher,order) ->
  reduce — with the stateful seen-set fold living in the reduce stage because it
  is BOT meaning, not kernel mechanism. The res.core kernel is the right layer
  here (not the `res` facade) because the facade's Claim schema requires a :role
  per claim, a granularity this resolution does not use.

  Every fn takes the registry maps explicitly (the behaviors/tags/negated-tags
  snapshots that live as atoms in lt.object); lt.object delegates its private
  resolution helpers here. Semantics are identical to the historical in-place
  implementation, pinned by the characterization gate."
  (:refer-clojure :exclude [resolve])
  (:require [res.core :as rc]))

(defn behavior-name
  "A behavior ref is either a bare name keyword or a coll whose head is the name."
  [beh]
  (if (coll? beh)
    (first beh)
    beh))

(defn lookup-behavior
  "Resolve a behavior ref to its definition map via the behaviors registry."
  [behaviors beh]
  (behaviors (behavior-name beh)))

(defn triggers
  "Group behavior refs by the triggers each declares: {trigger [behs...]}."
  [behaviors behs]
  (let [result (atom (transient {}))]
    (doseq [beh behs
            t (:triggers (lookup-behavior behaviors beh))]
      (swap! result assoc! t (conj (or (get @result t) '[]) beh)))
    (persistent! @result)))

(defn specificity-sort
  "Sort tags/behavior refs by dotted-segment count (more dots = more specific).
  Default direction (dir nil) is most-specific-first. Also used to rank tags for
  the res claim ordering below, so res reproduces BOT's tag precedence exactly,
  including its reverse-lexicographic tiebreak among equal-specificity tags."
  ([xs] (specificity-sort xs nil))
  ([xs dir]
   (let [arr #js []]
     (doseq [x xs]
       (.push arr #js [(.-length (.split (str x) ".")) (str x) x]))
     (.sort arr)
     (when-not dir (.reverse arr))
     (dotimes [i (.-length arr)]
       (aset arr i (aget arr i 2)))
     arr)))

(defn negations
  "Build a seen-set (js-obj) of behavior names negated by the given tags."
  [negated-tags ts]
  (let [seen (js-obj)]
    (doseq [beh (apply concat (map negated-tags ts))]
      (aset seen (behavior-name beh) true))
    seen))

(defn- tag-rank
  "Map each registered tag to its specificity precedence (0 = most specific),
  derived from specificity-sort so the relative order matches BOT exactly."
  [tags]
  (zipmap (specificity-sort (vec (keys tags))) (range)))

(defn- tag-claims
  "Res claims, one per (tag, behavior-ref) in the registry. :shape is the tag
  (matched by membership against an object's tag-set); :value is the behavior
  ref; ::order = [tag-specificity-rank within-tag-index] so res select reproduces
  BOT's `(apply concat (map tags (specificity-sort ts)))` order — most-specific
  tag first, registration order within a tag."
  [tags]
  (let [rank (tag-rank tags)]
    (vec (for [[tag refs] tags
               [i ref] (map-indexed vector refs)]
           {:shape tag
            :value ref
            ::order [(rank tag) i]}))))

(defn- member-matcher
  "res :matcher — the claim's tag (shape) is present in the candidate tag-set."
  [tag candidate-tagset]
  (contains? candidate-tagset tag))

(defn tags->behaviors
  "Collect the de-duplicated behavior refs contributed by tag set `ts`, in
  specificity order, honoring negations and per-behavior :exclusive sets.

  Match + order go through res.core/select (pluggable matcher/order); the
  negation/exclusive/dedup policy is the caller-owned reduce stage."
  [tags behaviors negated-tags ts]
  (let [selected (rc/select (tag-claims tags)
                            {:candidate (set ts)
                             :matcher member-matcher
                             :order (fn [[_idx claim]] (::order claim))})
        duped (mapv :value selected)
        de-duped (reduce
                   (fn [res cur]
                     (if (aget (:seen res) (behavior-name cur))
                       res
                       (let [beh (lookup-behavior behaviors cur)]
                         (when (:exclusive beh)
                           (when (coll? (:exclusive beh))
                             (doseq [exclude (:exclusive beh)]
                               (aset (:seen res) exclude true)))
                           (aset (:seen res) (behavior-name cur) true))
                         (conj! (:final res) cur)
                         res)))
                   {:seen (negations negated-tags ts)
                    :final (transient [])}
                   duped)]
    (reverse (persistent! (:final de-duped)))))

(defn trigger->behaviors
  "Behavior refs from tag set `ts` that listen for trigger `trig`."
  [tags behaviors negated-tags trig ts]
  (get (triggers behaviors (tags->behaviors tags behaviors negated-tags ts)) trig))
