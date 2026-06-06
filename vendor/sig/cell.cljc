(ns cell
  "Reactive cell implementation backed by alien-signals on both runtimes.

   A cell is local reactive memory. It is distinct from a sig, which is
   the boundary-crossing presented envelope; from res, which resolves
   presented candidates against claims; and from par, which provisions
   app meaning into peers.

   Cell is not transport, a resolver, a database, a cache, a distributed
   consistency protocol, a UI renderer, or a provisioning primitive. It
   may hold values produced by those layers, but it does not interpret
   them.

   `(cell/cell 0)` returns a Cell that:
     - implements IDeref / IAtom / IReset / ISwap / IWatchable /
       IMeta / IWithMeta,
     - tracks reads inside `effect` / `computed` via alien-signals'
       dependency graph,
     - fires IWatchable watchers on epochal change only: a reset! / swap!
       is an epoch iff (not= old new). ONE change-semantics governs both
       the reactive spine and the watch spine — an `=` write is a no-op
       epoch (no propagation, no watcher call), so effects and watches
       never disagree on what counts as a change. (Watches still observe
       every epoch; effects still coalesce to the latest under `batch` —
       two observation modes, one agreed notion of change.)

   Substrate:
     :cljs → npm `alien-signals` (peer dep, declared by consumers)
     :clj  → hbt/alien-signals Java port (declared by JVM consumers)

   See docs/cell.md for the normative boundary.

   Companion to `res` — the resolution primitive in the same project.
   `res` answers \"which claims does this candidate satisfy?\"; this
   namespace ships the reactive cell implementation + subscription
   registry that render layers may consume.

   This namespace also holds the keyword-keyed IDeref subscription
   registry (`defsub` / `sub` / `undefsub` / `reset-subs!`). The
   registry indexes any IDeref under a keyword for cross-cutting
   lookup; cells are the primary thing it indexes, so co-locating
   keeps the reactive-primitive surface in one ns.

   Usage:
     (require '[cell :as cell])
     (def !count (cell/cell 0))
     @!count                    ;; → 0; tracks the dep inside an effect
     (reset! !count 5)          ;; alien write + fire watchers
     (swap! !count inc)         ;; same
     (cell/effect #(println @!count))   ;; re-runs on every change

     (cell/defsub :counter !count)
     @(cell/sub :counter)       ;; current value of !count"
  #?(:cljs (:require ["alien-signals" :as alien]))
  #?(:clj  (:import [hbt.aliensignals Signal Signals]
                    [java.util.function UnaryOperator])))

;; ─────────────────────────────────────────────────────────────────────
;; Cell — deftype per runtime, same logical surface.
;; ─────────────────────────────────────────────────────────────────────

#?(:cljs
   (deftype SCell [^js sig
                  ^:mutable watches
                  ^:mutable validator
                  ^:mutable meta-data]
     IAtom

     IEquiv
     (-equiv [o other] (identical? o other))

     IDeref
     (-deref [_] (sig))                  ;; alien bound-fn read; ~4 ns on V8

     IMeta
     (-meta [_] meta-data)

     IWithMeta
     (-with-meta [_ m] (SCell. sig watches validator m))

     IWatchable
     (-add-watch [self key f]
       (set! watches (assoc (or watches {}) key f))
       self)
     (-remove-watch [self key]
       (set! watches (dissoc (or watches {}) key))
       self)
     (-notify-watches [self old-val new-val]
       (doseq [[key f] watches]
         (f key self old-val new-val))
       self)

     IHash
     (-hash [self] (goog/getUid self))

     IReset
     (-reset! [self new-val]
       (when (some? validator)
         (when-not (validator new-val)
           (throw (js/Error. "Validator rejected value"))))
       ;; Epochal value-succession: a write is an epoch only when the value
       ;; actually changes. Gating on Clojure = (not the signal's reference
       ;; equality) makes the reactive spine and the watch spine agree — an
       ;; = reset! propagates to neither.
       (let [old-val (sig)]
         (when (not= old-val new-val)
           (sig new-val)
           (when (seq watches)
             (doseq [[key f] watches] (f key self old-val new-val)))))
       new-val)

     ISwap
     (-swap! [self f]        (-reset! self (f (sig))))
     (-swap! [self f a]      (-reset! self (f (sig) a)))
     (-swap! [self f a b]    (-reset! self (f (sig) a b)))
     (-swap! [self f a b xs] (-reset! self (apply f (sig) a b xs)))

     IPrintWithWriter
     (-pr-writer [_ writer _opts]
       (-write writer "#<cell: ")
       (-write writer (pr-str (sig)))
       (-write writer ">"))))

