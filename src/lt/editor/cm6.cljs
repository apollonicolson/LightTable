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
  "Return a NEW state with the offset range [from, to) replaced by `text`, via a
  transaction. The input `state` is unchanged (CM6 state is immutable)."
  [state from to text]
  (-> state
      (.update #js {:changes #js {:from from :to to :insert text}})
      (.-state)))

;; ── positions ──────────────────────────────────────────────────────────────
;; LightTable/CM5 positions are {:line :ch}, 0-based. CM6 positions are a single
;; integer offset into the doc, and CM6 lines are 1-based. This pair is the crux
;; adapter the whole editor port hangs on.
(defn pos->offset
  "LightTable {:line :ch} (0-based) → CM6 offset."
  [state {:keys [line ch]}]
  (+ (.-from (.line (.-doc state) (inc line))) ch))

(defn offset->pos
  "CM6 offset → LightTable {:line :ch} (0-based)."
  [state offset]
  (let [l (.lineAt (.-doc state) offset)]
    {:line (dec (.-number l)) :ch (- offset (.-from l))}))

;; ── document ───────────────────────────────────────────────────────────────
(defn line-count [state] (.-lines (.-doc state)))
(defn line-text   [state n] (.-text (.line (.-doc state) (inc n))))
(defn line-length [state n] (.-length (.line (.-doc state) (inc n))))

(defn range-text
  "Document text between LightTable positions `from-pos` and `to-pos`."
  [state from-pos to-pos]
  (.sliceString (.-doc state) (pos->offset state from-pos) (pos->offset state to-pos)))

;; ── selection (lives in EditorState — no DOM needed) ────────────────────────
(defn selection?
  "True if the primary selection is non-empty."
  [state]
  (not (.-empty (.. state -selection -main))))

(defn selection-bounds
  "Primary selection as {:from {:line :ch} :to {:line :ch}}."
  [state]
  (let [m (.. state -selection -main)]
    {:from (offset->pos state (.-from m))
     :to   (offset->pos state (.-to m))}))

(defn selected-text [state]
  (let [m (.. state -selection -main)]
    (.sliceString (.-doc state) (.-from m) (.-to m))))

(defn set-selection
  "Return a NEW state with the primary selection from `from-pos` to `to-pos`."
  [state from-pos to-pos]
  (-> state
      (.update #js {:selection #js {:anchor (pos->offset state from-pos)
                                    :head   (pos->offset state to-pos)}})
      (.-state)))

(defn replace
  "Position-based replace: NEW state with [from-pos, to-pos) replaced by `text`."
  [state from-pos to-pos text]
  (replace-range state (pos->offset state from-pos) (pos->offset state to-pos) text))
