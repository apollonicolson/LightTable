(ns lt.editor.cm6.commands
  "CM6 equivalents of the CM5 `CodeMirror.commands.*` that editor/pool.cljs binds
  (cursor motion + edits). Maps CM5 command names → @codemirror/commands
  StateCommands so the lt.objs.editor seam can run either backend's commands by
  name. A StateCommand runs against a view (or any {state, dispatch} target)."
  (:require ["@codemirror/commands" :as cm]))

(def ^:private name->cmd
  {"delCharAfter"      (.-deleteCharForward cm)
   "delCharBefore"     (.-deleteCharBackward cm)
   "deleteLine"        (.-deleteLine cm)
   "delGroupAfter"     (.-deleteGroupForward cm)
   "delGroupBefore"    (.-deleteGroupBackward cm)
   "delLineLeft"       (.-deleteToLineStart cm)
   "delWordAfter"      (.-deleteGroupForward cm)
   "delWordBefore"     (.-deleteGroupBackward cm)
   "goCharLeft"        (.-cursorCharLeft cm)
   "goCharRight"       (.-cursorCharRight cm)
   "goColumnLeft"      (.-cursorLineBoundaryLeft cm)
   "goColumnRight"     (.-cursorLineBoundaryRight cm)
   "goDocEnd"          (.-cursorDocEnd cm)
   "goDocStart"        (.-cursorDocStart cm)
   "goGroupLeft"       (.-cursorGroupLeft cm)
   "goGroupRight"      (.-cursorGroupRight cm)
   "goLineDown"        (.-cursorLineDown cm)
   "goLineEnd"         (.-cursorLineEnd cm)
   "goLineLeft"        (.-cursorLineBoundaryLeft cm)
   "goLineRight"       (.-cursorLineBoundaryRight cm)
   "goLineStart"       (.-cursorLineStart cm)
   "goLineStartSmart"  (.-cursorLineBoundaryBackward cm)
   "goLineUp"          (.-cursorLineUp cm)
   "goPageDown"        (.-cursorPageDown cm)
   "goPageUp"          (.-cursorPageUp cm)
   "goWordLeft"        (.-cursorGroupLeft cm)
   "goWordRight"       (.-cursorGroupRight cm)
   "killLine"          (.-deleteToLineEnd cm)
   "newlineAndIndent"  (.-insertNewlineAndIndent cm)
   "selectAll"         (.-selectAll cm)
   "transposeChars"    (.-transposeChars cm)})

(defn run
  "Run the CM6 command named `command` (string/keyword) on `view`. Returns the
  StateCommand's handled-boolean, or false if there is no CM6 equivalent (e.g.
  toggleOverwrite)."
  [view command]
  (if-let [f (name->cmd (name command))]
    (boolean (f view))
    false))
