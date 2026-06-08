(ns lt.ext.vscode.fs-membrane
  "Tier-A (no-Node) `vscode.workspace.fs` — the sandboxed host has no `broker/fs`, so
  each op sends a semantic `effect` request over the membrane to the privileged main
  side (which binds the principal, gates, and performs via the broker). The extension
  sees the same FileSystem contract; the I/O + the gate live across the boundary.
  Denied ops reject with an EACCES-shaped error (graceful denial). Pure — no Node,
  no broker, no gate (all main-side)."
  (:require [lt.ext.membrane]))   ; for the endpoint contract; no runtime dep

(defn- uri->path [uri] (if (string? uri) uri (.-fsPath uri)))

(defn- effect! [endpoint op path content]
  (-> ((:request endpoint) {:t :effect :op op :path path :content content})
      (.then (fn [res]
               (if (:ok res)
                 (:data res)
                 (throw (js/Error. (str "EACCES: capability denied (" (name op) " " path ")"))))))))

(defn make-file-system
  "A `vscode.workspace.fs` bound to a membrane host `endpoint`."
  [endpoint]
  #js {:readFile  (fn [uri]         (effect! endpoint :fs.read  (uri->path uri) nil))
       :writeFile (fn [uri content] (effect! endpoint :fs.write (uri->path uri) content))
       :stat      (fn [uri]         (effect! endpoint :fs.stat  (uri->path uri) nil))})
