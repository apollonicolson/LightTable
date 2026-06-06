(ns res.core
  "Composable res prototype.

  The core path is:

    source -> match -> resolve -> values -> reduce

  Res selects claims whose shapes apply to a query candidate. A sig is
  the presented envelope — a map of {:candidate ... :payload ...} — but
  res itself only needs the candidate. Sigs are constructed by callers,
  not by res.

  Storage, matching, ordering, and reduction are supplied as ordinary
  functions. The default claim shape is a map, but the machinery keeps
  meaning out of the kernel."
  (:refer-clojure :exclude [resolve])
  (:require [res.shape :as shape]))

(defn source
  "Create a claim source from a collection. A source is `(fn [ctx] claims)`."
  [claims]
  (fn [_ctx] claims))

(defn atom-source
  "Create a claim source backed by an atom."
  [!claims]
  (fn [_ctx] @!claims))

(defn fn-source
  "Use `f` as a claim source. Provided for readability at call sites."
  [f]
  f)

(defn chain-sources
  "Compose multiple sources into one source."
  [& sources]
  (fn [ctx]
    (mapcat #(% ctx) sources)))

(defn register
  "Purely add `claim` to a collection of claims."
  [claims claim]
  (conj (vec claims) claim))

(defn register!
  "Add `claim` to an atom-backed collection of claims."
  [!claims claim]
  (swap! !claims register claim))

(defn claim-order
  "Default ordering: higher :order first, stable insertion order second.
  `map-indexed` supplies the insertion index during selection."
  [[idx claim]]
  [(- (long (or (:order claim) 0))) idx])

(defn- claim-matches?
  [matcher candidate [_idx claim]]
  (if (contains? claim :shape)
    (matcher (:shape claim) candidate)
    false))

(defn- role-matches?
  [role [_idx claim]]
  (or (nil? role) (= role (:role claim))))

(defn select
  "Select claims from `claims` according to `query`.

  Query keys:
    :candidate  candidate to match against claim :shape
    :role     optional role keyword
    :matcher  `(fn [shape candidate] boolean?)`, defaults to Malli matcher
    :order    sort key over `[idx claim]`, defaults to `claim-order`
    :where    optional predicate over claim maps"
  ([claims query]
   (let [{:keys [candidate role matcher order where]
          :or {matcher shape/matches?
               order claim-order
               where (constantly true)}} query]
     (->> claims
          (map-indexed vector)
          (filter #(if (contains? query :candidate)
                     (claim-matches? matcher candidate %)
                     true))
          (filter #(role-matches? role %))
          (filter (fn [[_idx claim]] (where claim)))
          (sort-by order)
          (mapv second)))))

(defn resolve
  "Resolve claims from `claim-source` using `query`. `claim-source` may
  be a collection or a source fn."
  ([claim-source query]
   (resolve claim-source query {}))
  ([claim-source query ctx]
   (let [claims (if (fn? claim-source)
                   (claim-source ctx)
                   claim-source)]
     (select claims query))))

(defn values
  "Project selected claims to their :value payloads."
  [claims]
  (mapv :value claims))

(defn explain
  "Pure resolution report. Does not invoke claim values."
  ([claim-source query]
   (explain claim-source query {}))
  ([claim-source query ctx]
   (let [claims  (vec (if (fn? claim-source)
                         (claim-source ctx)
                         claim-source))
         selected (select claims query)]
     {:query query
      :claim-count (count claims)
      :selected-count (count selected)
      :selected selected})))
