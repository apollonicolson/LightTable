(ns lt.editor.cm6.find
  "CM6 live find/replace glue — the view-level half of the search consumer
  (ADR 0009). The pure match computation lives in lt.editor.cm6.search (node-
  tested); this drives a live EditorView: select+scroll to a match, and highlight
  ALL matches via an independent decoration layer (so find's highlight never
  collides with eval's inline results — see lt.editor.cm6.decorations/make-layer).

  Find state (the query) is held by the consumer (find.cljs) and passed in each
  call — there is no search StateField, matching the decomplected design.

  `layer` is module-level: its `:field` goes into every CM6 editor's extensions
  (each editor state gets its own DecorationSet). View ops here are Electron-
  tested (real layout for scrollIntoView)."
  (:require [lt.editor.cm6.view :as view]
            [lt.editor.cm6.search :as search]
            [lt.editor.cm6.decorations :as dec]))

(def layer
  "The find-highlight decoration layer. Add (:field layer) to the CM6 editor's
  extensions so matches render."
  (dec/make-layer))

(def match-class "cm6-search-match")

(defn- select+scroll! [view from to]
  (.dispatch view #js {:selection #js {:anchor from :head to} :scrollIntoView true}))

(defn highlight!
  "Replace this layer's decorations with a mark over every match of `query`."
  [view query opts]
  (let [ranges (search/match-ranges (view/view-state view) query opts)
        effects (into-array
                 (cons ((:clear layer))
                       (map-indexed
                        (fn [i r] ((:add layer) (dec/mark [::match i] {:class match-class})
                                   (:from r) (:to r)))
                        ranges)))]
    (view/dispatch! view #js {:effects effects})
    (count ranges)))

(defn search!
  "Begin/refresh a search: highlight all matches and move to the first one at or
  after `from-offset` (wrapping). Returns the match {:from :to} or nil."
  [view query opts from-offset]
  (highlight! view query opts)
  (when-let [m (search/next-match (view/view-state view) query from-offset opts)]
    (select+scroll! view (:from m) (:to m))
    m))

(defn next!
  "Move to the next match after the current selection (wrapping). Returns it/nil."
  [view query opts]
  (let [cur (.. (view/view-state view) -selection -main -to)]
    (when-let [m (search/next-match (view/view-state view) query cur opts)]
      (select+scroll! view (:from m) (:to m))
      m)))

(defn prev!
  "Move to the previous match before the current selection (wrapping). Returns it."
  [view query opts]
  (let [cur (.. (view/view-state view) -selection -main -from)]
    (when-let [m (search/prev-match (view/view-state view) query cur opts)]
      (select+scroll! view (:from m) (:to m))
      m)))

(defn clear! "Drop all find highlights." [view]
  (view/dispatch! view #js {:effects ((:clear layer))}))

(defn replace-current!
  "If the current selection matches `query`, replace it with `replacement` and
  return true; else false (caller then advances to a match first)."
  [view query replacement opts]
  (let [m (.. (view/view-state view) -selection -main)]
    (if (.-empty m)
      false
      (let [sel (.sliceString (.-doc (view/view-state view)) (.-from m) (.-to m))
            hit (first (search/match-ranges (view/view-state view) query
                                            (assoc opts :from (.-from m) :to (.-to m))))]
        (if (and hit (= (:from hit) (.-from m)) (= (:to hit) (.-to m)) (seq sel))
          (do (.dispatch view #js {:changes #js {:from (.-from m) :to (.-to m) :insert replacement}})
              true)
          false)))))

(defn replace-all!
  "Replace every match of `query` with `replacement` in one transaction (one undo).
  Returns the number replaced."
  [view query replacement opts]
  (let [ranges (search/match-ranges (view/view-state view) query opts)]
    (when (seq ranges)
      (.dispatch view #js {:changes (clj->js (map (fn [r] {:from (:from r) :to (:to r) :insert replacement})
                                                  ranges))}))
    (count ranges)))
