(ns res
  "Public facade for res.

  The small user model is:

    claims -> resolver
    resolver + query -> resolve / values / reduce / explain

  Claims are standing definitions. Sigs ({:candidate :payload} maps) are
  presented envelopes constructed by callers; res only consumes the
  candidate. Shape matching lives in `res.shape`; payload validation
  belongs to callers.

  Lower-level namespaces remain available for advanced use:
  `res.core`, `res.index`."
  (:refer-clojure :exclude [resolve reduce])
  (:require [malli.core :as m]
            [res.core :as core]
            [res.index :as index]))

(def ^:private Claim
  [:map
   [:shape :any]
   [:role :keyword]
   [:value :any]
   [:order {:optional true} :int]
   [:meta {:optional true} :map]])

(def ResolverInput
  [:map
   [:res/name {:optional true} :keyword]
   [:claims [:vector Claim]]
   [:strategy {:optional true} [:enum :simple :index]]])

(defn explain-input
  [input]
  (m/explain ResolverInput input))

(defn valid-input?
  [input]
  (m/validate ResolverInput input))

(defn validate-input!
  [input]
  (when-let [explanation (explain-input input)]
    (throw (ex-info "Invalid resolver input"
                    {:type ::invalid-resolver-input
                     :explain explanation})))
  input)

(defn- index-strategy? [resolver]
  (= :index (:strategy resolver)))

(defn resolver
  "Build a resolver from plain data.

  Strategies:
    :simple    use `res.core`
    :index     derive lookup indexes and selected-value cache"
  [input]
  (let [{:keys [claims strategy]
         :or {strategy :index}
         :as input} (validate-input! input)]
    (case strategy
      :simple
      {:input input
       :strategy strategy
       :claims claims}

      :index
      {:input input
       :strategy strategy
       :claims claims
       :index (index/build claims)})))

(defn resolve
  "Resolve claims using a resolver."
  ([resolver query]
   (case (:strategy resolver)
     :simple (core/resolve (:claims resolver) query)
     :index (index/resolve (:index resolver) query)))
  ([resolver candidate role]
   (resolve resolver {:candidate candidate :role role})))

(defn values
  "Resolve and return selected :value payloads."
  ([resolver query]
   (if (and (index-strategy? resolver)
            (contains? query :candidate)
            (not (contains? query :matcher))
            (not (contains? query :order)))
     (index/values (:index resolver) (:candidate query) (:role query) (:where query))
     (mapv :value (resolve resolver query))))
  ([resolver candidate role]
   (values resolver {:candidate candidate :role role})))

(defn reduce
  "Reduce over selected values.

  This is the meaning-neutral hot-path API. The caller supplies the
  reducing function and therefore owns the meaning."
  [resolver query rf init]
  (if (and (index-strategy? resolver)
           (contains? query :candidate)
           (not (contains? query :matcher))
           (not (contains? query :order)))
    (index/reduce-values (:index resolver)
                         (:candidate query)
                         (:role query)
                         (:where query)
                         rf
                         init)
    (clojure.core/reduce rf init (values resolver query))))

(defn explain
  "Explain resolution using a resolver."
  [resolver query]
  (let [selected (resolve resolver query)]
    {:strategy (:strategy resolver)
     :query query
     :selected selected
     :selected-count (count selected)
     :claim-count (count (:claims resolver))}))

(defn presented-sig
  "Normalize raw data or an existing envelope into a presented sig.

  Raw data becomes `{:candidate data}`. Existing sig envelopes pass
  through unchanged."
  [presented]
  (if (and (map? presented) (contains? presented :candidate))
    presented
    {:candidate presented}))

(defn present-query
  "Build a query from raw data or a presented sig envelope.

  Query options such as :matcher/:order/:where are preserved. The
  presented candidate always wins over any query candidate so the call
  site has one source of truth."
  ([presented]
   (present-query presented nil {}))
  ([presented role]
   (present-query presented role {}))
  ([presented role query]
   (let [sig (presented-sig presented)]
     (cond-> (assoc query :candidate (:candidate sig))
       (some? role) (assoc :role role)))))

(defn present
  "Resolve claims by presenting raw data or a sig envelope."
  ([resolver presented]
   (resolve resolver (present-query presented)))
  ([resolver presented role]
   (resolve resolver (present-query presented role)))
  ([resolver presented role query]
   (resolve resolver (present-query presented role query))))

(defn present-values
  "Resolve claim values by presenting raw data or a sig envelope."
  ([resolver presented]
   (values resolver (present-query presented)))
  ([resolver presented role]
   (values resolver (present-query presented role)))
  ([resolver presented role query]
   (values resolver (present-query presented role query))))

(defn present-reduce
  "Reduce over selected values by presenting raw data or a sig envelope."
  ([resolver presented role rf init]
   (reduce resolver (present-query presented role) rf init))
  ([resolver presented role rf init query]
   (reduce resolver (present-query presented role query) rf init)))

(defn present-explain
  "Explain selected claims by presenting raw data or a sig envelope."
  ([resolver presented]
   (explain resolver (present-query presented)))
  ([resolver presented role]
   (explain resolver (present-query presented role)))
  ([resolver presented role query]
   (explain resolver (present-query presented role query))))
