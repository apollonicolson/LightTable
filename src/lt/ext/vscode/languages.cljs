(ns lt.ext.vscode.languages
  "Phase 4 of the VSCode extension host (ADR 0011): the `vscode.languages` namespace
  — provider registry (completion/hover/definition), invocation, DiagnosticCollection
  — and the mappings from VSCode provider results to the CM6 renderer inputs (the
  reuse of ADR 0010 slices 1–4). Registry + invocation + mappings are node-loadable +
  tested; the live wiring (invoke on the active editor; diagnostics → cm6.diagnostics)
  is the editor-coupled bridge (phase 4b)."
  (:require [lt.ext.vscode.types :as t]
            [clojure.string :as str]))

(defonce ^:private providers (atom {:completion [] :hover [] :definition []
                                    :document-symbol [] :references []
                                    :formatting [] :signature []
                                    :rename [] :code-action []}))
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

(defn provide-document-symbols
  "DocumentSymbolProvider.provideDocumentSymbols(document, token) — document-level,
  no position. Returns the first matching provider's DocumentSymbol[]|SymbolInformation[]."
  [document]
  (let [calls (map (fn [e] (.resolve js/Promise ((aget (:provider e) "provideDocumentSymbols") document nil)))
                   (matching :document-symbol (.-languageId document)))]
    (.then (.all js/Promise (into-array calls))
           (fn [results] (first (remove nil? (array-seq results)))))))

