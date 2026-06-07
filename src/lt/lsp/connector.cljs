(ns lt.lsp.connector
  "Editor connector — ADR 0010 layer 2, the thin glue between the editor-agnostic
  lt.lsp.service and the live editor. Diagnostics-only for now (completion/hover
  extend this later).

  INERT by default: with no server configured it does nothing, so the editor
  keeps working (ADR 0008). Call `configure!` with a server argv to activate.
  Lifecycle: `open!` registers an editor for a uri + syncs it (connecting lazily
  on first doc), `change!` pushes edits, `close!` tears down; inbound
  publishDiagnostics is rendered into the matching editor's CM6 diagnostics layer."
  (:require [lt.lsp.service :as svc]
            [lt.lsp.node-transport :as nt]
            [defport.lsp.client :as lsp]
            [lt.objs.editor :as editor]))

(defonce ^:private state
  (atom {:service nil :argv nil :root-uri nil
         :connected? false :connecting? false
         :editors {}}))   ; uri -> editor object

(defn path->uri [path] (str "file://" path))

(defn configure!
  "Set the LSP server command (e.g. [\"clojure-lsp\"]) + optional :root-uri.
  Inert until a doc opens."
  ([argv] (configure! argv {}))
  ([argv opts]
   (swap! state assoc :argv (vec argv) :root-uri (:root-uri opts))
   nil))

(defn- render-diagnostics! [uri diags]
  (when-let [e (get-in @state [:editors uri])]
    (editor/set-diagnostics e diags)))

(defn- ensure-connected! [cb]
  (let [{:keys [service connected? connecting? argv root-uri]} @state]
    (cond
      connected?  (cb service)
      (nil? argv) nil                                   ; inert — no server
      connecting? (js/setTimeout #(ensure-connected! cb) 30)
      :else
      (let [s (doto (svc/create (nt/transport argv))
                (svc/on-diagnostics render-diagnostics!))]
        (swap! state assoc :service s :connecting? true)
        (lsp/connect-async!
         (svc/client s) {:root-uri (or root-uri "file:///")}
         (fn [_ err]
           (swap! state assoc :connected? (not (boolean err)) :connecting? false)
           (when-not err (cb s))))))))

(defn open!
  "Register `editor` for `uri` and sync it to the server (connecting if needed)."
  [editor uri language-id text]
  (swap! state assoc-in [:editors uri] editor)
  (ensure-connected! (fn [s] (svc/open-doc! s uri language-id text)))
  nil)

(defn change! [uri text]
  (when (:connected? @state)
    (svc/change-doc! (:service @state) uri text))
  nil)

(defn close! [uri]
  (swap! state update :editors dissoc uri)
  (when (:connected? @state)
    (svc/close-doc! (:service @state) uri))
  nil)

(defn reset-all!
  "Disconnect + clear all connector state (test teardown / reconfigure)."
  []
  (when-let [s (:service @state)]
    (try (lsp/disconnect! (svc/client s)) (catch :default _ nil)))
  (reset! state {:service nil :argv nil :root-uri nil
                 :connected? false :connecting? false :editors {}})
  nil)