#?(:clj
   (deftype SCell [^Signal sig
                  ^:unsynchronized-mutable watches
                  ^:unsynchronized-mutable validator
                  ^:unsynchronized-mutable meta-data]
     clojure.lang.IDeref
     (deref [_] (.get sig))

     clojure.lang.IAtom
     (reset [self new-val]
       (when (some? validator)
         (when-not (validator new-val)
           (throw (IllegalStateException. "Validator rejected value"))))
       ;; Epochal value-succession (see the cljs branch): an = write is a
       ;; no-op epoch, so the reactive spine and the watch spine agree.
       (let [old-val (.get sig)]
         (when (not= old-val new-val)
           (.set sig new-val)
           (when (seq watches)
             (doseq [[k f] watches] (f k self old-val new-val)))))
       new-val)
     (swap [self f]            (.reset self (f (.get sig))))
     (swap [self f a]          (.reset self (f (.get sig) a)))
     (swap [self f a b]        (.reset self (f (.get sig) a b)))
     (swap [self f a b more]   (.reset self (apply f (.get sig) a b more)))
     (compareAndSet [self old new]
       (if (identical? (.get sig) old)
         (do (.reset self new) true)
         false))

     clojure.lang.IRef
     (setValidator [_ v] (set! validator v))
     (getValidator [_] validator)
     (getWatches   [_] (or watches {}))
     (addWatch [self k f]
       (set! watches (assoc (or watches {}) k f))
       self)
     (removeWatch [self k]
       (set! watches (dissoc (or watches {}) k))
       self)

     clojure.lang.IMeta
     (meta [_] meta-data)

     clojure.lang.IObj
     (withMeta [_ m] (SCell. sig watches validator m))))

;; ─────────────────────────────────────────────────────────────────────
;; Constructors
;; ─────────────────────────────────────────────────────────────────────

(defn cell
  "Create a reactive cell backed by alien-signals.

   Reads via @ auto-track inside an enclosing `effect` or `computed`.
   Writes via reset!/swap! propagate to alien subscribers AND fire
   IWatchable / IRef watchers."
  ([init]
   #?(:cljs (SCell. (alien/signal init) nil nil nil)
      :clj  (SCell. (Signals/signal init) nil nil nil)))
  ([init & {:keys [validator meta]}]
   #?(:cljs (SCell. (alien/signal init) nil validator meta)
      :clj  (SCell. (Signals/signal init) nil validator meta))))

(defn cell?
  "True if x is a Cell."
  [x]
  (instance? SCell x))


;; ─────────────────────────────────────────────────────────────────
;; Cell families — parameterised cells keyed by an arbitrary value.
;;
;; For collection-shaped state (\"a cell per user id\", \"a cell per
;; component instance\") spawning N named vars is awkward. A family
;; lazily materialises a fresh Cell per key on first access, caches
;; it for subsequent calls, and lets the family itself be passed
;; around as a single value.
;;
;;   (def !user (cell-family (fn [id] {:id id :loaded? false})))
;;   @(!user :u-42)             ; → {:id :u-42 :loaded? false}
;;   (reset! (!user :u-42) ...) ; same cell returned each time
;;
;; The init-fn is called once per key; subsequent (!user k) returns
;; the cached cell. Keys can be anything supported by hash-map keys.
;; ─────────────────────────────────────────────────────────────────

#?(:cljs
   (deftype CellFamily [lookup cache meta-data]
     IFn
     (-invoke [_] @cache)
     (-invoke [_ k] (lookup k))

     IDeref
     (-deref [_] @cache)

     IMeta
     (-meta [_] meta-data)

     IWithMeta
     (-with-meta [_ m] (CellFamily. lookup cache m))))

#?(:clj
   (deftype CellFamily [lookup cache meta-data]
     clojure.lang.IFn
     (invoke [_] @cache)
     (invoke [_ k] (lookup k))

     clojure.lang.IDeref
     (deref [_] @cache)

     clojure.lang.IMeta
     (meta [_] meta-data)

     clojure.lang.IObj
     (withMeta [_ m] (CellFamily. lookup cache m))))

(defn cell-family
  "Returns a function that, when called with a key, returns the Cell
   for that key — creating it lazily via `(init-fn key)` on first
   access.

   The returned fn is also IDeref: @family yields the underlying
   {key → cell} map snapshot. Useful for inspection and bulk ops.

   Options (after init-fn):
     :validator — passed to each created cell
     :meta      — passed to each created cell"
  ([init-fn]
   (cell-family init-fn {}))
  ([init-fn {:keys [validator meta]}]
   (let [cache (clojure.core/atom {})
         lookup
         (fn [k]
           (if-let [a (get @cache k)]
             a
             (let [a (cell (init-fn k) :validator validator :meta meta)]
               (swap! cache assoc k a)
               (or (get @cache k) a))))]
     (CellFamily. lookup cache {::family true}))))


