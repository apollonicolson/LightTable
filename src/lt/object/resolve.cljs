(ns lt.object.resolve
  "Pure behavior-resolution core of BOT, extracted from lt.object.

  lt.object's `raise` does two things: (1) RESOLVE which behaviors apply to an
  object's tag set, for a trigger, in specificity order; (2) INVOKE them with
  side effects. This namespace is half (1) only — the resolution — pulled out so
  it is DOM-free, registry-parameterized, and characterization-testable headlessly
  before the M3 res-substrate migration.

  Every fn here takes the registry maps explicitly (the `behaviors`/`tags`/
  `negated-tags` snapshots that live as atoms in lt.object). Semantics are
  identical to the historical in-place implementation; lt.object now delegates
  its private resolution helpers here. The next M3 slice re-expresses these on
  `res` (claims+resolve) behind this same surface, gated by the characterization
  tests in test/lt/object/resolve_test.cljs."
  (:refer-clojure :exclude [resolve]))

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
  Default direction (dir nil) is most-specific-first."
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

(defn tags->behaviors
  "Collect the de-duplicated behavior refs contributed by tag set `ts`, in
  specificity order, honoring negations and per-behavior :exclusive sets."
  [tags behaviors negated-tags ts]
  (let [duped (apply concat (map tags (specificity-sort ts)))
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
