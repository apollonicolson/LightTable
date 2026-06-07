(ns lt.editor.cm6
  "CodeMirror 6 editor seam (M5). Thin wrapper over @codemirror/state's immutable
  EditorState — the substrate the LightTable editor migrates onto.

  CM6 is epochal, like cell / Datomic / defport's framing: state is an immutable
  VALUE, and edits are TRANSACTIONS producing a new state (not in-place mutation
  of a CM5 instance). That shift is the whole migration — lt.objs.editor (1060
  lines, ~151 CM5 touch points: getValue/setValue/getCursor/markText/setOption…)
  becomes a delegation onto this seam.

  This namespace is slice 1: the import + state-model proof. The EditorView (DOM,
  needs a browser-test) and the lt.objs.editor delegation are later slices."
  (:require ["@codemirror/state" :as cm-state]))

(def ^:private EditorState (.-EditorState cm-state))

(defn make-state
  "Create an immutable CM6 EditorState seeded with document string `s`."
  [s]
  (.create EditorState #js {:doc s}))

(defn doc-string
  "The document text of `state`."
  [state]
  (.toString (.-doc state)))

(defn doc-length
  "The document length (chars) of `state`."
  [state]
  (.-length (.-doc state)))

(defn replace-range
  "Return a NEW state with the range [from, to) replaced by `text`, via a
  transaction. The input `state` is unchanged (CM6 state is immutable)."
  [state from to text]
  (-> state
      (.update #js {:changes #js {:from from :to to :insert text}})
      (.-state)))
