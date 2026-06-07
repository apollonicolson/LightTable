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
            [lt.editor.cm6 :as cm6]))

(def ^:private EditorView (.-EditorView cm-view))

(defn create-view
  "Mount an EditorView into DOM element `parent`. `opts` may carry `:doc` (string,
  default \"\") or `:state` (a prebuilt EditorState; takes precedence). The state
  carries the standard extensions from `cm6/make-state` unless one is supplied."
  [parent {:keys [doc state]}]
  (EditorView. #js {:state (or state (cm6/make-state (or doc "")))
                    :parent parent}))

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
