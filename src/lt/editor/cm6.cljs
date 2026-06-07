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
  ;; `replace` matches the lt.objs.editor seam name (shadows cljs.core/replace).
  (:refer-clojure :exclude [replace])
  (:require ["@codemirror/state" :as cm-state]
            ["@codemirror/commands" :as cm-commands]))

(def ^:private EditorState (.-EditorState cm-state))
(def ^:private StateField (.-StateField cm-state))
(def ^:private cm-history (.-history cm-commands))
(def ^:private cm-undo (.-undo cm-commands))
(def ^:private cm-redo (.-redo cm-commands))

;; ── generation (CM5 changeGeneration/isClean parity) ─────────────────────────
;; CM6 has no built-in generation counter. This StateField increments on every
;; doc-changing transaction — exactly what CM5's changeGeneration returns and
;; isClean(gen) compares against.
(def ^:private generation-field
  (.define StateField
           #js {:create (fn [_] 0)
                :update (fn [value tr] (if (.-docChanged tr) (inc value) value))}))

(defn make-state
  "Create an immutable CM6 EditorState seeded with document string `s`. The state
  carries the standard editor extensions (undo/redo history + a generation
  counter) so the backend matches CM5's document semantics."
  [s]
  (.create EditorState #js {:doc s
                            :extensions #js [(cm-history) generation-field]}))

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

;; ── cursor (the empty-selection case; CM6 has no separate cursor concept) ─────
;; In CM6 the cursor IS the primary selection's head. An empty selection
;; (anchor == head) is a bare cursor. lt.objs.editor's `cursor`/`->cursor` map
;; onto this.
(defn cursor-offset
  "CM6 offset of the primary selection head — the cursor position."
  [state]
  (.. state -selection -main -head))

(defn cursor
  "Cursor as LightTable {:line :ch} (mirrors lt.objs.editor/->cursor)."
  [state]
  (offset->pos state (cursor-offset state)))

(defn move-cursor
  "Return a NEW state with the cursor at `pos` and an empty selection."
  [state pos]
  (-> state
      (.update #js {:selection #js {:anchor (pos->offset state pos)}})
      (.-state)))

(defn select-all
  "Return a NEW state selecting the whole document."
  [state]
  (-> state
      (.update #js {:selection #js {:anchor 0 :head (doc-length state)}})
      (.-state)))

;; ── document value (whole-buffer ops) ────────────────────────────────────────
(defn set-val
  "Return a NEW state with the whole document replaced by `s`; cursor reset to 0
  (matches CM5 setValue, which loses the cursor)."
  [state s]
  (-> state
      (.update #js {:changes #js {:from 0 :to (doc-length state) :insert s}
                    :selection #js {:anchor 0}})
      (.-state)))

(defn set-val-keep-cursor
  "Like [[set-val]] but the cursor offset is preserved (clamped to the new length)
  — matches CM5 set-val-and-keep-cursor."
  [state s]
  (let [off (min (cursor-offset state) (count s))]
    (-> state
        (.update #js {:changes #js {:from 0 :to (doc-length state) :insert s}
                      :selection #js {:anchor off}})
        (.-state))))

(defn insert-at-cursor
  "Return a NEW state with `text` inserted at the cursor; cursor moves to the end
  of the inserted text."
  [state text]
  (let [off (cursor-offset state)]
    (-> state
        (.update #js {:changes #js {:from off :insert text}
                      :selection #js {:anchor (+ off (count text))}})
        (.-state))))

(defn replace-selection
  "Return a NEW state with the primary selection replaced by `text`; cursor moves
  to the end of the inserted text (matches CM5 replaceSelection \"end\")."
  [state text]
  (let [m (.. state -selection -main)
        from (.-from m)]
    (-> state
        (.update #js {:changes #js {:from from :to (.-to m) :insert text}
                      :selection #js {:anchor (+ from (count text))}})
        (.-state))))

;; ── line accessors / edits ───────────────────────────────────────────────────
;; lt.objs.editor lines are 0-based: first-line is 0, last-line is line-count-1.
(defn first-line [_state] 0)
(defn last-line  [state] (dec (line-count state)))

(defn set-line
  "Return a NEW state with line `n` (0-based) content replaced by `text`."
  [state n text]
  (let [l (.line (.-doc state) (inc n))]
    (replace-range state (.-from l) (.-to l) text)))

(defn get-char
  "Characters from the cursor offset by `dir` (CM5 get-char, offset-based): for
  dir>0 the `dir` chars after the cursor, for dir<0 the chars before it."
  [state dir]
  (let [off (cursor-offset state)
        doc (.-doc state)]
    (if (pos? dir)
      (.sliceString doc off (min (doc-length state) (+ off dir)))
      (.sliceString doc (max 0 (+ off dir)) off))))

;; ── history (undo / redo / generation / dirty) ───────────────────────────────
;; CM6 undo/redo are StateCommands — `(cmd {:state :dispatch})` — not state→state
;; fns. Headless, we hand them a target whose `dispatch` captures the resulting
;; transaction's state. A command that can't run (empty history) returns false
;; and never dispatches, so the original state passes through unchanged.
(defn- run-command [state cmd]
  (let [result (atom state)]
    (cmd #js {:state state
              :dispatch (fn [tr] (reset! result (.-state tr)))})
    @result))

(defn undo "Return a NEW state with the last history event undone (no-op if none)."
  [state] (run-command state cm-undo))

(defn redo "Return a NEW state with the last undone event redone (no-op if none)."
  [state] (run-command state cm-redo))

(defn ->generation
  "Integer that increases with each document change — CM5 changeGeneration."
  [state]
  (.field state generation-field))

(defn dirty?
  "True if the document changed since generation `gen` — CM5 (not (isClean gen))."
  [state gen]
  (not= gen (->generation state)))
