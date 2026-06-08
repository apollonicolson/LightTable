(ns lt.sec.gate
  "Object-capability gate — the AUTHORITY enforcement layer of the security model
  (the trust/capability docs). Default-deny: a principal may exercise an effect
  only if it holds a matching capability grant in the active profile.

  Profile-keyed from the start (profile → principal → #{capability}) so multi-
  profile / per-domain is a free OUTER key, never a retrofit. Pure data + atoms,
  node-loadable + tested; the editor-coupled consent prompt and the control-center
  UI are sinks layered on top. Wired in front of every EXTENSION effect (workspace
  .fs, process spawn, net, …) as those APIs land — and, under phase 5, it sits on
  the contextBridge so it guards everything crossing the membrane.

  capability = {:kind :fs.read :scope \"/proj\"} — scope semantics by kind:
    path-prefix for :fs.read/:fs.write, exact for others, nil = unscoped.
  principal  = a string id (an extension's principal id, or a domain id)."
  (:require [clojure.string :as str]))

(defonce ^:private grants         (atom {}))         ; profile -> principal -> #{cap}
(defonce ^:private active-profile* (atom :default))
(defonce journal                  (atom []))         ; append-only audit log
(defonce ^:private prompt-fn      (atom nil))        ; (fn [principal cap]) -> boolean

(defn cap [kind scope] {:kind kind :scope scope})

(defn active-profile [] @active-profile*)
(defn set-active-profile! [p] (reset! active-profile* p))
(defn set-prompt! [f] (reset! prompt-fn f))

(defn grant! [principal capability]
  (swap! grants update-in [@active-profile* principal] (fnil conj #{}) capability) nil)

(defn revoke! [principal capability]
  (swap! grants update-in [@active-profile* principal] (fnil disj #{}) capability) nil)

(defn granted
  "The capability set held by `principal` in the active profile."
  [principal]
  (get-in @grants [@active-profile* principal] #{}))

(defn- path-kind? [kind] (contains? #{:fs.read :fs.write} kind))

(defn- covers?
  "Does grant `g` cover requested capability `req`? Same kind, and scope matches
  (path-prefix for fs kinds, exact otherwise; an unscoped grant covers all)."
  [g req]
  (and (= (:kind g) (:kind req))
       (let [gs (:scope g) rs (:scope req)]
         (cond
           (nil? gs)               true
           (nil? rs)               false
           (path-kind? (:kind req)) (or (= gs rs) (str/starts-with? rs (str gs "/")))
           :else                   (= gs rs)))))

(defn allowed?
  "True if `principal` already holds a grant covering `capability` (no prompt, no
  journal — a pure query)."
  [principal capability]
  (boolean (some #(covers? % capability) (granted principal))))

(defn check!
  "The authoritative gate. Returns true if `principal` may exercise `capability`
  in the active profile. Default-deny; on a missing grant, ask the consent prompt
  (if installed) and persist a yes as a grant. Every decision is journaled."
  [principal capability]
  (let [decision (cond
                   (allowed? principal capability) :allow
                   (and @prompt-fn (@prompt-fn principal capability))
                   (do (grant! principal capability) :granted)
                   :else :deny)]
    (swap! journal conj {:profile @active-profile* :principal principal
                         :capability capability :decision decision})
    (not= decision :deny)))

(defn reset-gate! []
  (reset! grants {}) (reset! active-profile* :default)
  (reset! journal []) (reset! prompt-fn nil))
