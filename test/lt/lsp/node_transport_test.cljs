(ns lt.lsp.node-transport-test
  "Falsifier gate for lt.lsp.node-transport (ADR 0010 slice 2b): a real spawned
  subprocess round-trips a Content-Length framed message through stdin→stdout and
  back out via the ClientTransport contract. Uses `node` as a stdin→stdout echo,
  so no LSP server (clojure-lsp etc.) is required."
  (:require [cljs.test :refer-macros [deftest is async]]
            [defport.lsp.client :as lsp]
            [lt.lsp.node-transport :as nt]))

(deftest alive-false-before-start
  (let [t (nt/transport ["node" "-e" ""])]
    (is (false? (lsp/transport-alive? t)) "no process spawned yet → not alive")))

(deftest recv-empty-is-no-message
  (let [t (nt/transport ["node" "-e" ""])]
    (is (= ::lsp/no-message (lsp/transport-recv! t))
        "unstarted transport recv → ::no-message, never throws")))

(deftest spawn-send-recv-roundtrip
  (async done
    (let [t (nt/transport ["node" "-e" "process.stdin.pipe(process.stdout)"])]
      (lsp/transport-start! t)
      (is (lsp/transport-alive? t) "subprocess alive after start")
      (lsp/transport-send! t {:jsonrpc "2.0"
                              :method "textDocument/didOpen"
                              :params {:uri "file:///a.clj"}})
      (letfn [(poll [n]
                (let [m (lsp/transport-recv! t)]
                  (cond
                    (not= ::lsp/no-message m)
                    (do (is (= "textDocument/didOpen" (:method m))
                            "echoed frame decoded back through the transport")
                        (is (= {:uri "file:///a.clj"} (:params m)) "params preserved")
                        (lsp/transport-stop! t)
                        (done))

                    (zero? n)
                    (do (is false "timed out waiting for the echoed frame")
                        (lsp/transport-stop! t)
                        (done))

                    :else (js/setTimeout #(poll (dec n)) 20))))]
        (poll 100)))))
