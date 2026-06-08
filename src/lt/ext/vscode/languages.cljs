(ns lt.ext.vscode.languages
  "Phase 4 of the VSCode extension host (ADR 0011): the `vscode.languages` namespace
  — provider registry (completion/hover/definition), invocation, DiagnosticCollection
  — and the mappings from VSCode provider results to the CM6 renderer inputs (the
  reuse of ADR 0010 slices 1–4). Registry + invocation + mappings are node-loadable +
  tested; the live wiring (invoke on the active editor; diagnostics → cm6.diagnostics)
  is the editor-coupled bridge (phase 4b)."
  (:require [lt.ext.vscode.types :as t]
            [clojure.string :as str]))

(defonce ^:private providers (atom {:completion [] :hover [] :definition []}))
(defonce ^:private diag-sink (atom nil))   ; (fn [uri-string lsp-diagnostics-js])

(defn set-diagnostic-sink! [f] (reset! diag-sink f))

;; ── DocumentSelector matching (string | {language} | array) ──────────────────
(defn- selector-matches? [selector language-id]
  (cond
    (nil? selector)    true
    (string? selector) (or (= selector "*") (= selector language-id))
    (array? selector)  (boolean (some #(selector-matches? % language-id) (array-seq selector)))
    :else              (let [l (.-language selector)] (or (nil? l) (= l "*") (= l language-id)))))

(defn- register [kind selector provider]
  (let [entry {:selector selector :provider provider}]
    (swap! providers update kind conj entry)
    (t/disposable (fn [] (swap! providers update kind #(vec (remove #{entry} %)))))))

(defn- matching [kind language-id]
  (filter #(selector-matches? (:selector %) language-id) (get @providers kind)))

(defn- call-all
  "Invoke `method` on every provider of `kind` matching the document's language with
  (document, position, nil, nil); returns a Promise of the non-nil results."
  [kind document position method]
  (let [calls (map (fn [e] (.resolve js/Promise
                                     ((aget (:provider e) method) document position nil nil)))
                   (matching kind (.-languageId document)))]
    (.then (.all js/Promise (into-array calls))
           (fn [results] (remove nil? (array-seq results))))))

(defn provide-completions [document position]
  (.then (call-all :completion document position "provideCompletionItems")
         (fn [results]
           (into-array (mapcat (fn [r] (if (.-items r) (array-seq (.-items r)) (array-seq r)))
                               results)))))

(defn provide-hover [document position]
  (.then (call-all :hover document position "provideHover") first))

(defn provide-definition [document position]
  (.then (call-all :definition document position "provideDefinition") first))

;; ── Result → CM6 renderer mappings ───────────────────────────────────────────
(defn completion-items->hints
  "VSCode CompletionItem[] → hint items ({.completion .text}) for the hint UI."
  [items]
  (into-array (map (fn [it]
                     (let [label (.-label it)
                           lab   (if (string? label) label (.-label label))]
                       #js {:completion (or (.-insertText it) lab) :text lab}))
                   (array-seq items))))

(defn hover->text
  "VSCode Hover → plain text (contents = string | MarkdownString | array)."
  [hov]
  (when hov
    (let [c (.-contents hov)
          one (fn [x] (cond (string? x) x (and x (.-value x)) (.-value x) :else ""))]
      (cond (nil? c)    nil
            (string? c) c
            (array? c)  (str/join "\n\n" (remove str/blank? (map one (array-seq c))))
            :else       (one c)))))

(defn definition->location
  "VSCode Location | Location[] → {:uri :line :character}."
  [result]
  (let [loc (if (array? result) (first (array-seq result)) result)]
    (when loc
      (let [uri (.-uri loc) r (.-range loc)]
        {:uri       (if (string? uri) uri (.toString uri))
         :line      (.. r -start -line)
         :character (.. r -start -character)}))))

;; ── DiagnosticCollection (→ cm6.diagnostics via the sink) ────────────────────
(defn- uri-key [uri] (if (string? uri) uri (.toString uri)))

(defn- vscode-diag->lsp
  "VSCode Diagnostic → the LSP-shaped map cm6.diagnostics consumes (severity is
  +1: VSCode Error=0 → LSP Error=1)."
  [d]
  (let [r (.-range d)]
    {:range    {:start {:line (.. r -start -line) :character (.. r -start -character)}
                :end   {:line (.. r -end -line)   :character (.. r -end -character)}}
     :severity (inc (or (.-severity d) 0))
     :message  (.-message d)}))

(defn- emit-diagnostics!
  "Notify the sink with clj LSP-shaped diagnostic maps (consumed directly by
  editor/set-diagnostics → cm6.diagnostics)."
  [k diags]
  (when-let [f @diag-sink]
    (f k (mapv vscode-diag->lsp (array-seq diags)))))

(defn create-diagnostic-collection [name]
  (let [store (atom {})]
    #js {:name    name
         :set     (fn [uri diags]
                    (let [k (uri-key uri)]
                      (swap! store assoc k (vec (array-seq diags)))
                      (emit-diagnostics! k diags)))
         :delete  (fn [uri] (let [k (uri-key uri)] (swap! store dissoc k) (emit-diagnostics! k #js [])))
         :clear   (fn [] (doseq [k (keys @store)] (emit-diagnostics! k #js [])) (reset! store {}))
         :get     (fn [uri] (clj->js (get @store (uri-key uri))))
         :dispose (fn [] (reset! store {}))
         :_count  (fn [] (count @store))}))

(defn ns-object []
  #js {:registerCompletionItemProvider (fn [sel prov & _tc] (register :completion sel prov))
       :registerHoverProvider          (fn [sel prov] (register :hover sel prov))
       :registerDefinitionProvider     (fn [sel prov] (register :definition sel prov))
       :createDiagnosticCollection     (fn [name] (create-diagnostic-collection name))})

(defn reset-languages! []
  (reset! providers {:completion [] :hover [] :definition []})
  (reset! diag-sink nil))
