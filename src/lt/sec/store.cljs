(ns lt.sec.store
  "Persistence for the capability gate — grants survive restarts, stored per the
  'profile = state root' model (the grants live in the profile's directory). The
  grant map (profile → principal → #{capability}) is plain clj data, so it
  round-trips through EDN losslessly. File I/O via the M1 broker; node-loadable +
  tested. (Wiring: load! on profile activation / startup, save! on grant changes.)"
  (:require [lt.sec.gate :as gate]
            [lt.util.broker :as broker]
            [clojure.edn :as edn]))

(defn- grants-file [dir] (.join broker/path dir "capability-grants.edn"))

(defn save!
  "Write the gate's current grant map to `dir`/capability-grants.edn."
  [dir]
  (.writeFileSync broker/fs (grants-file dir) (pr-str (gate/grants-snapshot)))
  nil)

(defn load!
  "Read grants from `dir` and install them into the gate (replacing the current
  map). No-op (returns {}) if the file is absent. Returns the loaded map."
  [dir]
  (let [f (grants-file dir)]
    (if (.existsSync broker/fs f)
      (let [m (edn/read-string (.readFileSync broker/fs f "utf8"))]
        (gate/install-grants! m)
        m)
      {})))
