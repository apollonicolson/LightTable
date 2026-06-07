(ns lt.editor.cm6.comment
  "CM6-native comment toggling — the replacement for CM5's comment addon
  (line/block/toggle/uncomment) behind lt.objs.editor's comment seam fns
  (ADR 0009; live commands :comment-selection / :toggle-comment-selection / … in
  editor/pool).

  CM6's @codemirror/commands provides toggleComment / toggleLineComment /
  toggleBlockComment as StateCommands that act on the CURRENT selection (not an
  explicit from/to like CM5). The comment tokens are taken from the language's
  `commentTokens` languageData, so this only does anything when the state carries
  a language that defines them — the official langs (javascript, json) do;
  legacy StreamLanguage modes need commentTokens attached in lt.editor.cm6.modes
  (a follow-up). Pure state→state here (via cm6/run-command), node-tested with a
  language state; the seam sets the selection then runs these on the live view."
  (:require ["@codemirror/commands" :as cm-commands]
            [lt.editor.cm6 :as cm6]))

(def ^:private toggle-cmd       (.-toggleComment cm-commands))
(def ^:private toggle-line-cmd  (.-toggleLineComment cm-commands))
(def ^:private toggle-block-cmd (.-toggleBlockComment cm-commands))

(defn toggle-line
  "Toggle line comments over the current selection. NEW state (unchanged if the
  language defines no line-comment token)."
  [state] (cm6/run-command state toggle-line-cmd))

(defn toggle-block
  "Toggle a block comment around the current selection. NEW state (unchanged if
  the language defines no block-comment tokens)."
  [state] (cm6/run-command state toggle-block-cmd))

(defn toggle
  "Toggle comments over the current selection — line comment when available, else
  block (CM6's toggleComment heuristic, matching CM5 toggle-comment). NEW state."
  [state] (cm6/run-command state toggle-cmd))
