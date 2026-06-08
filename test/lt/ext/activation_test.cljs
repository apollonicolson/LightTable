(ns lt.ext.activation-test
  "Phase 1 gate (ADR 0011): activationEvents ↔ runtime trigger matching."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.ext.activation :as a]))

(deftest star-and-startup
  (is (a/activates? ["*"] {:kind :startup}))
  (is (a/activates? ["onStartupFinished"] {:kind :startup}))
  (is (not (a/activates? ["onLanguage:clojure"] {:kind :startup}))))

(deftest language
  (is (a/activates? ["onLanguage:clojure"] {:kind :language :value "clojure"}))
  (is (not (a/activates? ["onLanguage:clojure"] {:kind :language :value "python"}))))

(deftest command
  (is (a/activates? ["onCommand:a.b"] {:kind :command :value "a.b"}))
  (is (not (a/activates? ["onCommand:a.b"] {:kind :command :value "a.c"}))))

(deftest workspace-contains
  (is (a/activates? ["workspaceContains:**/deps.edn"]
                    {:kind :workspace-file :paths ["/proj/deps.edn"]}))
  (is (a/activates? ["workspaceContains:**/*.clj"]
                    {:kind :workspace-file :paths ["/proj/src/a.clj"]}))
  (is (a/activates? ["workspaceContains:project.clj"]
                    {:kind :workspace-file :paths ["/proj/project.clj"]}))
  (is (not (a/activates? ["workspaceContains:**/deps.edn"]
                         {:kind :workspace-file :paths ["/proj/x.txt"]})))
  (is (not (a/activates? ["workspaceContains:**/*.clj"]
                         {:kind :workspace-file :paths ["/proj/a.cljs.bak"]}))
      "suffix-anchored: *.clj does not match a.cljs.bak"))

(deftest any-event-matches
  (is (a/activates? ["onLanguage:py" "onCommand:a"] {:kind :command :value "a"}))
  (is (not (a/activates? [] {:kind :startup}))))
