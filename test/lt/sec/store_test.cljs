(ns lt.sec.store-test
  "Gate for capability-grant persistence: the grant map round-trips through EDN to
  a directory and reloads into the gate (incl. across profiles)."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.sec.store :as store]
            [lt.sec.gate :as gate]
            [lt.util.broker :as broker]))

(defn- tmp-dir []
  (let [d (.join broker/path (.tmpdir broker/os) (str "lt-sec-" (gensym)))]
    (.mkdirSync broker/fs d #js {:recursive true})
    d))

(deftest save-load-roundtrip
  (gate/reset-gate!)
  (gate/grant! "ext.a" (gate/cap :fs.read "/proj"))
  (gate/grant! "ext.a" (gate/cap :net "x.com"))
  (gate/set-active-profile! :student)
  (gate/grant! "ext.b" (gate/cap :fs.read "/home"))
  (gate/set-active-profile! :default)
  (let [dir (tmp-dir)]
    (store/save! dir)
    (gate/reset-gate!)
    (is (not (gate/allowed? "ext.a" (gate/cap :fs.read "/proj"))) "reset cleared grants")
    (store/load! dir)
    ;; default-profile grants restored
    (is (gate/allowed? "ext.a" (gate/cap :fs.read "/proj")))
    (is (gate/allowed? "ext.a" (gate/cap :net "x.com")))
    ;; another profile's grants restored too
    (gate/set-active-profile! :student)
    (is (gate/allowed? "ext.b" (gate/cap :fs.read "/home")) "per-profile grants persist")))

(deftest load-missing-is-noop
  (gate/reset-gate!)
  (gate/grant! "ext.a" (gate/cap :fs.read "/proj"))
  (is (= {} (store/load! (tmp-dir))) "absent file → {} and gate untouched")
  (is (gate/allowed? "ext.a" (gate/cap :fs.read "/proj")) "existing grants intact"))
