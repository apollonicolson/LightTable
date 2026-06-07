(ns lt.editor.cm6.results
  "CM6 eval inline results — the replacement for CM5 bookmarks / line-widgets +
  per-line-handle change/delete events (ADR 0009, the eval consumer).

  CM5 attached each result to a TextMarker/LineHandle and MANUALLY relocated it on
  every edit (eval's ::move-mark) and cleared it on line delete. CM6 decorations
  map through edits automatically, so relocation is free — a result only needs
  removing when its line's text is deleted (its tracked range collapses to empty).
  That collapses eval's move/changed behaviors to no-ops on CM6.

  Results live in their OWN decoration layer (independent of find's highlight —
  see decorations/make-layer), so clearing one never disturbs the other. Widget
  DOM is the singultus-built result element from eval.cljs; rendering needs a live
  view (Electron-tested)."
  (:require [lt.editor.cm6.view :as view]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.decorations :as dec]))

(def layer
  "Eval results decoration layer. Its :field goes into every CM6 editor's
  extensions so result widgets render."
  (dec/make-layer))

(defn add!
  "Add result widget DOM `el` tagged `id` for 0-based `line`. `block?` renders it
  as a block below the line (underline result / exception); otherwise an inline
  widget at the line end (inline result). Returns `id`."
  [view id line el {:keys [block?]}]
  (let [state (view/view-state view)
        l (.line (.-doc state) (inc line))
        ;; inline → end of line; block → end of line with block:true renders below.
        pos (.-to l)
        deco (dec/widget id el (if block? {:block true :side 1} {:side 1}))]
    (view/dispatch! view #js {:effects ((:add layer) deco pos)})
    id))

(defn remove!
  "Remove the result widget tagged `id`."
  [view id]
  (view/dispatch! view #js {:effects ((:remove-by-id layer) id)}))

(defn line-of
  "Current 0-based line of widget `id`, or nil if it is gone (its text deleted)."
  [view id]
  (when-let [r ((:tracked-range layer) (view/view-state view) id)]
    (:line (cm6/offset->pos (view/view-state view) (:from r)))))

(defn present?
  "True if widget `id` is still attached (its line not deleted)."
  [view id]
  (boolean ((:tracked-range layer) (view/view-state view) id)))

(defn count-results [view]
  ((:count layer) (view/view-state view)))
