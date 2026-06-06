(ns res.shape
  "Candidate shape vocabulary for res.

  A shape is a standing acceptor for a presented candidate. This
  namespace defines the reference matching vocabulary only. Payload
  schemas and validation belong to callers or Malli directly."
  (:require [malli.core :as m]))

(defn schema
  "Translate terse map shape sugar to a Malli schema.

  Map sugar:
    literal value -> [:= value]
    set           -> [:enum ...]
    :_            -> optional :any wildcard
    raw schema    -> passed through when the value is a vector beginning
                     with a keyword

  Vector shapes are treated as already-Malli. Other values become
  exact-match schemas."
  [p]
  (cond
    (vector? p) p
    (map? p)    (into [:map]
                      (for [[k v] p]
                        (if (= :_ v)
                          [k {:optional true} :any]
                          [k (cond
                               (set? v) (into [:enum] v)
                               (and (vector? v) (keyword? (first v))) v
                               :else [:= v])])))
    :else       [:= p]))

(defn matches?
  "Matcher implementation: shape × candidate -> boolean."
  [p candidate]
  (m/validate (schema p) candidate))
