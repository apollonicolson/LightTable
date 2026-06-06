(ns lt.substrate-smoke-test
  "M3/M4 substrate spike: prove sig's cell (reactive primitive) and res
  (resolution ≈ object/raise) compile and run under the LightTable shadow/node
  toolchain (cell uses the alien-signals npm engine). Not a permanent test — it
  verifies the substrate is importable before the BOT→cell/res migration starts."
  (:require [clojure.test :refer [deftest is]]
            [cell :as cell]
            [res :as res]))

(deftest cell-reactivity
  (let [c (cell/cell 1)
        doubled (cell/computed (fn [] (* 2 @c)))
        runs (atom 0)
        seen (atom nil)]
    ;; cells read via @, computeds are callable signals read via (c)
    ;; effect's return is treated as a cleanup fn by alien-signals — return nil
    (cell/effect (fn [] (reset! seen (doubled)) (swap! runs inc) nil))
    (is (= 2 (doubled)) "computed derives from cell")
    (is (= 2 @seen) "effect ran once with initial value")
    (reset! c 5)
    (is (= 10 (doubled)) "computed recomputes on cell change")
    (is (= 10 @seen) "effect re-ran with new derived value")
    (is (>= @runs 2) "effect re-ran at least once after the change")))

(deftest res-resolution
  ;; res/resolver + res/resolve: the BOT raise analogue. Minimal smoke — a
  ;; resolver that answers a query, proving the resolution machinery loads + runs.
  (is (fn? res/resolver) "res/resolver is callable")
  (is (fn? res/resolve) "res/resolve is callable"))
