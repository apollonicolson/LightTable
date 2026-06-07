(ns lt.lsp.completion
  "Pure mapping: LSP completion results → LightTable hint items (ADR 0010 slice 3).
  The auto-complete UI consumes hint items as JS objects with `.completion` (text
  to insert) and `.text` (display); this maps LSP CompletionItems to that shape.

  Node-loadable + node-tested — no editor deps. The wiring that feeds these into
  the live hint UI (a `:hints+` source) is the editor-coupled half (slice 3b).")

;; LSP CompletionItemKind → a short label (for display / future iconography).
(def ^:private kind->label
  {1 "text" 2 "method" 3 "function" 4 "constructor" 5 "field" 6 "variable"
   7 "class" 8 "interface" 9 "module" 10 "property" 11 "unit" 12 "value"
   13 "enum" 14 "keyword" 15 "snippet" 16 "color" 17 "file" 18 "reference"
   19 "folder" 20 "enum-member" 21 "constant" 22 "struct" 23 "event"
   24 "operator" 25 "type-parameter"})

(defn kind-label [kind] (get kind->label kind))

(defn- insert-text
  "The text to insert for an item: insertText, else the textEdit's newText, else
  the label. (Snippet expansion is deferred — newText is taken literally.)"
  [it]
  (or (:insertText it)
      (get-in it [:textEdit :newText])
      (:label it)))

(defn- item->hint [it]
  #js {:completion (insert-text it)
       :text       (:label it)
       :kind       (kind-label (:kind it))
       :detail     (:detail it)})

(defn lsp-items->hints
  "Map an LSP completion response — a CompletionList `{:items [...]}` or a bare
  CompletionItem vector — to a JS array of hint items, sorted by `:sortText`
  (falling back to `:label`). Returns an empty array for nil/empty."
  [result]
  (let [items  (cond (map? result) (:items result)
                     (sequential? result) result
                     :else [])
        sorted (sort-by (fn [it] [(or (:sortText it) (:label it) "")
                                  (or (:label it) "")])
                        items)]
    (into-array (map item->hint sorted))))
