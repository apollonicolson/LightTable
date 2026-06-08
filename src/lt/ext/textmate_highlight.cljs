(ns lt.ext.textmate-highlight
  "Whole-document TextMate highlighting (ADR 0011, the syntax wall — render half).
  Tokenizes every line of a document with a grammar, THREADING the rule stack across
  lines (so multi-line constructs — block comments, strings — highlight correctly),
  and offsets each line's spans to absolute document positions. Output is neutral
  data ([{:from :to :class}]); the CM6 layer turns it into mark decorations. Pure +
  node-gated; lives on the adapter side (depends on lt.ext.textmate)."
  (:require [lt.ext.textmate :as tm]
            [clojure.string :as str]))

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
