(ns lt.lsp.defport-smoke-test
  "Protocol-substrate spike: prove defport's framing codec — the byte-accurate
  base of the LSP/DAP/MCP/CDP clients — compiles and RUNS under the LightTable
  shadow/node toolchain. Supersedes the retracted hand-rolled lt.lsp.jsonrpc.
  Verifies the substrate is importable before the LSP-client slice builds on it."
  (:require [clojure.test :refer [deftest is]]
            [defport.transports.framing :as framing]))

(deftest content-length-framing-roundtrip
  (let [msg {:jsonrpc "2.0" :id 1 :method "initialize" :params {:rootUri "file:///x"}}
        wire (framing/encode-message msg)                 ; Node Buffer
        [msgs _state] (framing/feed (framing/empty-state) wire)]
    (is (= 1 (count msgs)) "one Content-Length-framed message decoded")
    (is (= "initialize" (:method (first msgs))) "round-trips with keyword keys")
    (is (= {:rootUri "file:///x"} (:params (first msgs))) "params preserved")))

(deftest streaming-partial-then-complete
  (let [wire (framing/encode-message {:jsonrpc "2.0" :id 2 :method "x"})
        half (quot (.-length wire) 2)
        c1 (.subarray wire 0 half)
        c2 (.subarray wire half)
        [m1 s1] (framing/feed (framing/empty-state) c1)
        [m2 _]  (framing/feed s1 c2)]
    (is (empty? m1) "no message from the first chunk")
    (is (= 1 (count m2)) "completed after the second chunk")
    (is (= "x" (:method (first m2))) "streaming reassembly works")))
