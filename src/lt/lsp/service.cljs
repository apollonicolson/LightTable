(ns lt.lsp.service
  "Editor-agnostic language-client service — ADR 0010 layer 1, the reusable
  boundary the editor AND (later) an extension host both drive. Owns a defport
  LSP client's lifecycle, document sync (didOpen/didChange/didClose with version
  tracking), a uri→diagnostics registry, and a subscriber fan-out for
  `textDocument/publishDiagnostics`.

  NOTHING here touches CM6 or lt.objs — it is node-loadable and node-tested
  against a fake/test transport. The CM6 rendering (layer 2) subscribes via
  `on-diagnostics` and maps to `lt.editor.cm6.diagnostics`; the live clojure-lsp
  subprocess transport is wired by the caller (slice 2b)."
  (:require [defport.lsp.client :as lsp]))

(defn create
  "Build a service over a defport `ClientTransport` (NOT yet connected — the
  caller runs `lsp/connect-async!` on `(client service)`). Installs the
  publishDiagnostics handler that updates the registry + fans out to subscribers."
  [transport]
  (let [client (lsp/create-client transport)
        service {:client      client
                 :docs        (atom {})   ; uri -> {:language-id :version}
                 :diagnostics (atom {})   ; uri -> [diagnostic ...]
                 :subscribers (atom [])}] ; [(fn [uri diagnostics]) ...]
    (lsp/on-notification
     client "textDocument/publishDiagnostics"
     (fn [params]
       (let [uri (:uri params) diags (vec (:diagnostics params))]
         (swap! (:diagnostics service) assoc uri diags)
         (doseq [f @(:subscribers service)] (f uri diags)))))
    service))

(defn client [service] (:client service))

(defn on-diagnostics
  "Subscribe `(fn [uri diagnostics])`, called on every publishDiagnostics.
  Returns the service."
  [service f]
  (swap! (:subscribers service) conj f)
  service)

(defn diagnostics-for [service uri]
  (get @(:diagnostics service) uri []))

(defn open-doc!
  "Register `uri` (version 1) and send didOpen. Returns the service."
  [service uri language-id text]
  (swap! (:docs service) assoc uri {:language-id language-id :version 1})
  (lsp/did-open! (:client service) uri language-id 1 text)
  service)

(defn change-doc!
  "Bump `uri`'s version and send a full-text didChange (the simplest correct
  sync; incremental ranges are a later refinement). Returns the service."
  [service uri text]
  (let [docs (swap! (:docs service) update-in [uri :version] (fnil inc 1))
        version (get-in docs [uri :version])]
    (lsp/did-change! (:client service) uri version [{:text text}])
    service))

(defn completion
  "Request completion at LSP position {:line :character} (0-based) for `uri`.
  Calls `(cb result)` with the raw LSP completion result, or `(cb nil)` on error."
  [service uri line character cb]
  (lsp/then (lsp/completion-at (:client service) uri line character)
            (fn [result error] (cb (when-not error result))))
  service)

(defn doc-version [service uri]
  (get-in @(:docs service) [uri :version]))

(defn open? [service uri]
  (contains? @(:docs service) uri))

(defn close-doc!
  "Unregister `uri`, drop its diagnostics, and send didClose. Returns the service."
  [service uri]
  (swap! (:docs service) dissoc uri)
  (swap! (:diagnostics service) dissoc uri)
  (lsp/did-close! (:client service) uri)
  service)