(defn family?
  "True if `x` was produced by `cell-family`."
  [x]
  (boolean (some-> x meta ::family)))

;; ─────────────────────────────────────────────────────────────────────
;; computed / effect / batch / untracked / derived
;; ─────────────────────────────────────────────────────────────────────

(defn computed
  "Create a derived (lazy, cached) signal.

     CLJS: returns the bound fn — call as (c) to read.
     JVM:  returns a Computed<T> — call (.get c) to read.

   Re-evaluates only when a tracked dep changes."
  [f]
  #?(:cljs (alien/computed f)
     :clj  (Signals/computed (reify UnaryOperator
                               (apply [_ prev] (f prev))))))

(defn effect
  "Run f, tracking signal reads as deps; re-run f on any dep change.

     CLJS: returns a dispose fn.
     JVM:  returns an Effect — call (.stop e) to dispose."
  [f]
  #?(:cljs (alien/effect f)
     :clj  (Signals/effect (reify Runnable (run [_] (f))))))

(defn batch
  "Group multiple writes into one flush. Effects run once on outer end."
  [f]
  #?(:cljs (do (alien/startBatch)
               (try (f) (finally (alien/endBatch))))
     :clj  (Signals/batch (reify Runnable (run [_] (f))))))

(defn untracked
  "Run f without tracking any signal reads as deps."
  [f]
  #?(:cljs (let [prev (alien/setCurrentSub js/undefined)]
             (try (f)
                  (finally (alien/setCurrentSub prev))))
     :clj  (let [prev (Signals/setActiveSub nil)]
             (try (f)
                  (finally (Signals/setActiveSub prev))))))

;; ─────────────────────────────────────────────────────────────────────
;; derived — read-only Cell backed by a computed. CLJS only: the CLJS
;; Cell deftype takes a callable (alien-signals' bound fn) as its sig
;; field. The JVM Cell takes a Signal, not a Computed; consumers there
;; should call `(computed f)` directly and use `.get` on the result.
;; ─────────────────────────────────────────────────────────────────────

#?(:cljs
   (defn derived
     "Read-only Cell backed by an alien computed. IDeref-able, IWatchable-stub.

        (def !greeting (derived #(str \"Hi, \" @!name)))
        (deref !greeting)"
     [f]
     (SCell. (alien/computed f) nil nil nil)))

;; ─────────────────────────────────────────────────────────────────────
;; raf-effect — browser-only.
;; ─────────────────────────────────────────────────────────────────────

#?(:cljs
   (defn raf-effect
     "Like `effect`, but coalesces re-runs into requestAnimationFrame.
      Browser-only."
     [f]
     (let [raf-id  (volatile! nil)
           dirty   (volatile! false)
           first?  (volatile! true)
           dispose (effect
                     (fn []
                       (if @first?
                         (do (vreset! first? false) (f))
                         (when-not @dirty
                           (vreset! dirty true)
                           (vreset! raf-id
                             (js/requestAnimationFrame
                               (fn [_]
                                 (vreset! dirty false)
                                 (vreset! raf-id nil)
                                 (untracked f))))))))]
       (fn []
         (when-let [id @raf-id]
           (js/cancelAnimationFrame id))
         (dispose)))))

;; ─────────────────────────────────────────────────────────────────────
;; Subscription registry — `{keyword → IDeref}`.
;;
;; Indexes any IDeref under a keyword name. Consumers that just want
;; "give me the current value of the thing called :foo" use
;; `(deref (sub :foo))`. In a reactive context (inside `effect` or
;; `computed`) the deref auto-subscribes via the alien dependency
;; graph, so re-renders propagate.
;; ─────────────────────────────────────────────────────────────────────

(defonce ^:private !subs (clojure.core/atom {}))

(defn defsub
  "Register a subscription. `ref` is any IDeref (cell, reaction,
   alien computed, Cell, ...).

     (defsub :kernel/windows !windows)
     (deref (sub :kernel/windows))   ;; current value
     ;; in an effect / computed context: deref auto-subscribes."
  [key ref]
  (swap! !subs assoc key ref)
  true)

(defn sub
  "Look up a subscription by keyword. Returns an IDeref. Throws if no
   subscription is registered."
  [key]
  (or (get @!subs key)
      (throw (ex-info (str "No subscription: " key)
                      {:key        key
                       :registered (vec (keys @!subs))}))))

(defn undefsub
  "Remove a subscription."
  [key]
  (swap! !subs dissoc key)
  true)

(defn reset-subs!
  "Clear all subscriptions. For tests / REPL."
  []
  (reset! !subs {})
  true)
