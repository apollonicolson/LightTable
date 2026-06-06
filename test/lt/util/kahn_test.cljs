(ns lt.util.kahn-test
  "Seed test proving the node-test harness runs. kahn is dependency-free
  (clojure.set only), so it unit-tests cleanly under :node-test."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.util.kahn :as kahn]))

(deftest without-test
  (is (= #{:a :c} (kahn/without #{:a :b :c} :b)))
  (is (= #{:a} (kahn/without #{:a} :missing))))

(deftest no-incoming-test
  (testing "nodes with no incoming edges"
    (is (= #{:a} (kahn/no-incoming {:a #{:b} :b #{}})))
    (is (= #{:a :b} (kahn/no-incoming {:a #{} :b #{}})))))

(deftest normalize-test
  (is (= {:a #{:b} :b #{}} (kahn/normalize {:a #{:b}}))))

(deftest kahn-sort-test
  (testing "linear chain sorts dependency-first"
    (is (= [:a :b :c] (kahn/kahn-sort {:a #{:b} :b #{:c} :c #{}}))))
  (testing "independent nodes all returned"
    (is (= 2 (count (kahn/kahn-sort {:a #{} :b #{}})))))
  (testing "cyclic graph returns nil"
    (is (nil? (kahn/kahn-sort {:a #{:b} :b #{:a}})))))
