(ns lt.ext.membrane
  "Phase 5a — the extension-host membrane (ADR 0012). A thin, neutral protocol of
  ~7 message categories (lifecycle / doc / invoke / register / effect / render /
  event) carried by request/response endpoints over a pluggable transport.

  The wire stays small; the shim absorbs API breadth on each side — the opposite of
  VSCode's per-member proxy. Driven over an in-process LOOPBACK transport first
  (node-gated) to prove the protocol is sufficient + thin before the real cross-
  process boundary (5b); `port-transport` is the real-boundary adapter (a MessagePort
  / contextBridge channel, transit+json-serialized). Pure — no editor/adapter deps."
  (:require [cognitect.transit :as transit]))

(defn loopback
  "Two connected transport ends. Each end's `:send` delivers (async, to mimic the
  process boundary) to the peer's handler, registered via `:on`."
  []
  (let [ha (atom nil) hb (atom nil)]
    [{:on (fn [h] (reset! ha h)) :send (fn [m] (js/setTimeout #(when @hb (@hb m)) 0))}
     {:on (fn [h] (reset! hb h)) :send (fn [m] (js/setTimeout #(when @ha (@ha m)) 0))}]))

(defonce ^:private writer (transit/writer :json))
(defonce ^:private reader (transit/reader :json))

(defn port-transport
  "Adapt a MessagePort-like object (`.postMessage` + `.onmessage` receiving a
  MessageEvent with `.data`) to the transport shape `{:on :send}`, with transit+json
  serialization (faster + key-caching + richer types than EDN). The real cross-
  process boundary (5b-2) drops a real MessagePort / contextBridge channel in here.
  Messages cross as transit-json STRINGS — CLJS data does not survive structured-
  clone — so membrane messages must be pure serializable clj data (keywords/sets/
  maps round-trip natively; JS conversion happens at the host/main edges, not on the
  wire)."
  [port]
  {:on   (fn [h] (set! (.-onmessage port) (fn [ev] (h (transit/read reader (.-data ev))))))
   :send (fn [m] (.postMessage port (transit/write writer m)))})

(def ^:private reply-types #{:result :effect-result})

(defn endpoint
  "Wrap a transport end with request/response correlation. Returns:
   - `:notify` (msg)        — fire-and-forget
   - `:request` (msg)       — send + return a Promise of the correlated reply
   - `:on` (handler)        — handle inbound NON-reply messages (a request handler
                              calls `:reply` to answer)
   - `:reply` (req resp)    — answer an inbound request, correlating by id"
  [t]
  (let [pending (atom {}) next-id (atom 0) handler (atom nil)]
    ((:on t)
     (fn [msg]
       (if (contains? reply-types (:t msg))
         (when-let [resolve (get @pending (:id msg))]
           (swap! pending dissoc (:id msg))
           (resolve msg))
         (when-let [h @handler] (h msg)))))
    {:notify  (fn [msg] ((:send t) msg))
     :request (fn [msg]
                (js/Promise.
                 (fn [resolve _reject]
                   (let [id (swap! next-id inc)]
                     (swap! pending assoc id resolve)
                     ((:send t) (assoc msg :id id))))))
     :on      (fn [h] (reset! handler h))
     :reply   (fn [req resp] ((:send t) (assoc resp :id (:id req))))}))
