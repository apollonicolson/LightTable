(ns lt.lsp.jsonrpc-test
  "Gate for the LSP base-protocol / JSON-RPC 2.0 codec: framing, round-trip,
  streaming (split + concatenated chunks), and byte-accurate unicode."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string]
            [lt.lsp.jsonrpc :as rpc]))

(deftest constructors-have-jsonrpc-shape
  (is (= {:jsonrpc "2.0" :id 1 :method "initialize" :params {:x 1}}
         (rpc/request 1 "initialize" {:x 1})))
  (is (= {:jsonrpc "2.0" :method "exit"} (rpc/notification "exit" nil))
      "nil params are omitted")
  (is (= {:jsonrpc "2.0" :id 2 :result {:ok true}} (rpc/response 2 {:ok true})))
  (is (= {:jsonrpc "2.0" :id 3 :error {:code -32601 :message "no"}}
         (rpc/error-response 3 -32601 "no"))))

(deftest encode-frames-with-byte-length-header
  (let [wire (rpc/encode {:jsonrpc "2.0" :id 1 :method "ping"})]
    (is (re-find #"^Content-Length: \d+\r\n\r\n\{" wire) "header then body")
    (let [[hdr body] (string/split wire #"\r\n\r\n" 2)
          n (js/parseInt (second (re-find #"(\d+)" hdr)) 10)]
      (is (= n (.-length (.encode (js/TextEncoder.) body))) "Content-Length == body byte length"))))

(deftest round-trips-one-message
  (let [msg {:jsonrpc "2.0" :id 7 :method "textDocument/hover" :params {:uri "file:///a.clj"}}
        {:keys [messages rest]} (rpc/decode (rpc/encode msg))]
    (is (= [msg] messages) "decode(encode(msg)) == [msg]")
    (is (= "" rest) "no leftover")))

(deftest decodes-multiple-concatenated-frames
  (let [a (rpc/encode (rpc/request 1 "a" nil))
        b (rpc/encode (rpc/notification "b" {:n 2}))
        {:keys [messages rest]} (rpc/decode (str a b))]
    (is (= 2 (count messages)) "both frames parsed")
    (is (= "a" (:method (first messages))))
    (is (= "b" (:method (second messages))))
    (is (= "" rest))))

(deftest streams-a-frame-split-across-chunks
  (testing "a partial frame is held in :rest and completed by the next chunk"
    (let [wire (rpc/encode (rpc/request 9 "split" {:big "payload"}))
          cut  (quot (count wire) 2)
          chunk1 (subs wire 0 cut)
          chunk2 (subs wire cut)
          r1 (rpc/decode chunk1)]
      (is (empty? (:messages r1)) "no complete message in the first half")
      (let [r2 (rpc/decode (str (:rest r1) chunk2))]
        (is (= 1 (count (:messages r2))) "completed after the second half")
        (is (= "split" (:method (first (:messages r2)))))
        (is (= "" (:rest r2)))))))

(deftest byte-accurate-unicode-body
  ;; Content-Length is BYTES; a body with multi-byte chars must not desync.
  (let [msg {:jsonrpc "2.0" :id 1 :method "x" :params {:text "héllo→世界"}}
        {:keys [messages rest]} (rpc/decode (rpc/encode msg))]
    (is (= [msg] messages) "unicode body round-trips byte-accurately")
    (is (= "" rest))))

(deftest incomplete-header-is-held
  (let [{:keys [messages rest]} (rpc/decode "Content-Length: 5\r\n")]
    (is (empty? messages))
    (is (= "Content-Length: 5\r\n" rest) "partial header returned verbatim")))
