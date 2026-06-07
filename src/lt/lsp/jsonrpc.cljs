(ns lt.lsp.jsonrpc
  "JSON-RPC 2.0 over the LSP base protocol (Content-Length-framed messages).

  The pure codec foundation of the LSP/DAP client (ADR 0007 item 2). Transport —
  spawning the language-server subprocess and wiring stdio through the effect
  broker — is a separate slice; this namespace has no I/O and no DOM, so it runs
  in the fast node-test suite and stays runtime-agnostic.

  Wire frame: `Content-Length: <n>\\r\\n\\r\\n<utf8-json>`. Content-Length counts
  BYTES, not characters, so `encode`/`decode` are byte-accurate — a document body
  with multi-byte unicode will not desync the stream. `decode` is streaming: it
  returns any complete messages plus the leftover bytes (as a string) to prepend
  to the next chunk.")

(def ^:private jsonrpc-version "2.0")

;; ── message constructors ──────────────────────────────────────────────────
(defn request
  "A JSON-RPC request (expects a response keyed by `id`)."
  [id method params]
  (cond-> {:jsonrpc jsonrpc-version :id id :method method}
    (some? params) (assoc :params params)))

(defn notification
  "A JSON-RPC notification (no id, no response)."
  [method params]
  (cond-> {:jsonrpc jsonrpc-version :method method}
    (some? params) (assoc :params params)))

(defn response
  "A successful JSON-RPC response for request `id`."
  [id result]
  {:jsonrpc jsonrpc-version :id id :result result})

(defn error-response
  "An error JSON-RPC response for request `id`."
  [id code message]
  {:jsonrpc jsonrpc-version :id id :error {:code code :message message}})

;; ── UTF-8 byte helpers ────────────────────────────────────────────────────
(defn- ->bytes [s] (.encode (js/TextEncoder.) s))
(defn- bytes-> [u8] (.decode (js/TextDecoder.) u8))
(defn- byte-count [s] (.-length (->bytes s)))

;; ── encode ────────────────────────────────────────────────────────────────
(defn encode
  "Encode a message map to a framed LSP wire string."
  [msg]
  (let [json (js/JSON.stringify (clj->js msg))]
    (str "Content-Length: " (byte-count json) "\r\n\r\n" json)))

;; ── decode (streaming, byte-accurate) ─────────────────────────────────────
(defn- index-of-header-end
  "Byte index of the \\r\\n\\r\\n header terminator in `u8`, or -1."
  [u8]
  (let [n (.-length u8)]
    (loop [i 0]
      (cond
        (> (+ i 4) n) -1
        (and (= 13 (aget u8 i))       (= 10 (aget u8 (+ i 1)))
             (= 13 (aget u8 (+ i 2))) (= 10 (aget u8 (+ i 3)))) i
        :else (recur (inc i))))))

(defn- content-length [header-str]
  (when-let [m (re-find #"(?i)content-length:\s*(\d+)" header-str)]
    (js/parseInt (second m) 10)))

(defn- parse-json [json]
  (js->clj (js/JSON.parse json) :keywordize-keys true))

(defn decode
  "Parse every complete frame out of `buf` (a UTF-8 string, possibly a partial
  stream chunk). Returns `{:messages [clj-map…] :rest leftover-string}`; an
  incomplete trailing frame is returned verbatim in `:rest` to prepend to the
  next chunk."
  [buf]
  (loop [u8 (->bytes buf)
         msgs []]
    (let [hdr-end (index-of-header-end u8)]
      (if (neg? hdr-end)
        {:messages msgs :rest (bytes-> u8)}
        (let [clen       (content-length (bytes-> (.subarray u8 0 hdr-end)))
              body-start (+ hdr-end 4)
              body-end   (when clen (+ body-start clen))]
          (if (or (nil? clen) (> body-end (.-length u8)))
            {:messages msgs :rest (bytes-> u8)}        ; wait for more bytes
            (recur (.subarray u8 body-end)
                   (conj msgs (parse-json (bytes-> (.subarray u8 body-start body-end)))))))))))
