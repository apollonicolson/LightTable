(ns lt.lsp.node-transport
  "Node `child_process` subprocess transport for the defport LSP client — the
  CLJS side defport itself does NOT ship (its subprocess.cljc is JVM-only, and
  its docstring notes the Node version 'is not yet shipped'). Lives at our edge
  per CLAUDE.md (platform glue at the boundary), keeping vendored defport pristine.

  Spawns an LSP server (e.g. [\"clojure-lsp\"]), writes Content-Length framed
  JSON-RPC to its stdin, and buffers messages decoded from stdout for the
  client's poll-driven CLJS driver: `transport-recv!` returns the next message
  or `::lsp/no-message` when the buffer is empty (the ClientTransport contract).

  Byte-level framing is `defport.transports.framing` (smoke-verified). Runs in
  Electron/Node; node-testable against a fake echo subprocess."
  (:require [defport.lsp.client :as lsp]
            [defport.transports.framing :as framing]
            [lt.util.broker :as broker]))

(defn transport
  "A ClientTransport running `argv` as a subprocess. `opts` may carry :cwd.
  The process is spawned on `transport-start!`, not here."
  ([argv] (transport argv {}))
  ([argv opts]
   (let [st (atom {:proc nil
                   :fstate (framing/empty-state)
                   :queue []        ; vector FIFO of decoded messages
                   :exited? false})]
     (reify lsp/ClientTransport
       (transport-start! [this]
         (let [proc (.spawn broker/child-process
                            (first argv)
                            (clj->js (vec (rest argv)))
                            #js {:cwd (:cwd opts) :stdio "pipe"})]
           (swap! st assoc :proc proc)
           (.on (.-stdout proc) "data"
                (fn [chunk]
                  (let [[msgs s2] (framing/feed (:fstate @st) chunk)]
                    (swap! st assoc :fstate s2)
                    (when (seq msgs) (swap! st update :queue into msgs)))))
           (.on proc "exit" (fn [_ _] (swap! st assoc :exited? true)))
           this))
       (transport-send! [this message]
         (when-let [proc (:proc @st)]
           (.write (.-stdin proc) (framing/encode-message message)))
         this)
       (transport-recv! [_]
         ;; atomic dequeue: pop the front and report what was there before.
         (let [[old _] (swap-vals! st update :queue #(if (seq %) (subvec % 1) %))]
           (if (seq (:queue old)) (nth (:queue old) 0) ::lsp/no-message)))
       (transport-stop! [this]
         (when-let [proc (:proc @st)] (.kill proc))
         this)
       (transport-alive? [_]
         (boolean (and (:proc @st) (not (:exited? @st)))))))))
