(ns lt.ext.vscode.commands-test
  "Phase 2 gate (ADR 0011): the vscode.commands registry."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.vscode.commands :as c]))

(deftest register-execute-dispose
  (c/reset-registry!)
  (let [d (c/register-command "a.b" (fn [x] (str "got:" x)))]
    (is (c/has-command? "a.b"))
    (async done
      (.then (c/execute-command "a.b" "hi")
             (fn [r]
               (is (= "got:hi" r))
               (.dispose d)
               (is (not (c/has-command? "a.b")) "dispose unregisters")
               (done))))))

(deftest duplicate-throws
  (c/reset-registry!)
  (c/register-command "x" (fn [] nil))
  (is (thrown? js/Error (c/register-command "x" (fn [] nil)))
      "re-registering an id throws (VSCode semantics)"))

(deftest unknown-command-resolves-undefined
  (c/reset-registry!)
  (async done
    (.then (c/execute-command "nope")
           (fn [r] (is (nil? r) "absence shape, not a throw") (done)))))

(deftest bridge-hooks-fire
  (c/reset-registry!)
  (let [registered (atom nil) unregistered (atom nil)]
    (reset! c/on-register (fn [id _f] (reset! registered id)))
    (reset! c/on-unregister (fn [id] (reset! unregistered id)))
    (reset! c/fallback-execute (fn [id args] (str "fallback:" id ":" (first args))))
    (let [d (c/register-command "x.y" (fn [] nil))]
      (is (= "x.y" @registered) "on-register fired (→ lt.objs.command in Electron)")
      (.dispose d)
      (is (= "x.y" @unregistered) "on-unregister fired on dispose (→ cmd/forget)"))
    (async done
      (.then (c/execute-command "lt.native" "a")
             (fn [r]
               (is (= "fallback:lt.native:a" r)
                   "unknown id falls through to the LightTable command system")
               (done))))))
