(ns lt.architecture-test
  "Architecture fitness function — the 'non-viral' guard.

  The editor CORE (`lt.objs.*`, `lt.editor.*`) must never depend on an ADAPTER
  (`lt.ext.*` — the VSCode shim — or `lt.lsp.*` — the LSP-client integration). The
  dependency arrow points only INWARD: adapters depend on the core, never the
  reverse. That is what keeps a foreign API design (VSCode's mutable/async/OO
  idioms) from rippling into the core 'like a virus' — the core literally cannot
  reference the adapter, so it cannot be infected.

  The one designated boundary-glue file (`test_bridge`, which legitimately knows
  both sides for e2e) is exempt. This test reads the source on disk so the
  guarantee can't silently rot as parity grows."
  (:require [cljs.test :refer-macros [deftest is]]))

(def ^:private fs   (js/require "fs"))
(def ^:private path (js/require "path"))

(def ^:private forbidden #"lt\.ext\.|lt\.lsp\.")
(def ^:private exempt #{"test_bridge.cljs"})

(defn- cljs-files [dir]
  (let [out (atom [])]
    (letfn [(walk [d]
              (doseq [e (.readdirSync fs d)]
                (let [p (.join path d e)]
                  (if (.isDirectory (.statSync fs p))
                    (walk p)
                    (when (.endsWith e ".cljs") (swap! out conj p))))))]
      (when (.existsSync fs dir) (walk dir)))
    @out))

(defn- core-files []
  (->> (concat (cljs-files "src/lt/objs") (cljs-files "src/lt/editor"))
       (remove (fn [p] (exempt (.basename path p))))))

(deftest core-files-are-discoverable
  (is (pos? (count (core-files)))
      "sanity: the test runs from the submodule root and finds core sources"))

(deftest core-does-not-depend-on-adapters
  (doseq [f (core-files)]
    (let [src (.readFileSync fs f "utf8")]
      (is (not (re-find forbidden src))
          (str f " references an adapter (lt.ext.*/lt.lsp.*). The core must not "
               "depend on the VSCode shim or the LSP integration — the dependency "
               "arrow points only inward (non-viral guard). If this is genuine "
               "boundary glue, add it to the `exempt` set with justification.")))))
