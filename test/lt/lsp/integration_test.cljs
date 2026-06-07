(ns lt.lsp.integration-test
  "End-to-end gate for the LSP client stack (ADR 0010 slice 2d, node half):
  lt.lsp.node-transport spawns a real protocol-speaking subprocess, the defport
  client completes the initialize handshake, lt.lsp.service syncs a document, and
  the server's publishDiagnostics flows back through the whole pipeline to a
  subscriber. Uses a fake LSP server (test/fixtures/fake-lsp-server.js) so no
  clojure-lsp install is required."
  (:require [cljs.test :refer-macros [deftest is async]]
            [defport.lsp.client :as lsp]
            [lt.lsp.node-transport :as nt]
            [lt.lsp.service :as svc]))

(deftest connect-handshake-sync-and-diagnostics
  (async done
    (let [t   (nt/transport ["node" "test/fixtures/fake-lsp-server.js"])
          s   (svc/create t)
          got (atom nil)]
      (svc/on-diagnostics s (fn [uri diags] (reset! got [uri (count diags)])))
      (lsp/connect-async!
       (svc/client s) {:root-uri "file:///tmp"}
       (fn [_client err]
         (is (nil? err) "initialize handshake completed against the subprocess")
         (svc/open-doc! s "file:///tmp/a.clj" "clojure" "(ns a)")
         (letfn [(poll [n]
                   (cond
                     @got
                     (do (is (= ["file:///tmp/a.clj" 1] @got)
                             "server's publishDiagnostics flowed through transport→client→service")
                         (is (= 1 (count (svc/diagnostics-for s "file:///tmp/a.clj")))
                             "service registry holds the diagnostic")
                         (lsp/disconnect! (svc/client s))
                         (done))

                     (zero? n)
                     (do (is false "timed out waiting for diagnostics through the full stack")
                         (lsp/disconnect! (svc/client s))
                         (done))

                     :else (js/setTimeout #(poll (dec n)) 30)))]
           (poll 100)))))))
