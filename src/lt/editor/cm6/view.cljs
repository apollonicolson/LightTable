(ns lt.editor.cm6.view
  "CodeMirror 6 EditorView — the DOM-bound half of the CM6 seam (M5 slice).

  `lt.editor.cm6` is the immutable state model (pure, node-tested). This namespace
  is the live VIEW: an EditorView mounts a state into a DOM element, owns the
  contenteditable surface, and applies edits as transactions (`dispatch`). The
  view's current value is always `(.-state view)`, so every read accessor in
  `lt.editor.cm6` works on a live view via `view-state`.

  Two write paths, deliberately distinct (the delegation layer, M5 slice 4,
  chooses per-op):
  - `dispatch!` applies a transaction spec — this is the edit path; it threads
    through the history extension, so undo/redo work.
  - `set-state!` replaces the whole state — the doc-swap path (CM5 swapDoc); it
    resets history, so it is NOT for ordinary edits.

  Mounts/dispatches/destroys under jsdom (verified), so it is node-testable here;
  pixel-measurement (coords, scrolling) still needs a real browser."
  (:require ["@codemirror/state" :as cm-state]
            ["@codemirror/view" :as cm-view]
            ["@codemirror/commands" :as cm-commands]
            [lt.editor.cm6 :as cm6]))

(def ^:private EditorView (.-EditorView cm-view))
(def ^:private cm-undo (.-undo cm-commands))
(def ^:private cm-redo (.-redo cm-commands))
(def ^:private isolate-history (.-isolateHistory cm-commands))

(defn create-view
  "Build an EditorView. `parent` (a DOM element) is optional — when nil the view
  is detached and its `.dom` can be inserted later (the editor object* returns it
  for the tab). `opts` may carry `:doc` (string, default \"\") or `:state` (a
  prebuilt EditorState; takes precedence). The state carries the standard
  extensions from `cm6/make-state` unless one is supplied."
  [parent {:keys [doc state]}]
  (let [config #js {:state (or state (cm6/make-state (or doc "")))}]
    (when parent (set! (.-parent config) parent))
    (EditorView. config)))

(defn view-state
  "The EditorView's current EditorState — the value every cm6 read accessor takes."
  [view]
  (.-state view))

(defn dispatch!
  "Apply a transaction `spec` (a #js map like {:changes … :selection …}) to the
  live view, threading through history. Returns the view."
  [view spec]
  (.dispatch view spec)
  view)

(defn set-state!
  "Replace the view's entire state with `state` (CM5 swapDoc semantics — resets
  history). Returns the view."
  [view state]
  (.setState view state)
  view)

(defn dom "The view's outer DOM element." [view] (.-dom view))

(defn focused? [view] (.-hasFocus view))
(defn focus!   [view] (.focus view) view)
(defn blur!    [view] (.. view -contentDOM (blur)) view)

(defn destroy!
  "Tear the view down and detach it from the DOM."
  [view]
  (.destroy view)
  view)

;; ── live-view writes (offset-based; dispatch transactions → history-preserving) ─
;; The pure ops in `lt.editor.cm6` are state→state values; these are their live
;; analogues for an EditorView — they dispatch, so they thread through history
;; (unlike set-state!, which would reset it). The CM6 backend (lt.editor.backend)
;; uses these; reads still go through `view-state` + the cm6 accessors.
(defn replace! [view from to text]
  (dispatch! view #js {:changes #js {:from from :to to :insert text}}))

(defn move-cursor! [view offset]
  (dispatch! view #js {:selection #js {:anchor offset}}))

(defn set-selection! [view anchor head]
  (dispatch! view #js {:selection #js {:anchor anchor :head head}}))

(defn select-all! [view]
  (dispatch! view #js {:selection #js {:anchor 0 :head (cm6/doc-length (view-state view))}}))

(defn set-val!
  "Replace the whole document and reset the cursor. Annotated as a history
  boundary (CM5 `make` pairs setValue with clearHistory) so a later edit's undo
  stops here rather than merging across — while PRESERVING the view's extensions/
  option compartments (unlike set-state!)."
  [view s]
  (dispatch! view #js {:changes #js {:from 0 :to (cm6/doc-length (view-state view)) :insert s}
                       :selection #js {:anchor 0}
                       :annotations (.of isolate-history "full")}))

(defn insert-at-cursor! [view text]
  (let [off (cm6/cursor-offset (view-state view))]
    (dispatch! view #js {:changes #js {:from off :insert text}
                         :selection #js {:anchor (+ off (count text))}})))

(defn replace-selection! [view text]
  (let [m (.. (view-state view) -selection -main)
        from (.-from m)]
    (dispatch! view #js {:changes #js {:from from :to (.-to m) :insert text}
                         :selection #js {:anchor (+ from (count text))}})))

(defn undo! "Undo on the live view (no-op if no history)." [view] (cm-undo view) view)
(defn redo! "Redo on the live view (no-op if nothing to redo)." [view] (cm-redo view) view)
