(ns lt.sec.center-test
  "Gate for the control center's management/query layer: cross-principal/profile
  grant views, revoke-all, journal filtering, and the audit summary."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.sec.center :as c]
            [lt.sec.gate :as gate]))

(deftest grant-views-across-principals
  (gate/reset-gate!)
  (gate/grant! "ext.a" (gate/cap :fs.read "/proj"))
  (gate/grant! "ext.a" (gate/cap :net "x.com"))
  (gate/grant! "ext.b" (gate/cap :fs.read "/home"))
  (is (= #{"ext.a" "ext.b"} (set (c/principals))))
  (is (= 2 (count (c/principal-grants "ext.a"))))
  (is (= #{"ext.a" "ext.b"} (set (keys (c/all-grants))))))

(deftest revoke-all-clears-a-principal
  (gate/reset-gate!)
  (gate/grant! "ext.a" (gate/cap :fs.read "/proj"))
  (gate/grant! "ext.a" (gate/cap :net "x.com"))
  (c/revoke-all! "ext.a")
  (is (empty? (c/principal-grants "ext.a")))
  (is (not (gate/allowed? "ext.a" (gate/cap :fs.read "/proj")))))

(deftest profiles-listed
  (gate/reset-gate!)
  (gate/grant! "ext.a" (gate/cap :fs.read "/proj"))
  (gate/set-active-profile! :student)
  (gate/grant! "ext.a" (gate/cap :fs.read "/home"))
  (is (= #{:default :student} (set (c/profiles))))
  (is (= ["ext.a"] (c/principals :default)))
  (is (= 1 (count (c/principal-grants "ext.a" :default)))))

(deftest journal-filtering-and-summary
  (gate/reset-gate!)
  (gate/grant! "ext.a" (gate/cap :fs.read "/proj"))
  (gate/check! "ext.a" (gate/cap :fs.read "/proj/x"))   ; allow (granted /proj)
  (gate/check! "ext.a" (gate/cap :net "x.com"))          ; deny
  (gate/check! "ext.b" (gate/cap :fs.write "/etc"))      ; deny
  (is (= 2 (count (c/denials))) "two denials recorded (net + fs.write)")
  (is (= 2 (count (c/journal-for "ext.a"))) "ext.a: allow + deny")
  (let [s (c/summary)]
    (is (= :default (:active-profile s)))
    (is (= 1 (get-in s [:grants :default "ext.a"])) "ext.a holds 1 grant")
    (is (= {:allow 1 :deny 2} (:journal s)) "decision tallies")))
