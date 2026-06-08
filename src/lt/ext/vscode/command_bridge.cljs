(ns lt.ext.vscode.command-bridge
  "Phase 2b of the VSCode extension host (ADR 0011): bridge `vscode.commands` into
  LightTable's command system (editor-coupled — Electron only). After `install!`:
  every `vscode.commands.registerCommand` becomes a real `lt.objs.command` (callable
  from the command bar / keybindings), disposing it forgets the LightTable command,
  and `vscode.commands.executeCommand` falls through to LightTable's own commands for
  ids the extension registry doesn't own. Bidirectional."
  (:require [lt.ext.vscode.commands :as c]
            [lt.objs.command :as cmd]))

(defn install! []
  (reset! c/on-register
          (fn [id f]
            (cmd/command {:command (keyword id)
                          :desc    id
                          :hidden  true
                          :exec    (fn [& args] (apply f args))})))
  (reset! c/on-unregister
          (fn [id] (cmd/forget (keyword id))))
  (reset! c/fallback-execute
          (fn [id args] (apply cmd/exec! (keyword id) args)))
  nil)
