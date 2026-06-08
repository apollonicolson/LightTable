(ns lt.ext.vscode.fs-test
  "Gate for the gated vscode.workspace.fs — the first real consumer of lt.sec.gate:
  default-deny, then allow after a grant. Reads a real file via the broker."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.vscode.fs :as vfs]
            [lt.sec.gate :as gate]))

(deftest read-is-gated
  (gate/reset-gate!)
  (let [fsobj (vfs/make-file-system "ext.p")]
    (async done
      ;; default-deny → the promise rejects (effectful op → error)
      (.then (.readFile fsobj "package.json")
             (fn [_] (is false "should be denied by default") (done))
             (fn [err]
               (is (re-find #"capability denied" (.-message err)) "denied with EACCES-shaped error")
               (is (= :deny (:decision (last @gate/journal))) "journaled as deny")
               ;; grant fs.read for the file, then it succeeds
               (gate/grant! "ext.p" (gate/cap :fs.read "package.json"))
               (.then (.readFile fsobj "package.json")
                      (fn [bytes]
                        (is (pos? (.-length bytes)) "allowed after grant → bytes")
                        (is (= :allow (:decision (last @gate/journal))))
                        (done))
                      (fn [_] (is false "should be allowed after grant") (done))))))))

(deftest write-needs-write-capability
  (gate/reset-gate!)
  (gate/grant! "ext.p" (gate/cap :fs.read "/tmp"))   ; read grant only
  (let [fsobj (vfs/make-file-system "ext.p")]
    (async done
      (.then (.writeFile fsobj "/tmp/lt-fs-test.txt" (js/Uint8Array. 0))
             (fn [_] (is false "read grant must not authorize write") (done))
             (fn [err] (is (re-find #"capability denied \(fs.write" (.-message err))) (done))))))
