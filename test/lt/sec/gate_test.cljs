(ns lt.sec.gate-test
  "Gate for the capability gate (the authority layer): default-deny, scoped
  matching (incl. fs path-prefix), grant/revoke, profile isolation, consent
  prompt, and the effect journal."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.sec.gate :as g]))

(deftest default-deny
  (g/reset-gate!)
  (is (not (g/allowed? "ext.a" (g/cap :fs.read "/proj/x"))) "nothing granted → denied")
  (is (false? (g/check! "ext.a" (g/cap :fs.read "/proj/x"))) "check! denies + journals")
  (is (= :deny (:decision (last @g/journal)))))

(deftest grant-allows-then-revoke-denies
  (g/reset-gate!)
  (g/grant! "ext.a" (g/cap :process.spawn "clojure-lsp"))
  (is (g/allowed? "ext.a" (g/cap :process.spawn "clojure-lsp")))
  (is (not (g/allowed? "ext.a" (g/cap :process.spawn "rm"))) "exact scope for process")
  (g/revoke! "ext.a" (g/cap :process.spawn "clojure-lsp"))
  (is (not (g/allowed? "ext.a" (g/cap :process.spawn "clojure-lsp")))))

(deftest fs-scope-is-path-prefix
  (g/reset-gate!)
  (g/grant! "ext.a" (g/cap :fs.read "/proj"))
  (is (g/allowed? "ext.a" (g/cap :fs.read "/proj")))
  (is (g/allowed? "ext.a" (g/cap :fs.read "/proj/src/x.clj")) "prefix covered")
  (is (not (g/allowed? "ext.a" (g/cap :fs.read "/projector"))) "/projector is NOT under /proj")
  (is (not (g/allowed? "ext.a" (g/cap :fs.read "/etc/passwd"))) "out of scope")
  (is (not (g/allowed? "ext.a" (g/cap :fs.write "/proj/x"))) "read grant ≠ write"))

(deftest unscoped-grant-covers-all
  (g/reset-gate!)
  (g/grant! "ext.a" (g/cap :net nil))
  (is (g/allowed? "ext.a" (g/cap :net "example.com")))
  (is (g/allowed? "ext.a" (g/cap :net "evil.test"))))

(deftest profiles-isolate-grants
  (g/reset-gate!)
  (g/grant! "ext.a" (g/cap :fs.read "/proj"))
  (g/set-active-profile! :student)
  (is (not (g/allowed? "ext.a" (g/cap :fs.read "/proj"))) "another profile has no grants")
  (g/grant! "ext.a" (g/cap :fs.read "/home"))
  (is (g/allowed? "ext.a" (g/cap :fs.read "/home")))
  (g/set-active-profile! :default)
  (is (g/allowed? "ext.a" (g/cap :fs.read "/proj")) "default profile's grant intact")
  (is (not (g/allowed? "ext.a" (g/cap :fs.read "/home"))) "student's grant doesn't leak"))

(deftest consent-prompt-grants-on-yes
  (g/reset-gate!)
  (g/set-prompt! (fn [_principal capability] (= :fs.read (:kind capability))))  ; allow reads, deny else
  (is (true? (g/check! "ext.a" (g/cap :fs.read "/proj"))) "prompt said yes")
  (is (g/allowed? "ext.a" (g/cap :fs.read "/proj")) "a yes persists as a grant")
  (is (= :granted (:decision (last @g/journal))))
  (is (false? (g/check! "ext.a" (g/cap :process.spawn "rm"))) "prompt said no")
  (is (= :deny (:decision (last @g/journal)))))

(deftest journal-records-every-decision
  (g/reset-gate!)
  (g/grant! "ext.a" (g/cap :fs.read "/proj"))
  (g/check! "ext.a" (g/cap :fs.read "/proj/x"))   ; allow
  (g/check! "ext.a" (g/cap :net "x.com"))          ; deny
  (is (= [:allow :deny] (map :decision @g/journal)))
  (is (= "ext.a" (:principal (first @g/journal)))))
