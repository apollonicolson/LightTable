(ns lt.ext.membrane-test
  "Phase 5a gate (ADR 0012): the 7-category membrane protocol carries real host
  operations over an in-process loopback — register (host→main), invoke
  (main→host, reading the host's LOCAL mirror), effect (host→main, GATED on the
  main side, deny→grant→allow), and doc-sync (main→host, updating the local mirror).
  Wired to the real gate + TextDocument; proves the protocol is sufficient + thin
  before the real cross-process boundary."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.membrane :as m]
            [lt.sec.gate :as gate]
            [lt.ext.vscode.document :as doc]))

(deftest protocol-carries-the-core-flows
  (gate/reset-gate!)
  (let [[main-t host-t] (m/loopback)
        main     (m/endpoint main-t)
        host     (m/endpoint host-t)
        registry (atom #{})                                   ; main-side: advertised providers
        mirror   (atom (doc/make-text-document {:uri "file:///a" :text "ab"}))]  ; host-side local mirror
    ;; main handles host→main: register, effect (gated here — on the privileged side)
    ((:on main)
     (fn [msg]
       (case (:t msg)
         :register (swap! registry conj (:feature msg))
         :effect   ((:reply main) msg
                    {:t :effect-result
                     :ok (gate/check! (:principal msg) (:capability msg))})
         nil)))
    ;; host handles main→host: doc-sync (local mirror), invoke (runs provider locally)
    ((:on host)
     (fn [msg]
       (case (:t msg)
         :doc    (reset! mirror (doc/make-text-document {:uri (:uri msg) :text (:text msg)}))
         :invoke ((:reply host) msg
                  {:t :result
                   :data (clj->js [{:label (str "len:" (.-length (.getText @mirror)))}])})
         nil)))
    (let [cap        (gate/cap :fs.read "/x")
          invoke-msg {:t :invoke :feature :completion :uri "file:///a" :pos 0}
          effect-msg {:t :effect :principal "ext.p" :capability cap :op :read}
          delay      (fn [ms] (js/Promise. (fn [r _] (js/setTimeout #(r nil) ms))))]
      (async done
        ((:notify host) {:t :register :feature :completion})
        ;; shadow compiles this async body with auto-await: each (:request ...) form
        ;; resolves to its reply inline (sequential async).
        (let [res1 ((:request main) invoke-msg)]
          (is (= #{:completion} @registry) "register crossed the membrane")
          (is (= "len:2" (.-label (aget (:data res1) 0)))
              "invoke ran the provider on the host's LOCAL mirror (no per-read RPC)"))
        (let [res2 ((:request host) effect-msg)]
          (is (false? (:ok res2)) "effect denied by default — gate on the MAIN side"))
        (gate/grant! "ext.p" cap)
        (let [res3 ((:request host) effect-msg)]
          (is (true? (:ok res3)) "effect allowed after grant, across the membrane"))
        ((:notify main) {:t :doc :op :change :uri "file:///a" :text "abcd"})
        (delay 15)                                            ; let the doc notify land
        (let [res4 ((:request main) invoke-msg)]
          (is (= "len:4" (.-label (aget (:data res4) 0)))
              "doc-sync updated the host's local mirror"))
        (done)))))
