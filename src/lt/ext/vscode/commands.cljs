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

;; Bridge slots (set by the editor-coupled lt.ext.vscode.command-bridge, Electron):
;; mirror registrations into lt.objs.command, and let executeCommand fall through to
;; LightTable's own commands for ids this registry doesn't own.
(defonce on-register     (atom nil))     ; (fn [id f])
(defonce on-unregister   (atom nil))     ; (fn [id])
(defonce fallback-execute (atom nil))    ; (fn [id args]) -> result

(defn register-command
  "Register `id` → `f`; returns a Disposable that unregisters it. Throws if `id`
  is already taken (VSCode semantics)."
  [id f]
  (when (contains? @registry id)
    (throw (js/Error. (str "command '" id "' already exists"))))
  (swap! registry assoc id f)
  (when-let [h @on-register] (h id f))
  (types/disposable (fn []
                      (swap! registry dissoc id)
                      (when-let [h @on-unregister] (h id)))))

(defn execute-command
  "Run command `id` with `args`; returns a Promise of its result. Unknown ids fall
  through to the LightTable command system if a fallback is installed, else resolve
  to undefined (the absence shape, per the graceful-denial model)."
  [id & args]
  (.resolve js/Promise
            (if-let [f (get @registry id)]
              (apply f args)
              (when-let [fb @fallback-execute] (fb id args)))))

(defn has-command? [id] (contains? @registry id))

(defn command-ids [] (vec (keys @registry)))

(defn ns-object
  "The `vscode.commands` object injected into the shim."
  []
  #js {:registerCommand (fn [id f] (register-command id f))
       :executeCommand  (fn [id & args] (apply execute-command id args))
       :getCommands     (fn [& _] (.resolve js/Promise (clj->js (command-ids))))})

(defn reset-registry! []
  (reset! registry {})
  (reset! on-register nil)
  (reset! on-unregister nil)
  (reset! fallback-execute nil))
