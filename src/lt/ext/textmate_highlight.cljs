(ns lt.ext.textmate-highlight
  "Whole-document TextMate highlighting (ADR 0011, the syntax wall — render half).
  Tokenizes every line of a document with a grammar, THREADING the rule stack across
  lines (so multi-line constructs — block comments, strings — highlight correctly),
  and offsets each line's spans to absolute document positions. Output is neutral
  data ([{:from :to :class}]); the CM6 layer turns it into mark decorations. Pure +
  node-gated; lives on the adapter side (depends on lt.ext.textmate)."
  (:require [lt.ext.textmate :as tm]
            [clojure.string :as str]
            ["@codemirror/view" :as cm-view]
            ["@codemirror/state" :as cm-state]))

(def ^:private Decoration (.-Decoration cm-view))
(def ^:private EditorView (.-EditorView cm-view))
(def ^:private StateField (.-StateField cm-state))

(defn spans-for-text
  "Tokenize all lines of `text` with `grammar` → absolute highlight spans
  [{:from :to :class}] (document offsets), rule state threaded line→line."
  [grammar text]
  (let [lines (str/split text #"\n" -1)]          ; -1 keeps trailing empties
    (loop [ls lines, offset 0, stack nil, acc (transient [])]
      (if (empty? ls)
        (persistent! acc)
        (let [line (first ls)
              {:keys [tokens rule-stack]} (tm/tokenize-line grammar line stack)
              acc' (reduce (fn [a s] (conj! a {:from   (+ offset (:start s))
                                               :to     (+ offset (:end s))
                                               :class  (:class s)}))
                           acc (tm/line-spans tokens))]
          (recur (rest ls) (+ offset (count line) 1) rule-stack acc'))))))

(defn spans->decorations
  "Highlight spans [{:from :to :class}] → a CM6 mark DecorationSet (a RangeSet) the
  editor applies. Empty `:to == :from` spans are dropped (CM6 marks must be non-empty)."
  [spans]
  (let [ranges (->> spans
                    (filter #(< (:from %) (:to %)))
                    (sort-by :from)
                    (map (fn [s] (.range (.mark Decoration #js {:class (:class s)}) (:from s) (:to s))))
                    (into-array))]
    (.set Decoration ranges true)))

(defn decorations-for
  "Whole-document text + grammar → a CM6 mark DecorationSet."
  [grammar text]
  (spans->decorations (spans-for-text grammar text)))

(defn highlight-extension
  "A CM6 editor extension that syntax-highlights with `grammar`: a StateField holding
  the mark DecorationSet, recomputed on every doc change and provided to the editor
  via EditorView.decorations. (Whole-document retokenize per change — viewport/
  incremental is a perf follow-on.) The editor installs this via its extension seam;
  the adapter owns it, so the core never depends on lt.ext."
  [grammar]
  (.define StateField
           #js {:create  (fn [state] (decorations-for grammar (.toString (.-doc state))))
                :update  (fn [value tr]
                           (if (.-docChanged tr)
                             (decorations-for grammar (.toString (.. tr -state -doc)))
                             (.map value (.-changes tr))))
                :provide (fn [f] (.from (.-decorations EditorView) f))}))
