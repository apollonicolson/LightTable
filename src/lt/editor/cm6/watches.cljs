(ns lt.editor.cm6.watches
  "CM6 watch highlights — the replacement for CM5's `.markText` watched ranges +
  their TextMarker identity/`.find`/`.clear`/`hide` event (ADR 0009, the watches
  consumer). A watch is a `mark` decoration in an independent layer; its position
  is read via tracked-range (auto-mapped through edits), and it is removed by id.
  Watch metadata (custom expr, etc.) lives in lt.plugins.watches' own :watches map,
  not on the decoration, so this layer only carries the highlight + position."
  (:require [lt.editor.cm6.view :as view]
            [lt.editor.cm6.decorations :as dec]))

(def layer
  "Watch-highlight decoration layer. Add (:field layer) to the CM6 editor's
  extensions so watched ranges render."
  (dec/make-layer))

(defn add!
  "Mark offset range [from,to) as a watch tagged `id`. Returns `id`."
  [view id from to]
  (view/dispatch! view #js {:effects ((:add layer) (dec/mark id {:class "watched"}) from to)})
  id)

(defn remove! [view id]
  (view/dispatch! view #js {:effects ((:remove-by-id layer) id)}))

(defn bounds
  "Current {:from :to} offset range of watch `id`, or nil if gone (collapsed/removed)."
  [view id]
  ((:tracked-range layer) (view/view-state view) id))
