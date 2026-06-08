(ns lt.sec.center
  "Control center — the management/query layer over the capability gate (security
  model layer 3, the data side; 'auditability is a primary UX feature'). Cross-
  principal/profile views of grants + the effect journal that the permission-center
  UI (and the :security.* commands) bind to. Pure reads/commands over lt.sec.gate's
  atoms; node-loadable + tested. No editor deps."
  (:require [lt.sec.gate :as gate]))

(defn profiles
  "Profiles that have any grants (always includes the active profile)."
  []
  (distinct (cons (gate/active-profile) (keys (gate/grants-snapshot)))))

(defn all-grants
  "{principal #{capability}} for a profile (default: the active one)."
  ([] (all-grants (gate/active-profile)))
  ([profile] (get (gate/grants-snapshot) profile {})))

(defn principals
  ([] (principals (gate/active-profile)))
  ([profile] (vec (keys (all-grants profile)))))

(defn principal-grants
  ([principal] (principal-grants principal (gate/active-profile)))
  ([principal profile] (get (all-grants profile) principal #{})))

(defn revoke-all!
  "Revoke every capability held by `principal` in the active profile."
  [principal]
  (doseq [c (principal-grants principal)] (gate/revoke! principal c))
  nil)

(defn journal [] (gate/journal-snapshot))
(defn journal-for [principal] (filterv #(= principal (:principal %)) (gate/journal-snapshot)))
(defn denials [] (filterv #(= :deny (:decision %)) (gate/journal-snapshot)))

(defn summary
  "An at-a-glance audit map: grant counts per profile→principal, and journal
  decision tallies."
  []
  {:active-profile (gate/active-profile)
   :grants (into {} (for [[prof byp] (gate/grants-snapshot)]
                      [prof (into {} (for [[prin caps] byp] [prin (count caps)]))]))
   :journal (frequencies (map :decision (gate/journal-snapshot)))})
