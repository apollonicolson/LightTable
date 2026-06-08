(ns lt.ext.vscode.types-test
  "Phase 2 gate (ADR 0011): the vscode value types."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.ext.vscode.types :as t]))

(deftest position
  (let [p (t/->Position 1 4)]
    (is (= 1 (.-line p)))
    (is (= 4 (.-character p)))
    (is (.isEqual p (t/->Position 1 4)))
    (is (.isBefore (t/->Position 0 9) (t/->Position 1 0)))
    (is (.isAfter (t/->Position 2 0) (t/->Position 1 9)))
    (let [tr (.translate p 1 1)]
      (is (= 2 (.-line tr))) (is (= 5 (.-character tr))))
    (is (= 0 (.compareTo p (t/->Position 1 4))))
    (is (= -1 (.compareTo p (t/->Position 2 0))))))

(deftest range-both-signatures
  (let [r1 (t/make-range (t/->Position 0 0) (t/->Position 2 3))
        r2 (t/make-range 0 0 2 3)]
    (is (= 0 (.-line (.-start r2))))
    (is (= 3 (.-character (.-end r2))))
    (is (.contains r1 (t/->Position 1 4)) "(1,4) within (0,0)-(2,3)")
    (is (not (.contains r1 (t/->Position 3 0))))
    (is (.isEqual r1 r2))
    (is (instance? t/VRange r1) "make-range yields Range instances")))

(deftest uri-file-and-parse
  (let [u (.file t/Uri "/proj/x.clj")]
    (is (= "file" (.-scheme u)))
    (is (= "/proj/x.clj" (.-fsPath u)))
    (is (= "/proj/x.clj" (.-path u))))
  (let [u (.parse t/Uri "https://example.com/a/b?q=1#f")]
    (is (= "https" (.-scheme u)))
    (is (= "example.com" (.-authority u)))
    (is (= "/a/b" (.-path u)))
    (is (= "q=1" (.-query u)))
    (is (= "f" (.-fragment u)))))

(deftest disposable-fires-once
  (let [n (atom 0)
        d (t/disposable (fn [] (swap! n inc)))]
    (.dispose d)
    (.dispose d)
    (is (= 1 @n) "dispose is idempotent")))

(deftest event-emitter-subscribe-fire-unsubscribe
  (let [e   (t/event-emitter)
        got (atom nil)
        sub (.event e (fn [d] (reset! got d)))]
    (.fire e "x")
    (is (= "x" @got))
    (.dispose sub)
    (.fire e "y")
    (is (= "x" @got) "unsubscribed listener no longer fires")))
