(ns lt.object.dispatch
  "Open per-behavior invocation for BOT, with tap> observation.

  This is the INVOKE half of `raise` — the 'how'. lt.object.resolve is the
  RESOLVE half — the 'which'. Together they mirror lume's split (lume.res):
  res resolves, a multimethod executes, and a single tap> stream observes.

  `invoke` is a multimethod dispatched on a behavior's optional :dispatch key,
  so invocation strategy is open: the default is synchronous application; async,
  queued, or backend-delegated strategies register their own `defmethod` without
  touching the dispatch loop in lt.object/raise*.

  Observation is a single tap> stream. Subscribe with clojure.core/add-tap; no
  coupling to the console or to BOT itself. (BOT's legacy timing channel,
  :object.behavior.time, is BOT observing BOT via a self-raise; it remains for
  back-compat but tap> is the decomplected replacement.)

  Event shapes:
    [:lt.object/dispatched {:behavior name :trigger k :time ms}]
    [:lt.object/error      {:behavior name :trigger k :error e}]

  Dispatch observation is OPT-IN (default off) — like lume's opt-in
  `lume.infra.observe`. raise* is a hot path (per reaction, per event), and cljs
  `tap>` schedules a macrotask + allocates even with no subscribers; so when
  observation is off, `observe-dispatched!` does nothing and allocates nothing.
  Enable with `observe!` (e.g. a profiler / time-travel view). Errors are rare,
  so error observation is always on.")

(defmulti invoke
  "Invoke behavior `beh`'s :reaction with `obj` and the seq `args`. Dispatched on
  the behavior's :dispatch key; defaults to synchronous application — identical
  to BOT's historical `(apply (:reaction beh) obj args)`."
  (fn [beh _obj _args] (or (:dispatch beh) :default)))

(defmethod invoke :default [beh obj args]
  (apply (:reaction beh) obj args))

(def dispatched-event
  "tap> event tag emitted after a behavior reaction runs."
  :lt.object/dispatched)

(def error-event
  "tap> event tag emitted when a behavior reaction throws."
  :lt.object/error)

;; When false (default), dispatch observation is a no-op — no per-dispatch
;; allocation, no tap, no scheduled macrotask. Toggle with `observe!`.
(defonce ^:private !observing (atom false))

(defn observe!
  "Turn dispatch observation on/off (default off, so the hot path pays nothing).
  Callers that `add-tap` a dispatch subscriber should enable this."
  ([] (observe! true))
  ([on?] (reset! !observing (boolean on?))))

(defn observing?
  "Is dispatch observation enabled?"
  []
  @!observing)

(defn observe-dispatched!
  "Emit a dispatch observation IFF observation is enabled. Takes raw pieces (not
  a pre-built map) so nothing is allocated on the hot path when observation is
  off."
  [behavior trigger time]
  (when @!observing
    (tap> [dispatched-event {:behavior behavior :trigger trigger :time time}])))

(defn observe-error!
  "Emit an error observation (always — errors are rare, not hot)."
  [behavior trigger error]
  (tap> [error-event {:behavior behavior :trigger trigger :error error}]))
