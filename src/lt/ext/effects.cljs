(ns lt.ext.effects
  "Main-side (privileged) handler for membrane `effect` messages — the gate-on-the-
  contextBridge chokepoint (ADR 0012, the substance of 5c). A Tier-A host (sandboxed,
  no Node) can't touch the OS; it sends *semantic* effect requests over the membrane
  ({:t :effect :op :fs.read :path …}). THIS side:
   - binds the `principal` to the connection (the host never self-declares it — it
     can't be trusted to; one host ↔ one principal),
   - maps the op to a capability and checks the gate (default-deny),
   - performs the I/O via the M1 broker,
   - replies {:t :effect-result :ok …}.
  This is the only path from a sandboxed extension to the OS. fs ops now; process/net
  slot in the same way."
  (:require [lt.sec.gate :as gate]
            [lt.util.broker :as broker]))

(defn- capability-for [op path]
  (case op
    :fs.read  (gate/cap :fs.read path)
    :fs.write (gate/cap :fs.write path)
    :fs.stat  (gate/cap :fs.read path)))   ; stat reads metadata

(defn- perform [op path content]
  (case op
    :fs.read  (.readFileSync broker/fs path)                    ; Buffer ⊂ Uint8Array
    :fs.write (do (.writeFileSync broker/fs path content) nil)
    :fs.stat  (let [s (.statSync broker/fs path)]
                #js {:type (if (.isDirectory s) 2 1) :size (.-size s) :mtime (.getTime (.-mtime s))})))

(defn handle-effect
  "Gate-check + perform + reply for one inbound `:effect` msg, on behalf of the host
  bound to `principal`."
  [endpoint principal msg]
  (let [{:keys [op path content]} msg]
    (if (gate/check! principal (capability-for op path))
      ((:reply endpoint) msg {:t :effect-result :ok true :data (perform op path content)})
      ((:reply endpoint) msg {:t :effect-result :ok false}))))
