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
    [:lt.object/error      {:behavior name :trigger k :error e}]"
  (:refer-clojure :exclude []))

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

(defn observe-dispatched!
  "Emit a dispatch observation on the tap> stream."
  [m]
  (tap> [dispatched-event m]))

(defn observe-error!
  "Emit an error observation on the tap> stream."
  [m]
  (tap> [error-event m]))
