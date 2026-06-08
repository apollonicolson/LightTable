(ns lt.lsp.clojure-lsp-test
  "LIVE gate (ADR 0010): drives REAL clojure-lsp through lt.lsp.node-transport + the
  defport client + lt.lsp.service — the production server, not the fake. Spawns a
  tiny temp Clojure project, completes the handshake, opens a real .clj doc, and
  polls hover on `println` until clojure-lsp's analysis returns real content.

  PRESENCE-GATED: if clojure-lsp isn't on PATH the test passes trivially (CI without
  the binary stays green); where it IS installed (dev, and after a one-line install)
  it runs the full vertical against the real server."
  (:require [cljs.test :refer-macros [deftest is async]]
            [defport.lsp.client :as lsp]
            [lt.lsp.node-transport :as nt]
            [lt.lsp.service :as svc]
            [lt.lsp.hover :as hover]
            [lt.util.broker :as broker]))

(defn- clojure-lsp? []
  (let [r (.spawnSync broker/child-process "clojure-lsp" #js ["--version"])]
    (and (nil? (.-error r)) (zero? (.-status r)))))

(defn- temp-project []
  (let [dir (.join broker/path (.tmpdir broker/os) (str "lt-clj-lsp-" (gensym)))
        src (.join broker/path dir "src")]
    (.mkdirSync broker/fs src #js {:recursive true})
    (.writeFileSync broker/fs (.join broker/path dir "deps.edn") "{:deps {}}")
    (.writeFileSync broker/fs (.join broker/path src "sample.clj")
                    "(ns sample)\n(defn greet [n] (println \"hi\" n))\n(greet 1)\n")
    dir))

(deftest live-clojure-lsp-handshake-and-hover
  (if-not (clojure-lsp?)
    (is true "skipped — clojure-lsp not on PATH (install: gh release download from clojure-lsp/clojure-lsp)")
    (async done
      (let [root     (temp-project)
            root-uri (str "file://" root)
            doc-uri  (str root-uri "/src/sample.clj")
            text     (.readFileSync broker/fs (.join broker/path root "src" "sample.clj") "utf8")
            t        (nt/transport ["clojure-lsp"])
            s        (svc/create t)
            finish   (fn [] (lsp/disconnect! (svc/client s)) (done))]
        (lsp/connect-async!
         (svc/client s) {:root-uri root-uri}
         (fn [_client err]
           (is (nil? err) "REAL clojure-lsp completed the initialize handshake over node-transport")
           (svc/open-doc! s doc-uri "clojure" text)
           ;; hover on `println` (line 1, ~col 18); poll until analysis yields content
           (letfn [(poll [n]
                     (svc/hover
                      s doc-uri 1 18
                      (fn [result]
                        (let [txt (hover/hover->text result)]
                          (cond
                            (and txt (re-find #"(?i)println|clojure\.core" txt))
                            (do (is (re-find #"clojure\.core" txt)
                                    "real clojure-lsp hover resolved println to clojure.core/println")
                                (finish))
                            (zero? n)
                            (do (is false "timed out waiting for real clojure-lsp hover (analysis?)")
                                (finish))
                            :else (js/setTimeout #(poll (dec n)) 300))))))]
             (poll 60))))))))   ; ≤ ~18s for first-run analysis
