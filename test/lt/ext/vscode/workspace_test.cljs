(ns lt.ext.vscode.workspace-test
  "Phase 3b gate (ADR 0011): vscode.workspace doc registry + events + config."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.ext.vscode.workspace :as ws]
            [lt.editor.cm6 :as cm6]))

(deftest open-tracks-and-fires
  (ws/reset-workspace!)
  (let [opened (atom nil)
        wsobj  (ws/ns-object)]
    (.onDidOpenTextDocument wsobj (fn [d] (reset! opened d)))
    (ws/open-document! {:uri "file:///a.clj" :languageId "clojure" :text "x"})
    (is (= "file:///a.clj" (.-uri @opened)) "onDidOpen fired")
    (is (= 1 (.-length (.-textDocuments wsobj))) "textDocuments is a live getter")))

(deftest change-fires-with-content-changes
  (ws/reset-workspace!)
  (let [seen  (atom nil)
        wsobj (ws/ns-object)
        _     (.onDidChangeTextDocument wsobj (fn [e] (reset! seen e)))
        _     (ws/open-document! {:uri "file:///a" :text "abc\ndef"})
        state (cm6/make-state "abc\ndef")
        tr    (.update state #js {:changes #js {:from 1 :to 2 :insert "XY"}})
        new-text (.. tr -state -doc toString)]
    (ws/notify-change! "file:///a" (.-changes tr) new-text)
    (is (some? @seen) "onDidChangeTextDocument fired")
    (is (= 2 (.-version (.-document @seen))) "version bumped")
    (is (= "aXYc" (.-text (.lineAt (.-document @seen) 0))) "doc rebuilt from new text")
    (let [ch (aget (.-contentChanges @seen) 0)]
      (is (= 1 (.-rangeOffset ch)))
      (is (= "XY" (.-text ch))))))

(deftest configuration
  (ws/reset-workspace!)
  (ws/set-config! {"editor.tabSize" 2 "editor.insertSpaces" true})
  (let [cfg (.getConfiguration (ws/ns-object) "editor")]
    (is (= 2 (.get cfg "tabSize")))
    (is (= 7 (.get cfg "missing" 7)) "default returned")
    (is (.has cfg "insertSpaces"))
    (is (not (.has cfg "nope")))))
