(ns lt.ext.port-transport-test
  "Phase 5b-2 gate (ADR 0012): the membrane works over a REAL-boundary-shaped
  transport with EDN serialization. Two `port-transport`s connected by a fake
  MessageChannel (messages cross as STRINGS) carry the protocol — register, invoke
  (clj-data payload, not clj->js), and a gated effect whose capability map
  round-trips through EDN and still matches a grant. Proves the wire is serializable
  pure-clj data, ready to drop a real MessagePort / contextBridge channel in."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.membrane :as m]
            [lt.sec.gate :as gate]))

(defn- fake-channel
  "Two MessagePort-likes; a.postMessage(s) → b.onmessage({data:s}) (async), and
  vice-versa. The payload is whatever was posted (the transport's EDN string), so a
  non-string leaking onto the wire would blow up read-string — serialization is real."
  []
  (let [a (js-obj) b (js-obj)]
    (set! (.-postMessage a) (fn [s] (js/setTimeout #(when-let [h (.-onmessage b)] (h #js {:data s})) 0)))
    (set! (.-postMessage b) (fn [s] (js/setTimeout #(when-let [h (.-onmessage a)] (h #js {:data s})) 0)))
    [a b]))

(deftest membrane-over-serialized-ports
  (gate/reset-gate!)
  (let [[pa pb]  (fake-channel)
        main     (m/endpoint (m/port-transport pa))
        host     (m/endpoint (m/port-transport pb))
        registry (atom #{})
        cap      (gate/cap :fs.read "/x")]
    ((:on main)
     (fn [msg]
       (case (:t msg)
         :register (swap! registry conj (:feature msg))
         :effect   ((:reply main) msg {:t :effect-result :ok (gate/check! (:principal msg) (:capability msg))})
         nil)))
    ((:on host)
     (fn [msg]
       (case (:t msg)
         ;; clj data on the wire (NOT clj->js) — converted to JS only at the edges
         :invoke ((:reply host) msg {:t :result :data [{:label "ok"}]})
         nil)))
    (async done
      ((:notify host) {:t :register :feature :completion})
      (let [r1 ((:request main) {:t :invoke :feature :completion})]
        (is (= #{:completion} @registry) "register survived EDN serialization (keyword preserved)")
        (is (= "ok" (:label (first (:data r1)))) "invoke clj-data payload round-tripped through the wire"))
      (let [r2 ((:request host) {:t :effect :principal "p" :capability cap :op :read})]
        (is (false? (:ok r2)) "effect denied by default — serialized capability gated on the main side"))
      (gate/grant! "p" cap)
      (let [r3 ((:request host) {:t :effect :principal "p" :capability cap :op :read})]
        (is (true? (:ok r3)) "capability map round-tripped through EDN and still matched the grant"))
      (done))))
