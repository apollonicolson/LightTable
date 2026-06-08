(ns lt.ext.vscode.fs
  "vscode.workspace.fs — a GATED FileSystem, the first real consumer of the
  capability gate (lt.sec.gate). Every effect is checked (default-deny) against the
  calling extension's `principal`; the actual I/O routes through the M1 broker.
  A denied effect rejects with an EACCES-shaped error (effectful op → error, per
  the graceful-denial model). The bytes contract is Uint8Array (a Node Buffer is
  one)."
  (:require [lt.sec.gate :as gate]
            [lt.util.broker :as broker]))

(defn- uri->path [uri] (if (string? uri) uri (.-fsPath uri)))

(defn- deny [verb p]
  (.reject js/Promise (js/Error. (str "EACCES: capability denied (" verb " " p ")"))))

(defn make-file-system [principal]
  #js {:readFile  (fn [uri]
                    (let [p (uri->path uri)]
                      (if (gate/check! principal (gate/cap :fs.read p))
                        (.resolve js/Promise (.readFileSync broker/fs p))   ; Buffer ⊂ Uint8Array
                        (deny "fs.read" p))))
       :writeFile (fn [uri content]
                    (let [p (uri->path uri)]
                      (if (gate/check! principal (gate/cap :fs.write p))
                        (do (.writeFileSync broker/fs p content)
                            (.resolve js/Promise js/undefined))
                        (deny "fs.write" p))))
       :stat      (fn [uri]
                    (let [p (uri->path uri)]
                      (if (gate/check! principal (gate/cap :fs.read p))
                        (.resolve js/Promise
                                  (let [s (.statSync broker/fs p)]
                                    #js {:type (if (.isDirectory s) 2 1)
                                         :size (.-size s)
                                         :mtime (.getTime (.-mtime s))}))
                        (deny "stat" p))))})
