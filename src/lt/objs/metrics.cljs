(ns lt.objs.metrics
  "Define and collect usage metrics.

  The original implementation POSTed metrics to LightTable.com's server
  (app.kodowa.com) via the fetch.remotes RPC mechanism (fetch.macros letrem/remote).
  That backend is long dead and the fetch library used goog.structs.Map, removed in
  modern Closure. Network reporting is removed; the local capture API (used!,
  capture!) is preserved for in-process consumers (keyboard, opener)."
  (:refer-clojure :exclude [send flush])
  (:require [lt.object :as object]
            [lt.util.js :refer [now]])
  (:require-macros [lt.macros :refer [behavior]]))

(def active? false)
(def used? false)

(def _metrics (atom []))

(defn used! []
  (set! used? true))

(defn capture! [ev & [ex]]
  (let [mtr {:ev ev :ts (now)}
        mtr (if ex (assoc mtr :ex ex) mtr)]
    (swap! _metrics conj mtr)))

(defn send [_mtrs]
  ;; no-op: reporting backend removed
  nil)

(defn flush []
  ;; drop accumulated metrics without sending
  (reset! _metrics []))

(defn init []
  ;; no remote session / reporting; reporting backend removed
  nil)

(behavior ::init-metrics
          :triggers #{:init}
          :reaction (fn []
                      (init)))

(behavior ::disable-metrics
          :type :user
          :desc "App: Disable metrics"
          :triggers #{:object.instant}
          :reaction (fn [this]
                      (set! active? false)))
