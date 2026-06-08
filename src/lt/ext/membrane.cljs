(ns lt.ext.membrane
  "Phase 5a — the extension-host membrane (ADR 0012). A thin, neutral protocol of
  ~7 message categories (lifecycle / doc / invoke / register / effect / render /
  event) carried by request/response endpoints over a pluggable transport.

  The wire stays small; the shim absorbs API breadth on each side — the opposite of
  VSCode's per-member proxy. Driven over an in-process LOOPBACK transport first
  (node-gated) to prove the protocol is sufficient + thin before the real cross-
  process boundary (5b). Pure — no editor/adapter deps.")

(defn loopback
  "Two connected transport ends. Each end's `:send` delivers (async, to mimic the
  process boundary) to the peer's handler, registered via `:on`."
  []
  (let [ha (atom nil) hb (atom nil)]
    [{:on (fn [h] (reset! ha h)) :send (fn [m] (js/setTimeout #(when @hb (@hb m)) 0))}
     {:on (fn [h] (reset! hb h)) :send (fn [m] (js/setTimeout #(when @ha (@ha m)) 0))}]))

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
