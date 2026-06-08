(ns lt.ext.vscode.document
  "Phase 3 of the VSCode extension host (ADR 0011): the `TextDocument` value type
  every language provider receives, plus the document-sync crux — mapping a CM6
  `ChangeSet` to VSCode `TextDocumentContentChangeEvent[]` (the MirrorTextModel
  incremental shape). Pure functions of the text string + a passed-in changeset →
  node-loadable + tested; no cm6 require (the changeset is duck-typed)."
  (:require [lt.ext.vscode.types :as t]
            [clojure.string :as str]))

(defn- line-starts
  "Vector of each line's start offset in `text`."
  [text]
  (loop [offs [0] i 0]
    (let [nl (.indexOf text "\n" i)]
      (if (neg? nl) offs (recur (conj offs (inc nl)) (inc nl))))))

(defn- offset-at [starts pos]
  (let [line (max 0 (min (.-line pos) (dec (count starts))))]
    (+ (nth starts line) (.-character pos))))

(defn- pos-at [starts offset]
  (let [line (loop [i 0]
               (if (and (< (inc i) (count starts)) (<= (nth starts (inc i)) offset))
                 (recur (inc i)) i))]
    (t/->Position line (- offset (nth starts line)))))

(defn- line-text [text starts ln]
  (let [start (nth starts ln)
        end   (if (< (inc ln) (count starts)) (dec (nth starts (inc ln))) (count text))]
    (subs text start end)))

(defn- text-line [text starts ln]
  (let [s (line-text text starts ln)
        fnw (count (take-while #(or (= % \space) (= % \tab)) s))]
    #js {:lineNumber ln
         :text s
         :range (t/make-range (t/->Position ln 0) (t/->Position ln (count s)))
         :firstNonWhitespaceCharacterIndex fnw
         :isEmptyOrWhitespace (str/blank? s)}))

(defn make-text-document
  "Build a TextDocument from `{:uri :languageId :version :text}`."
  [{:keys [uri languageId version text]}]
  (let [text   (or text "")
        starts (line-starts text)]
    #js {:uri        uri
         :languageId (or languageId "plaintext")
         :version    (or version 1)
         :lineCount  (count starts)
         :getText    (fn [range]
                       (if range
                         (subs text (offset-at starts (.-start range)) (offset-at starts (.-end range)))
                         text))
         :offsetAt   (fn [pos] (offset-at starts pos))
         :positionAt (fn [o] (pos-at starts (max 0 (min o (count text)))))
         :lineAt     (fn [line-or-pos]
                       (text-line text starts (if (number? line-or-pos)
                                                line-or-pos
                                                (.-line line-or-pos))))}))

(defn content-changes
  "Map a CM6 `ChangeSet` to VSCode `TextDocumentContentChangeEvent[]` against
  `old-doc` (a TextDocument of the pre-change text). Ranges are in the OLD doc, the
  MirrorTextModel incremental shape: {range, rangeOffset, rangeLength, text}."
  [old-doc changeset]
  (let [events #js []]
    (.iterChanges changeset
                  (fn [from-a to-a _from-b _to-b inserted]
                    (.push events
                           #js {:range       (t/make-range (.positionAt old-doc from-a)
                                                           (.positionAt old-doc to-a))
                                :rangeOffset from-a
                                :rangeLength (- to-a from-a)
                                :text        (.toString inserted)})))
    events))
