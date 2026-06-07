(ns lt.time-test
  "Grounds the 'scroll back through program state' claim on the cell substrate:
  epochal recording, O(1) as-of, = writes are no-op epochs, and restore that is
  itself recorded (history never forgets)."
  (:require [clojure.test :refer [deftest is]]
            [cell :as cell]
            [lt.time :as t]))

(deftest records-epochal-succession
  (let [c (cell/cell {:n 0})
        r (t/recorded c)]
    (reset! c {:n 1})         ; epoch: {:n 0} -> {:n 1}
    (reset! c {:n 1})         ; = write: no-op epoch, NOT recorded
    (swap!  c update :n inc)  ; epoch: -> {:n 2}
    (is (= [{:n 0} {:n 1} {:n 2}] (t/history r))
        "succession retained; an = write is a no-op epoch")
    (is (= 3 (t/epochs r)))
    (is (= {:n 0} (t/at r 0)) "as-of epoch 0 is the retained value (O(1), no replay)")
    (is (= {:n 2} @c) "live value is the latest epoch")))

(deftest restore-is-destructive-to-value-not-to-history
  (let [c (cell/cell 0)
        r (t/recorded c)]
    (reset! c 1)
    (reset! c 2)
    (t/restore! r 0)          ; time-travel the live value back to epoch 0
    (is (= 0 @c) "live cell restored to epoch 0's value")
    (is (= [0 1 2 0] (t/history r))
        "restore is itself an epoch; the log never forgets and can scroll forward")))
