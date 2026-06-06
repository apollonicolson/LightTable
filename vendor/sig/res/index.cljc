(ns res.index
  "Indexed resolver over claims.

  The kernel stays data-first. Claims are truth; an index is a derived
  resolver over claims. It may contain lookup indexes and value caches.
  It must not change resolution semantics.

  This namespace combines two derived concerns:

    claims -> lookup index
    query  -> cached selected values

  Use `res.core` for reference semantics."
  (:refer-clojure :exclude [resolve])
  (:require [malli.core :as m]
            [res.core :as core]
            [res.shape :as shape]))

(def ^:private max-subset-keys 12)
(def ^:private index-threshold 512)

(defn- pair-key [[k v]]
  (str (pr-str k) "\u0000" (pr-str v)))

(defn- fingerprint [pairs]
  (vec (sort-by pair-key pairs)))

(defn- shape-literals [p]
  (when (map? p)
    (->> p
         (keep (fn [[k v]]
                 (when-not (or (= :_ v)
                               (set? v)
                               (and (vector? v) (keyword? (first v))))
                   [k v])))
         fingerprint)))

(defn- candidate-fingerprints [candidate]
  (when (map? candidate)
    (let [pairs (vec candidate)
          n (count pairs)]
      (when (<= n max-subset-keys)
        (loop [i 0
               fps [[]]]
          (if (< i n)
            (let [p (nth pairs i)]
              (recur (unchecked-inc i)
                     (into fps (map #(conj % p) fps))))
            (mapv fingerprint fps)))))))

(defn- map-shape-predicate
  [p]
  (let [clauses (mapv (fn [[k v]]
                        (cond
                          (= :_ v) [:any k nil]
                          (set? v) [:set k v]
                          (and (vector? v) (keyword? (first v)))
                          [:pred k (m/validator v)]
                          :else [:eq k v]))
                      p)
        n (count clauses)]
    (fn [candidate]
      (and (map? candidate)
           (loop [i 0]
             (if (< i n)
               (let [[kind k v] (nth clauses i)
                     actual (get candidate k)]
                 (if (case kind
                       :any true
                       :set (contains? v actual)
                       :pred (v actual)
                       :eq (= v actual))
                   (recur (unchecked-inc i))
                   false))
               true))))))

(defn shape-predicate
  "Compile a shape to `(fn [candidate] boolean?)`.

  Map sugar gets a direct predicate. Raw Malli schemas and other values
  use a compiled Malli validator."
  [p]
  (if (map? p)
    (map-shape-predicate p)
    (m/validator (shape/schema p))))

(defn index-claim
  "Index one claim, retaining the original claim under :claim."
  [idx claim]
  {:idx idx
   :claim claim
   :role (:role claim)
   :fingerprint (shape-literals (:shape claim))
   :match? (shape-predicate (:shape claim))})

(defn- indexed-order-key
  [{:keys [idx claim]}]
  (core/claim-order [idx claim]))

(defn- sort-indexed
  [claims]
  (sort-by indexed-order-key claims))

(defn build
  "Build an index from claims.

  Returns data, not a new semantic object:

    {:claims  all indexed claims in default order
     :by-role  role -> indexed claims in default order
     :by-fp    experimental literal shape fingerprint -> indexed claims
     :by-role-fp role -> experimental fingerprint -> indexed claims
     :scan     claims not eligible for experimental fingerprint lookup
     :original original claims
     :!cache   selected value cache}

  Query-level custom :matcher or :order falls back to `res.core/select`
  because those options intentionally replace the indexed choices."
  [claims]
  (let [indexed (->> claims
                     (map-indexed index-claim)
                     sort-indexed
                     vec)
        indexed? (fn [claim] (some? (:fingerprint claim)))]
    {:claims indexed
     :by-role (reduce (fn [m claim]
                        (update m (:role claim) (fnil conj []) claim))
                      {}
                      indexed)
     :by-fp (reduce (fn [m claim]
                      (if (indexed? claim)
                        (update m (:fingerprint claim) (fnil conj []) claim)
                        m))
                    {}
                    indexed)
     :by-role-fp (reduce (fn [m claim]
                           (if (indexed? claim)
                             (update-in m [(:role claim) (:fingerprint claim)]
                                        (fnil conj []) claim)
                             m))
                         {}
                         indexed)
     :scan (filterv (complement indexed?) indexed)
     :original (vec claims)
     :!cache (volatile! {})}))

(defn- candidate-claims
  [index role candidate]
  (let [role-candidates (if (some? role)
                          (get-in index [:by-role role] [])
                          (:claims index))]
    (if (<= (count role-candidates) index-threshold)
      role-candidates
      (if-let [fps (candidate-fingerprints candidate)]
        (let [idx (if (some? role)
                    (get-in index [:by-role-fp role])
                    (:by-fp index))
              found (reduce (fn [m fp]
                              (reduce (fn [m claim]
                                        (assoc m (:idx claim) claim))
                                      m
                                      (get idx fp)))
                            {}
                            fps)
              found (reduce (fn [m claim]
                            (if (or (nil? role) (= role (:role claim)))
                              (assoc m (:idx claim) claim)
                              m))
                            found
                            (:scan index))]
          (->> (vals found)
               (sort-by indexed-order-key)
               vec))
        role-candidates))))

(defn select
  "Select original claims from an index.

  Fast path supports default matching and ordering. Supplying query
  :matcher or :order deliberately falls back to the generic resolver."
  [index query]
  (if (or (contains? query :matcher)
          (contains? query :order))
    (core/select (:original index) query)
    (let [{:keys [candidate role where]} query
          has-candidate? (contains? query :candidate)]
      (loop [xs (candidate-claims index role candidate)
             i 0
             n (count xs)
             out (transient [])]
        (if (< i n)
          (let [{:keys [claim match?]} (nth xs i)]
            (recur xs
                   (unchecked-inc i)
                   n
                   (if (and (or (not has-candidate?) (match? candidate))
                            (or (nil? where) (where claim)))
                     (conj! out claim)
                     out)))
          (persistent! out))))))

(defn resolve
  "Resolve claims from an index."
  ([index query]
   (select index query))
  ([index query _ctx]
   (select index query)))

(defn values
  "Return cached selected :value payloads for candidate and role."
  ([index candidate role]
   (values index candidate role nil))
  ([{:keys [!cache] :as index} candidate role where]
   (let [k [candidate role where]]
     (or (get @!cache k)
         (let [query (cond-> {:candidate candidate :role role}
                       where (assoc :where where))
               vs (mapv :value (resolve index query))]
           (vswap! !cache assoc k vs)
           vs)))))

(defn reduce-values
  "Reduce over cached selected values for candidate and role.

  This is the fast, meaning-neutral primitive. Callers supply the
  reducing function and therefore own the meaning."
  ([index candidate role rf init]
   (reduce rf init (values index candidate role)))
  ([index candidate role where rf init]
   (reduce rf init (values index candidate role where))))

(defn clear-cache!
  "Clear the selected value cache."
  [index]
  (vreset! (:!cache index) {})
  true)
