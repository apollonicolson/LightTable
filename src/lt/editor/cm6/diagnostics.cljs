(ns lt.editor.cm6.diagnostics
  "Render LSP diagnostics as CM6 decorations — the first editor-coupled protocol
  renderer (ADR 0010). The defport LSP client (editor-agnostic) delivers
  `textDocument/publishDiagnostics`; this maps each diagnostic's LSP range to a
  CM6 mark in an independent decoration layer (squiggle classes by severity).

  Position adapter: LSP positions are 0-based {:line :character}; LightTable is
  0-based {:line :ch}; `cm6/pos->offset` bridges to CM6 offsets. (UTF-16-vs-
  codepoint columns are a known edge — correct for ASCII/BMP; deferred.)

  The pure mapping (LSP diagnostics → decoration ranges over an EditorState) is
  node-tested; the live `set-diagnostics!` path needs a view (Electron)."
  (:require [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.view :as view]
            [lt.editor.cm6.decorations :as dec]))

(def layer
  "Diagnostics decoration layer; add (:field layer) to the editor's extensions."
  (dec/make-layer))

;; LSP DiagnosticSeverity: 1 Error, 2 Warning, 3 Information, 4 Hint.
(defn severity-class [severity]
  (case severity
    1 "cm-diag-error"
    2 "cm-diag-warning"
    3 "cm-diag-info"
    4 "cm-diag-hint"
    "cm-diag-error"))

(defn- lsp-pos->pos [p] {:line (:line p) :ch (:character p)})

(defn diagnostic-range
  "Offset range {:from :to} of an LSP diagnostic in `state`."
  [state diagnostic]
  (let [r (:range diagnostic)]
    {:from (cm6/pos->offset state (lsp-pos->pos (:start r)))
     :to   (cm6/pos->offset state (lsp-pos->pos (:end r)))}))

(defn- diagnostics->effects [state diagnostics]
  (cons
   ((:clear layer))
   (map-indexed
    (fn [i d]
      (let [{:keys [from to]} (diagnostic-range state d)]
        ;; marks can't be empty — a zero-width diagnostic squiggles one char.
        ((:add layer) (dec/mark [::diag i] {:class (severity-class (:severity d))})
         from (max (inc from) to))))
    diagnostics)))

(defn apply-diagnostics
  "Return a NEW state with `diagnostics` rendered into the layer (replacing any
  prior diagnostics). Pure — for node tests and headless use."
  [state diagnostics]
  (-> state
      (.update #js {:effects (into-array (diagnostics->effects state diagnostics))})
      (.-state)))

(defn set-diagnostics!
  "Render `diagnostics` into the live `view`'s diagnostics layer. Returns the count."
  [view diagnostics]
  (.dispatch view #js {:effects (into-array (diagnostics->effects (view/view-state view) diagnostics))})
  (count diagnostics))

(defn clear! [view]
  (.dispatch view #js {:effects ((:clear layer))}))

(defn count-diagnostics [state] ((:count layer) state))
