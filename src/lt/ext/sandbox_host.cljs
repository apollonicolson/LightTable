(ns lt.ext.sandbox-host
  "Tier-A sandboxed host dispatcher (ADR 0012, 5b-2 host side). Assembles the no-Node
  pieces into a runnable host:
   - the membrane `endpoint` (its only channel to the editor),
   - the Node-free loader (eval-in-scope, injected require),
   - a vscode shim whose `workspace.fs` routes over the membrane effect channel.
  An extension activates and runs with NO Node and NO direct OS access; the editor
  (main) drives it over the membrane and every effect is gated on the privileged
  side. Pure assembly — runs in the real sandboxed renderer (5b-2 Electron wiring)
  or over a loopback (node-gated)."
  (:require [lt.ext.loader :as loader]
            [lt.ext.vscode.api :as api]
            [lt.ext.vscode.fs-membrane :as fsm]))

(defn make-host-shim
  "The vscode shim for a sandboxed host: the standard shim, but `workspace.fs` routes
  through the membrane `endpoint` (Tier A has no broker/fs)."
  [endpoint principal]
  (let [shim (api/make-vscode principal)]
    (set! (.-fs (.-workspace shim)) (fsm/make-file-system endpoint))
    shim))

(defn start!
  "Run the host loop over `endpoint` for the extension `principal`. Handles
  `:activate` as a REQUEST: load + run the extension with the membrane shim, await
  its activate() return (which may itself await gated effects over the membrane),
  then reply `{:t :result :data <activate-return>}` so the editor knows activation
  completed and what it produced. Returns the activated-extensions atom
  (id → {:ctx :api})."
  [endpoint principal]
  (let [exts       (atom {})
        require-fn (loader/make-require (make-host-shim endpoint principal))]
    ((:on endpoint)
     (fn [msg]
       (case (:t msg)
         :activate (let [{:keys [id code]} msg
                         ctx #js {:subscriptions #js []}
                         {:keys [api]} (loader/activate-extension code require-fn ctx)]
                     (-> (.resolve js/Promise api)
                         (.then (fn [result]
                                  (swap! exts assoc id {:ctx ctx :api result})
                                  ((:reply endpoint) msg {:t :result :data result})))))
         nil)))
    exts))
