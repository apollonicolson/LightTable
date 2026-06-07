(ns lt.editor.cm6.tooltip
  "A single programmatic CM6 tooltip (ADR 0010 slice 4) — used to render LSP hover
  info. A StateField holds the current Tooltip (or nil), provided to the
  `showTooltip` facet; a StateEffect sets/clears it. Same shape as the decoration
  layers: add (:field) to the editor extensions, drive via show!/clear!."
  (:require ["@codemirror/state" :as cm-state]
            ["@codemirror/view" :as cm-view]))

(def ^:private StateField  (.-StateField cm-state))
(def ^:private StateEffect (.-StateEffect cm-state))
(def ^:private showTooltip (.-showTooltip cm-view))

(def ^:private set-effect (.define StateEffect))

(defn- ->tooltip [pos text]
  #js {:pos pos
       :above true
       :create (fn [_view]
                 (let [dom (.createElement js/document "div")]
                   (set! (.-className dom) "cm6-lsp-tooltip")
                   (set! (.-textContent dom) text)
                   #js {:dom dom}))})

(def field
  (.define StateField
           #js {:create (fn [_] nil)
                :update (fn [value tr]
                          (reduce (fn [v e] (if (.is e set-effect) (.-value e) v))
                                  value
                                  (.-effects tr)))
                :provide (fn [f] (.from showTooltip f))}))

(defn show!
  "Show a tooltip with `text` at character offset `pos` in `view`."
  [view pos text]
  (.dispatch view #js {:effects (.of set-effect (->tooltip pos text))}))

(defn clear! [view]
  (.dispatch view #js {:effects (.of set-effect nil)}))

(defn showing? [state] (some? (.field state field)))
