(ns lt.ext.vscode.commands
  "Phase 2 of the VSCode extension host (ADR 0011): the `vscode.commands` namespace
  — registerCommand / executeCommand / getCommands over a registry. Pure +
  node-tested (no editor deps). The bridge that also registers these INTO
  lt.objs.command (so extension commands are LightTable commands, and executeCommand
  can reach LightTable commands) is the editor-coupled half (phase 2b).

  Registry is process-global here; it becomes domain-scoped under the Qubes-style
  domain model (one registry per domain)."
  (:require [lt.ext.vscode.types :as types]))

(defonce ^:private registry (atom {}))   ; id -> handler fn

(defn register-command
  "Register `id` → `f`; returns a Disposable that unregisters it. Throws if `id`
  is already taken (VSCode semantics)."
  [id f]
  (when (contains? @registry id)
    (throw (js/Error. (str "command '" id "' already exists"))))
  (swap! registry assoc id f)
  (types/disposable (fn [] (swap! registry dissoc id))))

(defn execute-command
  "Run command `id` with `args`; returns a Promise of its result (undefined if no
  such command — the absence shape, per the graceful-denial model)."
  [id & args]
  (.resolve js/Promise
            (when-let [f (get @registry id)] (apply f args))))

(defn has-command? [id] (contains? @registry id))

(defn command-ids [] (vec (keys @registry)))

(defn ns-object
  "The `vscode.commands` object injected into the shim."
  []
  #js {:registerCommand (fn [id f] (register-command id f))
       :executeCommand  (fn [id & args] (apply execute-command id args))
       :getCommands     (fn [& _] (.resolve js/Promise (clj->js (command-ids))))})

(defn reset-registry! [] (reset! registry {}))
