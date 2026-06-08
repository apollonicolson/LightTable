(ns lt.ext.fs-membrane-test
  "Phase 5b-2/5c gate (ADR 0012): a Tier-A (no-Node) extension's `workspace.fs` read
  crosses the membrane as a semantic `effect`, the privileged main side binds the
  principal + gates it (deny→grant→allow), and the broker performs the real I/O —
  proving the gate-on-the-membrane chokepoint end-to-end with a real file."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.membrane :as m]
            [lt.ext.effects :as effects]
            [lt.ext.vscode.fs-membrane :as fsm]
            [lt.sec.gate :as gate]
            [lt.util.broker :as broker]))

(defn- settle
  "Promise → a Promise that always RESOLVES to {:ok v} or {:err msg}. Avoids
  try/catch around auto-awaited forms (shadow only awaits tail Promise forms)."
  [p]
  (.then p (fn [v] {:ok v}) (fn [e] {:err (.-message e)})))

(defn- tmp-file [name content]
  (let [dir (.join broker/path (.tmpdir broker/os) (str "lt-fsm-" (gensym)))]
    (.mkdirSync broker/fs dir #js {:recursive true})
    (let [f (.join broker/path dir name)]
      (.writeFileSync broker/fs f content)
      f)))

(deftest workspace-fs-read-gated-across-the-membrane
  (gate/reset-gate!)
  (let [[main-t host-t] (m/loopback)
        main      (m/endpoint main-t)
        host      (m/endpoint host-t)
        principal "ext.sandboxed"
        fs        (fsm/make-file-system host)            ; host-side, no Node
        file      (tmp-file "hello.txt" "hi from disk")]
    ;; main binds the principal to this host connection + owns :effect
    ((:on main) (fn [msg] (when (= :effect (:t msg)) (effects/handle-effect main principal msg))))
    ;; fs-shim promises are untyped method calls — NOT auto-awaited — so an explicit
    ;; .then chain is the correct pattern here (see the shadow async-test gotcha).
    (async done
      (-> (settle (.readFile fs file))
          (.then (fn [r1]
                   (is (:err r1) "read denied by default — host self-declares nothing; main gates it")
                   (gate/grant! principal (gate/cap :fs.read file))
                   (settle (.readFile fs file))))
          (.then (fn [r2]
                   (is (= "hi from disk" (.toString (:ok r2)))
                       "granted read crossed the membrane → gate → broker → content back")
                   (done)))))))

(deftest principal-is-bound-not-self-declared
  (gate/reset-gate!)
  (let [[main-t host-t] (m/loopback)
        main (m/endpoint main-t)
        host (m/endpoint host-t)
        fs   (fsm/make-file-system host)
        file (tmp-file "secret.txt" "x")]
    ;; main binds "trusted.ext"; a grant to some OTHER principal must not help
    ((:on main) (fn [msg] (when (= :effect (:t msg)) (effects/handle-effect main "trusted.ext" msg))))
    (gate/grant! "attacker.ext" (gate/cap :fs.read file))
    (async done
      (-> (settle (.readFile fs file))
          (.then (fn [r]
                   (is (:err r) "a grant to a different principal does not authorize this host (principal is connection-bound)")
                   (done)))))))
