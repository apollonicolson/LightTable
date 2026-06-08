(ns lt.ext.host-test
  "Phase 1 gate (ADR 0011): the host loads a real sample-extension fixture, builds
  an ExtensionContext, runs activate(context), and disposes on deactivate — the
  full skeleton lifecycle, no editor/Electron required."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.ext.host :as host]))

(def ^:private fixture "test/fixtures/sample-extension")

(deftest reads-manifest
  (let [d (host/read-manifest fixture)]
    (is (= "lt-test.sample-ext" (:id d)))
    (is (= "./extension.js" (:main d)))
    (is (= ["onLanguage:clojure" "onCommand:sample.hello"] (:activation-events d)))
    (is (= fixture (:dir d)))))

(deftest should-activate-by-trigger
  (let [d (host/read-manifest fixture)]
    (is (host/should-activate? d {:kind :language :value "clojure"}))
    (is (host/should-activate? d {:kind :command :value "sample.hello"}))
    (is (not (host/should-activate? d {:kind :startup})))))

(deftest activate-runs-and-deactivate-disposes
  (let [d      (host/read-manifest fixture)
        active (host/activate! d)]
    (is (:active? active))
    (is (= "pong" (.ping (:api active))) "activate() returned the extension API")
    (is (= 1 (.. (:api active) -calls -activated)) "activate() ran once")
    (is (= 1 (.-length (.-subscriptions (:context active)))) "a disposable was registered")
    (let [done (host/deactivate! active)]
      (is (not (:active? done)))
      (is (= 1 (.. (:api active) -calls -disposed)) "subscription disposed")
      (is (= 1 (.. (:api active) -calls -deactivated)) "deactivate() ran"))))
