(ns lt.editor.cm6.options
  "CM6's unified reconfiguration mechanism for the lt.objs.editor option behaviors
  (M5). CM5 has a flat setOption(name, value); CM6 expresses each option as an
  EXTENSION and makes it runtime-reconfigurable via a Compartment. Each editor
  owns one compartment per option, seeded with the option's default, and
  set-options dispatches `compartment.reconfigure(ext)` effects — one mechanism,
  not a setOption grab-bag.

  Covered so far: lineNumbers, lineWrapping, readOnly, tabSize (the keys the
  :object.instant behaviors set). foldGutter/theme/etc. follow with @codemirror/
  language and the theme system."
  (:require ["@codemirror/state" :as cm-state]
            ["@codemirror/view" :as cm-view]))

(def ^:private Compartment (.-Compartment cm-state))
(def ^:private EditorState (.-EditorState cm-state))
(def ^:private EditorView (.-EditorView cm-view))
(def ^:private line-numbers (.-lineNumbers cm-view))

(def ^:private option-keys [:lineNumbers :lineWrapping :readOnly :tabSize])

(defn make-compartments
  "A fresh map {option-key -> Compartment} for one editor."
  []
  (into {} (map (fn [k] [k (Compartment.)]) option-keys)))

(defn- ext-for
  "The CM6 extension for option `k` = `v`. An off/absent option is the empty
  extension #js []."
  [k v]
  (case k
    :lineNumbers  (if v (line-numbers) #js [])
    :lineWrapping (if v (.-lineWrapping EditorView) #js [])
    :readOnly     (.of (.-readOnly EditorState) (boolean v))
    :tabSize      (.of (.-tabSize EditorState) (or v 4))
    #js []))

(defn initial-extensions
  "Seed each compartment with its default-off extension — these go into the
  initial state so the option is reconfigurable later."
  [compartments]
  (->> option-keys
       (map (fn [k] (.of (compartments k) (ext-for k (when (= k :tabSize) 4)))))
       (into-array)))

(defn reconfigure!
  "Apply LightTable option map `m` to a live CM6 `view` by reconfiguring the
  matching compartments. Unknown keys are ignored. Returns the view."
  [view compartments m]
  (let [effects (->> m
                     (keep (fn [[k v]]
                             (when (contains? compartments k)
                               (.reconfigure (compartments k) (ext-for k v)))))
                     (into-array))]
    (when (pos? (.-length effects))
      (.dispatch view #js {:effects effects}))
    view))
