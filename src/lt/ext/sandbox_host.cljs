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
            [lt.ext.vscode.fs-membrane :as fsm]
            [lt.ext.vscode.languages :as languages]
            [lt.ext.vscode.document :as document]))

(defn make-host-shim
  "The vscode shim for a sandboxed host: the standard shim, but `workspace.fs` routes
  through the membrane `endpoint` (Tier A has no broker/fs)."
  [endpoint principal]
  (let [shim (api/make-vscode principal)]
    (set! (.-fs (.-workspace shim)) (fsm/make-file-system endpoint))
    shim))

(defn- invoke! [endpoint mirror msg]
  (let [{:keys [feature uri line character]} msg
        doc (get @mirror uri)
        pos #js {:line line :character character}
        reply (fn [data] ((:reply endpoint) msg {:t :result :data data}))]
    (when doc
      (case feature
        :completion (.then (languages/provide-completions doc pos)
                           (fn [items] (reply (languages/completion-items->data items))))
        :hover      (.then (languages/provide-hover doc pos)
                           (fn [h] (reply (languages/hover->text h))))
        :definition (.then (languages/provide-definition doc pos)
                           (fn [d] (reply (languages/definition->location d))))
        :symbols    (.then (languages/provide-document-symbols doc)
                           (fn [s] (reply (languages/document-symbols->data s))))
        :references (.then (languages/provide-references doc pos)
                           (fn [r] (reply (languages/references->data r))))
        nil))))

(defn start!
  "Run the host loop over `endpoint` for the extension `principal`. Handles:
   - `:activate` (request) — load + run the extension with the membrane shim, await
     its activate() return, reply `{:t :result :data <return>}`;
   - `:doc` — keep the host's LOCAL document mirror current (reads never cross);
   - `:invoke` (request) — run the registered provider on the local mirror, map the
     result to serializable clj data, reply.
  Returns the activated-extensions atom (id → {:ctx :api})."
  [endpoint principal]
  (let [exts       (atom {})
        mirror     (atom {})                       ; uri → TextDocument (local; reads stay here)
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
         :doc      (let [{:keys [op uri text]} msg]
                     (if (= op :close)
                       (swap! mirror dissoc uri)
                       (swap! mirror assoc uri (document/make-text-document {:uri uri :text text}))))
         :invoke   (invoke! endpoint mirror msg)
         nil)))
    exts))
