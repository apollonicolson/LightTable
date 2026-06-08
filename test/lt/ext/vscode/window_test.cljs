(ns lt.ext.vscode.window-test
  "Phase 3b gate (ADR 0011): vscode.window messages + output channel."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.vscode.window :as w]))

(deftest message-logs-and-routes-to-sink
  (w/reset-window!)
  (w/set-message-sink! (fn [_level _text items] (first items)))
  (let [win (w/ns-object)]
    (async done
      (.then (.showInformationMessage win "hello" "OK" "Cancel")
             (fn [chosen]
               (is (= "OK" chosen) "sink's chosen item resolves")
               (is (= [{:level :info :text "hello"}] @w/message-log) "message logged")
               (done))))))

(deftest output-channel
  (let [ch (w/create-output-channel "ext-out")]
    (is (= "ext-out" (.-name ch)))
    (.append ch "a")
    (.appendLine ch "b")
    (is (= "ab\n" (._value ch)))
    (.clear ch)
    (is (= "" (._value ch)))))