(defn provide-references
  "ReferenceProvider.provideReferences(document, position, context, token). Flattens
  Location[] from all matching providers."
  [document position]
  (let [ctx   #js {:includeDeclaration true}
        calls (map (fn [e] (.resolve js/Promise ((aget (:provider e) "provideReferences") document position ctx nil)))
                   (matching :references (.-languageId document)))]
    (.then (.all js/Promise (into-array calls))
           (fn [results] (into-array (mapcat #(array-seq %) (remove nil? (array-seq results))))))))

(defn provide-formatting
  "DocumentFormattingEditProvider.provideDocumentFormattingEdits(doc, options, token)
  → the first matching provider's TextEdit[]."
  [document]
  (let [opts  #js {:tabSize 2 :insertSpaces true}
        calls (map (fn [e] (.resolve js/Promise ((aget (:provider e) "provideDocumentFormattingEdits") document opts nil)))
                   (matching :formatting (.-languageId document)))]
    (.then (.all js/Promise (into-array calls))
           (fn [results] (first (remove nil? (array-seq results)))))))

(defn provide-signature-help
  "SignatureHelpProvider.provideSignatureHelp(doc, position, token, context)."
  [document position]
  (let [calls (map (fn [e] (.resolve js/Promise ((aget (:provider e) "provideSignatureHelp") document position nil nil)))
                   (matching :signature (.-languageId document)))]
    (.then (.all js/Promise (into-array calls))
           (fn [results] (first (remove nil? (array-seq results)))))))

(defn provide-rename
  "RenameProvider.provideRenameEdits(doc, position, newName, token) → WorkspaceEdit."
  [document position new-name]
  (let [calls (map (fn [e] (.resolve js/Promise ((aget (:provider e) "provideRenameEdits") document position new-name nil)))
                   (matching :rename (.-languageId document)))]
    (.then (.all js/Promise (into-array calls))
           (fn [results] (first (remove nil? (array-seq results)))))))

(defn provide-code-actions
  "CodeActionProvider.provideCodeActions(doc, range, context, token) → (Command|CodeAction)[]."
  [document range]
  (let [ctx   #js {:diagnostics #js [] :only nil}
        calls (map (fn [e] (.resolve js/Promise ((aget (:provider e) "provideCodeActions") document range ctx nil)))
                   (matching :code-action (.-languageId document)))]
    (.then (.all js/Promise (into-array calls))
           (fn [results] (into-array (mapcat #(array-seq %) (remove nil? (array-seq results))))))))

;; ── Result → CM6 renderer mappings ───────────────────────────────────────────
(defn- label-of [it]
  (let [l (.-label it)] (if (string? l) l (.-label l))))

(defn- insert-text-of
  "The text a CompletionItem inserts: a string insertText, a SnippetString's
  `.value`, else the label."
  [it]
  (let [ins (.-insertText it)]
    (cond (string? ins)        ins
          (and ins (.-value ins)) (.-value ins)   ; SnippetString
          :else                  (label-of it))))

(defn completion-items->hints
  "VSCode CompletionItem[] → hint items ({.completion .text}) for the hint UI."
  [items]
  (into-array (map (fn [it] #js {:completion (insert-text-of it) :text (label-of it)})
                   (array-seq items))))

(defn completion-items->data
  "VSCode CompletionItem[] → SERIALIZABLE clj completion data ({:completion :text})
  for the membrane (the `->hints` variant emits #js for the in-renderer hint UI)."
  [items]
  (mapv (fn [it] {:completion (insert-text-of it) :text (label-of it)}) (array-seq items)))

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

(defn document-symbols->data
  "VSCode DocumentSymbol[] (hierarchical: .name .kind .range .children) |
  SymbolInformation[] (.name .kind .location) → serializable clj outline."
  [syms]
  (when syms
    (mapv (fn [s]
            (let [r        (or (.-range s) (some-> (.-location s) .-range))
                  children (.-children s)]
              (cond-> {:name (.-name s) :kind (.-kind s)
                       :line (some-> r .-start .-line)}
                (and children (pos? (.-length children)))
                (assoc :children (document-symbols->data children)))))
          (array-seq syms))))

(defn references->data
  "VSCode Location[] → serializable clj [{:uri :line :character}]."
  [locs]
  (mapv (fn [l] (let [r (.-range l) uri (.-uri l)]
                  {:uri       (if (string? uri) uri (.toString uri))
                   :line      (.. r -start -line)
                   :character (.. r -start -character)}))
        (array-seq locs)))

(defn text-edits->changes
  "VSCode TextEdit[] → CM6-shaped change specs [{:from :to :insert}] using the
  document's offsetAt (host-side — the host holds the mirror), so main can apply them
  directly. Sorted by :from DESCENDING so sequential application doesn't shift the
  offsets of not-yet-applied edits."
  [document edits]
  (when edits
    (->> (array-seq edits)
         (map (fn [e] (let [r (.-range e)]
                        {:from   (.offsetAt document (.-start r))
                         :to     (.offsetAt document (.-end r))
                         :insert (.-newText e)})))
         (sort-by :from >)
         vec)))

(defn- text-edit->range-data [e]
  (let [r (.-range e)]
    {:range {:start {:line (.. r -start -line) :character (.. r -start -character)}
             :end   {:line (.. r -end -line)   :character (.. r -end -character)}}
     :new-text (.-newText e)}))

(defn workspace-edit->data
  "VSCode WorkspaceEdit → serializable {:changes {uri-string [{:range :new-text}]}}.
  Range-based (multi-file; main resolves offsets per doc), reusing the edit shape."
  [we]
  (when we
    {:changes (into {} (for [[uri edits] ((.-_entries we))]
                         [uri (mapv text-edit->range-data (if (vector? edits) edits (array-seq edits)))]))}))

(defn- code-action-kind-str [k] (cond (nil? k) nil (string? k) k :else (.-value k)))

(defn code-actions->data
  "VSCode (Command | CodeAction)[] → serializable clj. A Command has a string
  `.command`; a CodeAction has `.title`/`.kind`/`.edit`/`.command`(obj)."
  [actions]
  (when actions
    (mapv (fn [a]
            (let [cmd (.-command a)]
              (cond-> {:title (.-title a)}
                (.-kind a)        (assoc :kind (code-action-kind-str (.-kind a)))
                (.-isPreferred a) (assoc :is-preferred true)
                (.-edit a)        (assoc :edit (workspace-edit->data (.-edit a)))
                cmd               (assoc :command (if (string? cmd) cmd (.-command cmd))))))
          (array-seq actions))))

(defn signature-help->data
  "VSCode SignatureHelp → serializable clj (label/documentation/parameters)."
  [sh]
  (when sh
    {:active-signature (.-activeSignature sh)
     :active-parameter (.-activeParameter sh)
     :signatures (mapv (fn [s]
                         {:label         (.-label s)
                          :documentation (let [d (.-documentation s)]
                                           (if (string? d) d (some-> d .-value)))
                          :parameters    (mapv (fn [p] {:label (.-label p)})
                                               (array-seq (or (.-parameters s) #js [])))})
                       (array-seq (or (.-signatures sh) #js [])))}))

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
       :registerDocumentSymbolProvider (fn [sel prov] (register :document-symbol sel prov))
       :registerReferenceProvider      (fn [sel prov] (register :references sel prov))
       :registerDocumentFormattingEditProvider (fn [sel prov] (register :formatting sel prov))
       :registerSignatureHelpProvider  (fn [sel prov & _meta] (register :signature sel prov))
       :registerRenameProvider         (fn [sel prov] (register :rename sel prov))
       :registerCodeActionsProvider    (fn [sel prov & _meta] (register :code-action sel prov))
       :createDiagnosticCollection     (fn [name] (create-diagnostic-collection name))})

(defn reset-languages! []
  (reset! providers {:completion [] :hover [] :definition []
                     :document-symbol [] :references []
                     :formatting [] :signature []
                     :rename [] :code-action []})
  (reset! diag-sink nil))
