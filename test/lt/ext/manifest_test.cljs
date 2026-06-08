(ns lt.ext.manifest-test
  "Phase 1 gate (ADR 0011): package.json → normalized descriptor + validation."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.ext.manifest :as manifest]))

(deftest parses-core-fields
  (let [d (manifest/parse {:name "x" :publisher "pub" :version "1.2.3"
                           :main "./out.js" :engines {:vscode "^1.0.0"}
                           :activationEvents ["onLanguage:clojure"]
                           :contributes {:commands [{:command "x.y"}]}})]
    (is (= "pub.x" (:id d)) "id = publisher.name")
    (is (= "./out.js" (:main d)))
    (is (= "^1.0.0" (:engine d)) "engines.vscode")
    (is (= ["onLanguage:clojure"] (:activation-events d)))
    (is (= [{:command "x.y"}] (manifest/contributes d :commands)))))

(deftest validity
  (is (manifest/valid? (manifest/parse {:name "x" :publisher "p" :main "m"
                                        :engines {:vscode "^1.0.0"}})))
  (let [bad (manifest/parse {:name "x"})]
    (is (not (manifest/valid? bad)))
    (is (some #{"missing main"} (manifest/errors bad)))
    (is (some #{"missing publisher"} (manifest/errors bad)))))
