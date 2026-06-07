(ns lt.editor.cm6.decorations
  "CM6-native decorations that TRACK through edits — the replacement for CM5's
  marks, bookmarks, line widgets, and line handles (ADR 0009). This is the shared
  core that eval's inline results, watches, langs token-marks, and paredit all
  build on.

  CM5 attached identity to a `LineHandle`/`TextMarker` object and listened for its
  `change`/`delete` events. CM6 has no such objects: positions are offsets, and a
  `DecorationSet` (a RangeSet of decorations) is mapped through every edit's
  ChangeSet automatically. So identity becomes an `:id` stashed in a decoration's
  spec, and \"where is my mark now / was its text deleted\" becomes a RangeSet
  query (`tracked-range`) rather than an event subscription.

  Each consumer makes an independent LAYER (`make-layer`): a StateField + three
  StateEffects (add / remove-by-id / clear). Layers coexist — CM6 collects
  decorations from every field — so clearing find's highlight never touches eval's
  inline results. The RANGE-MAPPING (the load-bearing correctness — decorations
  move with edits, collapse when their text is deleted) is pure and node-tested
  here; widget DOM rendering needs a real view (Electron)."
  (:require ["@codemirror/state" :as cm-state]
            ["@codemirror/view" :as cm-view]))

(def ^:private StateField (.-StateField cm-state))
(def ^:private StateEffect (.-StateEffect cm-state))
(def ^:private Decoration (.-Decoration cm-view))
(def ^:private EditorView (.-EditorView cm-view))
(def ^:private WidgetType (.-WidgetType cm-view))

;; ── widget: a CM6 WidgetType wrapping a prebuilt DOM element ──────────────────
;; CLJS can't `extends` a JS abstract class via deftype, so subclass WidgetType by
;; hand: a ctor whose prototype chains to WidgetType.prototype. toDOM returns the
;; element as-is (LightTable builds it via singultus); eq is element identity, so
;; one logical result = one stable widget.
(def ^:private ElementWidget
  (let [ctor (fn [el] (this-as this (set! (.-el this) el) this))
        proto (js/Object.create (.-prototype WidgetType))]
    (set! (.-toDOM proto) (fn [] (this-as this (.-el this))))
    (set! (.-eq proto) (fn [other] (this-as this (identical? (.-el this) (.-el other)))))
    (set! (.-prototype ctor) proto)
    ctor))

(defn element-widget "A WidgetType rendering the prebuilt DOM element `el`." [el]
  (ElementWidget. el))

;; ── decoration constructors (each carries `:id` for tracking) ─────────────────
(defn- with-meta-spec [opts id extra]
  (let [spec (clj->js (or opts {}))]
    (set! (.-id spec) id)
    (doseq [[k v] extra] (aset spec k v))
    spec))

(defn mark
  "A mark decoration (range styling) tagged with tracking `id`. `opts` is the CM6
  MarkDecorationSpec (e.g. {:class \"result-mark\"})."
  ([id] (mark id nil))
  ([id opts] (.mark Decoration (with-meta-spec opts id nil))))

(defn widget
  "A point/inline widget decoration showing DOM `el`, tagged with `id`. `opts` is
  the CM6 WidgetDecorationSpec (e.g. {:side 1} — `:block true` makes it a line
  widget rendered above/below the line)."
  ([id el] (widget id el nil))
  ([id el opts] (.widget Decoration (with-meta-spec opts id [["widget" (element-widget el)]]))))

;; ── layers ────────────────────────────────────────────────────────────────────
;; A LAYER is an independent decoration field + its three effects. Each consumer
;; (find highlight, eval inline results, watches) owns its own layer, so clearing
;; one never touches another — multiple layers coexist (CM6 collects decorations
;; from every field via the EditorView.decorations facet). The deco CONSTRUCTORS
;; above are layer-independent; only the field/effects/queries are per-layer.

(defn make-layer
  "Create an independent decoration layer. Returns a map:
   :field        — a StateField to add to the editor's extensions (renders the set)
   :add          — (deco from [to]) → effect spec; to defaults to from (point)
   :remove-by-id — (id) → effect spec dropping the deco tagged id
   :clear        — () → effect spec dropping THIS layer's decorations only
   :ranges       — (state) → the live DecorationSet
   :tracked-range— (state id) → {:from :to} of deco id, or nil if gone (collapsed
                   range = its text was deleted; the line-handle delete signal)
   :count        — (state) → number of decorations in this layer"
  []
  (let [add-effect    (.define StateEffect)
        remove-effect (.define StateEffect)
        clear-effect  (.define StateEffect)
        apply-effect  (fn [set e]
                        (cond
                          (.is e add-effect)    (let [[deco from to] (.-value e)]
                                                  (.update set #js {:add #js [(.range deco from to)]
                                                                    :sort true}))
                          (.is e remove-effect) (.update set #js {:filter (fn [_ _ value]
                                                                            (not= (.-value e) (.. value -spec -id)))})
                          (.is e clear-effect)  (.-none Decoration)
                          :else set))
        field (.define StateField
                       #js {:create (fn [_] (.-none Decoration))
                            ;; map existing decorations through the edit FIRST, then
                            ;; apply this transaction's add/remove/clear effects.
                            :update (fn [set tr]
                                      (reduce apply-effect (.map set (.-changes tr)) (.-effects tr)))
                            :provide (fn [f] (.from (.-decorations EditorView) f))})
        ranges (fn [state] (.field state field))]
    {:field field
     :add (fn add ([deco from] (add deco from from))
                  ([deco from to] (.of add-effect [deco from to])))
     :remove-by-id (fn [id] (.of remove-effect id))
     :clear (fn [] (.of clear-effect nil))
     :ranges ranges
     :tracked-range (fn [state id]
                      (let [it (.iter (ranges state))]
                        (loop []
                          (when (.-value it)
                            (if (= id (.. it -value -spec -id))
                              {:from (.-from it) :to (.-to it)}
                              (do (.next it) (recur)))))))
     :count (fn [state] (.-size (ranges state)))}))
