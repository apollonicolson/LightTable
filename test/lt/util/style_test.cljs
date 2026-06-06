(ns lt.util.style-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.util.style :as style]))

;; lt.util.style/->px is defined as (str (or s 0) "px").
;; (or s 0) yields s unless s is falsey (nil or false), then 0.
;; str then concatenates the result with "px".
;; The fn is pure (no DOM/Electron), so it is fully node-safe.

(deftest ->px-numbers
  (testing "positive integers get a px suffix"
    (is (= "75px" (style/->px 75)))
    (is (= "1px" (style/->px 1))))
  (testing "zero is preserved (0 is truthy in cljs, so it is not replaced)"
    (is (= "0px" (style/->px 0))))
  (testing "negative numbers get a px suffix"
    (is (= "-5px" (style/->px -5))))
  (testing "floating point numbers stringify as cljs str would"
    ;; (str 1.5) => "1.5"; (str 2.0) => "2" under cljs number printing.
    (is (= "1.5px" (style/->px 1.5)))
    (is (= "2px" (style/->px 2.0)))))

(deftest ->px-falsey
  (testing "nil falls back to 0"
    (is (= "0px" (style/->px nil))))
  (testing "false falls back to 0 (documented behaviour)"
    (is (= "0px" (style/->px false)))))

(deftest ->px-strings
  (testing "numeric strings are concatenated, not coerced"
    (is (= "75px" (style/->px "75"))))
  (testing "already-suffixed strings are concatenated verbatim (double suffix)"
    (is (= "75pxpx" (style/->px "75px"))))
  (testing "empty string is truthy, so it is kept and only px is appended"
    (is (= "px" (style/->px ""))))
  (testing "arbitrary css value strings are concatenated"
    (is (= "autopx" (style/->px "auto")))
    (is (= "100%px" (style/->px "100%")))))

(deftest ->px-other-truthy
  (testing "the keyword 0 is truthy distinct case: zero stays zero"
    ;; guards against any future change replacing 0 incorrectly
    (is (= "0px" (style/->px 0)))))
