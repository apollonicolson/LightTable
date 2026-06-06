(ns lt.util.js-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [lt.util.js :as ujs]))

(deftest escape-test
  (testing "nil/falsy input returns nil"
    (is (nil? (ujs/escape nil)))
    (is (nil? (ujs/escape false))))
  (testing "empty string passes through unchanged"
    (is (= "" (ujs/escape ""))))
  (testing "string without special chars is unchanged"
    (is (= "hello world" (ujs/escape "hello world"))))
  (testing "each special character is escaped to its entity"
    (is (= "&amp;" (ujs/escape "&")))
    (is (= "&lt;" (ujs/escape "<")))
    (is (= "&gt;" (ujs/escape ">")))
    (is (= "&quot;" (ujs/escape "\"")))
    (is (= "&#39;" (ujs/escape "'")))
    (is (= "&#x2F;" (ujs/escape "/"))))
  (testing "multiple/repeated special chars all escaped (global replace)"
    (is (= "&amp;&amp;" (ujs/escape "&&")))
    (is (= "&lt;a href=&quot;x&#x2F;y&quot;&gt;"
           (ujs/escape "<a href=\"x/y\">")))))

(deftest ->clj-test
  (testing "JS object becomes a map with keywordized keys"
    (is (= {:a 1 :b 2}
           (ujs/->clj #js {:a 1 :b 2}))))
  (testing "nested JS structures convert recursively"
    (is (= {:a {:b [1 2 3]}}
           (ujs/->clj #js {:a #js {:b #js [1 2 3]}}))))
  (testing "JS array becomes a vector"
    (is (= ["x" "y"] (ujs/->clj #js ["x" "y"])))))

(deftest toggler-test
  (testing "when cur equals op, returns op2"
    (is (= :off (ujs/toggler :on :on :off))))
  (testing "when cur does not equal op, returns op"
    (is (= :on (ujs/toggler :off :on :off)))
    (is (= :on (ujs/toggler :something-else :on :off))))
  (testing "works with arbitrary values"
    (is (= 2 (ujs/toggler 1 1 2)))
    (is (= 1 (ujs/toggler 99 1 2)))))

(deftest now-test
  (testing "returns a number of ms close to Date.now"
    (let [n (ujs/now)]
      (is (number? n))
      (is (< (js/Math.abs (- n (js/Date.now))) 1000))))
  (testing "is monotonically non-decreasing across calls"
    (let [a (ujs/now)
          b (ujs/now)]
      (is (>= b a)))))

(deftest wait-test
  (testing "executes func after the delay"
    (async done
      (ujs/wait 15 (fn [] (is true "callback fired") (done)))))
  (testing "does not fire synchronously"
    (async done
      (let [fired (atom false)]
        (ujs/wait 15 (fn [] (reset! fired true)))
        ;; immediately after scheduling, callback must not have run yet
        (is (false? @fired))
        (ujs/wait 30 (fn []
                       (is (true? @fired) "callback ran after the delay")
                       (done)))))))

(deftest every-test
  (testing "executes func repeatedly on the interval and returns a timer id"
    (async done
      (let [calls (atom 0)
            id (ujs/every 10 (fn [] (swap! calls inc)))]
        ;; after enough time for ~2 ticks, stop and assert it fired >= 2 times
        (ujs/wait 35 (fn []
                       (js/clearInterval id)
                       (is (>= @calls 2)
                           (str "interval fired " @calls " times"))
                       (done)))))))
