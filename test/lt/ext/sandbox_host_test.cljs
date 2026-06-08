(ns lt.ext.sandbox-host-test
  "Phase 5b-2 capstone (ADR 0012): the assembled Tier-A host runs a real extension
  with NO Node — it activates, calls `vscode.workspace.fs.readFile`, that crosses the
  membrane as a gated effect (performed on the privileged side), and the bytes come
  back. Granted → reads; denied → the extension's own error path runs (graceful
  degradation, host stays up). End-to-end through loader + membrane-fs + the gate."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.membrane :as m]
            [lt.ext.sandbox-host :as host]
            [lt.ext.effects :as effects]
            [lt.sec.gate :as gate]
            [lt.util.broker :as broker]))

(defn- tmp-file [nm content]
  (let [dir (.join broker/path (.tmpdir broker/os) (str "lt-sbh-" (gensym)))]
    (.mkdirSync broker/fs dir #js {:recursive true})
    (let [f (.join broker/path dir nm)]
      (.writeFileSync broker/fs f content)
      f)))

(defn- ext-code [file]
  (str "const vscode = require('vscode');"
       "exports.activate = (ctx) => vscode.workspace.fs.readFile(" (pr-str file) ")"
       "  .then(b => ({ ok: true, text: b.toString() }), e => ({ ok: false, err: e.message }));"))

(defn- wire [principal grant?]
  (let [[main-t host-t] (m/loopback)
        main    (m/endpoint main-t)
        host-ep (m/endpoint host-t)
        file    (tmp-file "data.txt" "from disk")
        exts    (host/start! host-ep principal)]
    ((:on main) (fn [msg] (when (= :effect (:t msg)) (effects/handle-effect main principal msg))))
    (when grant? (gate/grant! principal (gate/cap :fs.read file)))
    {:main main :exts exts :file file}))

(deftest activates-and-reads-through-gated-membrane-fs
  (gate/reset-gate!)
  (let [{:keys [main file]} (wire "ext.sb" true)]
    (async done
      ;; :activate is a request → auto-awaited to the host's reply (the activate-return)
      (let [res ((:request main) {:t :activate :id "ext.sb" :code (ext-code file)})
            api (:data res)]
        (is (true? (.-ok api)) "extension activated + read the file via gated membrane fs — no Node")
        (is (= "from disk" (.-text api)) "the real bytes came back across the boundary"))
      (done))))

(deftest denied-fs-degrades-gracefully
  (gate/reset-gate!)
  (let [{:keys [main file]} (wire "ext.sb" false)]   ; no grant
    (async done
      (let [res ((:request main) {:t :activate :id "ext.sb" :code (ext-code file)})
            api (:data res)]
        (is (false? (.-ok api))
            "denied read → the extension's error path runs; the host stays up (graceful)"))
      (done))))
