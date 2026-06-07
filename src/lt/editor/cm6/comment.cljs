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
(def ^:private line-cmd         (.-lineComment cm-commands))
(def ^:private block-cmd        (.-blockComment cm-commands))

;; ── pure state→state (node-tested) ────────────────────────────────────────────
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

(defn line
  "Always ADD line comments over the selection (CM5 lineComment). NEW state."
  [state] (cm6/run-command state line-cmd))

(defn block
  "Always ADD a block comment around the selection (CM5 blockComment). NEW state."
  [state] (cm6/run-command state block-cmd))

;; ── live view runners (the seam calls these on a CM6 editor's view) ────────────
;; StateCommands take an EditorView directly and act on its CURRENT selection, so
;; the seam needs no explicit from/to — the view already holds the user's
;; selection. Each returns whether the command was handled.
(defn line!       [view] (boolean (line-cmd view)))
(defn block!      [view] (boolean (block-cmd view)))
(defn toggle!     [view] (boolean (toggle-cmd view)))
(defn uncomment!
  "CM6 has no pure uncomment; toggleLineComment removes comments when the lines are
  already commented (and would add them otherwise). Used for :uncomment-selection."
  [view] (boolean (toggle-line-cmd view)))
