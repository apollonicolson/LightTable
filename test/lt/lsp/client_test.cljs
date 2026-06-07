(ns lt.lsp.client-test
  "Smoke: defport's LSP client CORE — request/response correlation by id and
  inbound notification dispatch — compiles and runs under our shadow/node
  toolchain, driven by a fake in-memory transport. No live language server is
  needed (clojure-lsp isn't installed here); the live subprocess-transport
  integration against a real server is the next slice."
  (:require [clojure.test :refer [deftest is]]
            [defport.lsp.client :as lsp]))

(defn- fake-transport
  "A ClientTransport that records outbound messages into `sent`."
  [sent]
  (reify lsp/ClientTransport
    (transport-start! [this] this)
    (transport-send! [this msg] (swap! sent conj msg) this)
    (transport-recv!  [_] ::lsp/no-message)
    (transport-stop!  [this] this)
    (transport-alive? [_] true)))

(deftest request-response-correlation
  (let [sent   (atom [])
        client (lsp/create-client (fake-transport sent))
        got    (atom :unresolved)
        p      (lsp/request! client "textDocument/hover" {:uri "file:///a.clj"})]
    (lsp/then p (fn [result error] (reset! got [result error])))
    (is (= 1 (count @sent)) "request sent through the transport")
    (is (= "textDocument/hover" (:method (first @sent))))
    (let [id (:id (first @sent))]
      (lsp/dispatch-incoming! client {:jsonrpc "2.0" :id id :result {:contents "docs"}})
      (is (= [{:contents "docs"} nil] @got)
          "pending resolved with the result, correlated by id"))))

(deftest notification-dispatch
  (let [client (lsp/create-client (fake-transport (atom [])))
        diags  (atom nil)]
    (lsp/on-notification client "textDocument/publishDiagnostics"
                         (fn [params] (reset! diags params)))
    (lsp/dispatch-incoming! client {:jsonrpc "2.0"
                                    :method "textDocument/publishDiagnostics"
                                    :params {:uri "file:///a.clj" :diagnostics []}})
    (is (= {:uri "file:///a.clj" :diagnostics []} @diags)
        "inbound notification routed to its handler")))
