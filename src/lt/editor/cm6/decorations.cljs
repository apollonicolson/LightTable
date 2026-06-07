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

  A single StateField holds the set; three StateEffects mutate it (add / remove-
  by-id / clear). The field is provided to the view as decorations, so widgets
  render live. The RANGE-MAPPING (the load-bearing correctness — decorations move
  with edits, collapse when their text is deleted) is pure and node-tested here;
  widget DOM rendering needs a real view (Electron)."
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

;; ── effects (the only way to mutate the set) ──────────────────────────────────
(def add-effect    (.define StateEffect))   ; value #js{:deco :from :to}
(def remove-effect (.define StateEffect))   ; value id
(def clear-effect  (.define StateEffect))   ; value nil

(defn add
  "Effect spec: add `deco` over [from, to) (to defaults to from for point widgets)."
  ([deco from] (add deco from from))
  ([deco from to] (.of add-effect [deco from to])))

(defn remove-by-id "Effect spec: remove the decoration tagged `id`." [id]
  (.of remove-effect id))

(defn clear "Effect spec: drop all decorations." []
  (.of clear-effect nil))

(defn- apply-effect [set e]
  (cond
    (.is e add-effect)    (let [[deco from to] (.-value e)]
                            (.update set #js {:add #js [(.range deco from to)]
                                              :sort true}))
    (.is e remove-effect) (.update set #js {:filter (fn [_ _ value]
                                                      (not= (.-value e) (.. value -spec -id)))})
    (.is e clear-effect)  (.-none Decoration)
    :else set))

(def deco-field
  "The StateField holding the live DecorationSet. Add it to a state's extensions
  (cm6/make-state's `extra`) to enable tracked decorations + their rendering."
  (.define StateField
           #js {:create (fn [_] (.-none Decoration))
                :update (fn [set tr]
                          ;; map existing decorations through the edit FIRST, then
                          ;; apply this transaction's add/remove/clear effects.
                          (reduce apply-effect (.map set (.-changes tr)) (.-effects tr)))
                :provide (fn [f] (.from (.-decorations EditorView) f))}))

;; ── queries (the line-handle replacement) ─────────────────────────────────────
(defn decoration-set "The current DecorationSet of `state`." [state]
  (.field state deco-field))

(defn tracked-range
  "Current {:from :to} of the decoration tagged `id`, or nil if it is no longer in
  the set. A collapsed range (from == to) means its text was deleted — the CM6
  signal that replaces CM5's line-handle `delete` event."
  [state id]
  (let [it (.iter (decoration-set state))]
    (loop []
      (when (.-value it)
        (if (= id (.. it -value -spec -id))
          {:from (.-from it) :to (.-to it)}
          (do (.next it) (recur)))))))

(defn decoration-count "Number of decorations currently tracked in `state`." [state]
  (.-size (decoration-set state)))
