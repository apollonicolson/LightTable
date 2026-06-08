(ns lt.ext.vscode.types
  "Phase 2 of the VSCode extension host (ADR 0011): the core `vscode` value types
  extensions construct + receive — Position, Range, Uri, Disposable, EventEmitter.
  Plain immutable values, no editor/Node deps → node-loadable + tested. Faithful to
  the public contract (vscode.d.ts): JS-constructible (`new vscode.Position(...)`),
  property access (`pos.line`), instanceof, and the common methods. Overloads/long
  tail added target-driven.")

;; ── Position ──────────────────────────────────────────────────────────────
(deftype Position [line character]
  Object
  (isEqual         [_ o] (and (= line (.-line o)) (= character (.-character o))))
  (isBefore        [_ o] (or (< line (.-line o))
                             (and (= line (.-line o)) (< character (.-character o)))))
  (isBeforeOrEqual [this o] (or (.isBefore this o) (.isEqual this o)))
  (isAfter         [this o] (not (.isBeforeOrEqual this o)))
  (isAfterOrEqual  [this o] (not (.isBefore this o)))
  (translate       [_ dl dc] (Position. (+ line (or dl 0)) (+ character (or dc 0))))
  (with            [_ l c] (Position. (if (some? l) l line) (if (some? c) c character)))
  (compareTo       [this o] (cond (.isBefore this o) -1 (.isEqual this o) 0 :else 1)))

;; ── Range (deftype VRange to avoid shadowing cljs.core/Range; exposed as
;;    vscode.Range = make-range, which supports both ctor signatures) ───────────
(deftype VRange [start end]
  Object
  (isEmpty      [_] (.isEqual start end))
  (isSingleLine [_] (= (.-line start) (.-line end)))
  (contains     [_ x] (let [s (if (.-start x) (.-start x) x)
                            e (if (.-end x) (.-end x) x)]
                        (and (not (.isBefore s start)) (not (.isAfter e end)))))
  (isEqual      [_ o] (and (.isEqual start (.-start o)) (.isEqual end (.-end o))))
  (with         [_ s e] (VRange. (if (some? s) s start) (if (some? e) e end))))

(defn make-range
  "vscode.Range constructor — (start,end) Positions or (sl,sc,el,ec) numbers."
  ([start end] (->VRange start end))
  ([sl sc el ec] (->VRange (->Position sl sc) (->Position el ec))))
;; alias the prototype so `x instanceof vscode.Range` holds for both signatures.
(set! (.-prototype make-range) (.-prototype VRange))

;; ── Uri (constructed via static file/parse; fsPath precomputed) ──────────────
(deftype Uri [scheme authority path query fragment fsPath]
  Object
  (toString [_] (str scheme "://" authority path
                     (when (seq query) (str "?" query))
                     (when (seq fragment) (str "#" fragment))))
  (with [_ change] (Uri. (or (.-scheme change) scheme)
                         (or (.-authority change) authority)
                         (or (.-path change) path)
                         (or (.-query change) query)
                         (or (.-fragment change) fragment)
                         (or (.-path change) fsPath))))

(defn- uri-file [p] (->Uri "file" "" p "" "" p))

(defn- uri-parse [s]
  (let [m (re-matches #"^([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/?#]*)([^?#]*)(?:\?([^#]*))?(?:#(.*))?$" s)]
    (if m
      (let [[_ scheme authority path query fragment] m]
        (->Uri scheme authority path (or query "") (or fragment "") path))
      (->Uri "file" "" s "" "" s))))

(set! (.-file Uri) (fn [p] (uri-file p)))
(set! (.-parse Uri) (fn [s] (uri-parse s)))

;; ── Disposable ──────────────────────────────────────────────────────────────
(deftype Disposable [^:mutable callOnDispose]
  Object
  (dispose [_] (when callOnDispose (callOnDispose) (set! callOnDispose nil))))

(defn disposable [f] (->Disposable f))

(set! (.-from Disposable)
      (fn [& ds] (->Disposable (fn [] (doseq [d ds] (when (and d (.-dispose d)) (.dispose d)))))))

;; ── EventEmitter (`.event` subscribes → Disposable; `.fire`; `.dispose`) ──────
(defn event-emitter []
  (let [listeners (atom #{})]
    #js {:event   (fn [listener]
                    (swap! listeners conj listener)
                    (->Disposable (fn [] (swap! listeners disj listener))))
         :fire    (fn [data] (doseq [l @listeners] (l data)))
         :dispose (fn [] (reset! listeners #{}))}))

;; ── Language-feature value types (mutable data holders extensions construct) ──
;; CompletionItemKind / DiagnosticSeverity enums (note: severities differ from LSP —
;; VSCode Error=0, LSP Error=1; the languages layer converts).
(def CompletionItemKind
  #js {:Text 0 :Method 1 :Function 2 :Constructor 3 :Field 4 :Variable 5 :Class 6
       :Interface 7 :Module 8 :Property 9 :Unit 10 :Value 11 :Enum 12 :Keyword 13
       :Snippet 14 :Color 15 :File 16 :Reference 17 :Folder 18 :EnumMember 19
       :Constant 20 :Struct 21 :Event 22 :Operator 23 :TypeParameter 24})

(def DiagnosticSeverity #js {:Error 0 :Warning 1 :Information 2 :Hint 3})

(defn completion-item [label kind]
  #js {:label label :kind kind :insertText nil :detail nil
       :documentation nil :sortText nil :filterText nil})

(defn diagnostic [range message severity]
  #js {:range range :message message :severity (or severity 0)
       :source nil :code nil})

(defn hover [contents range]
  #js {:contents contents :range range})

(defn location [uri range]
  #js {:uri uri :range range})

(defn markdown-string [value]
  (let [obj #js {:value (or value "") :isTrusted false}]
    (set! (.-appendText obj) (fn [s] (set! (.-value obj) (str (.-value obj) s)) obj))
    (set! (.-appendMarkdown obj) (fn [s] (set! (.-value obj) (str (.-value obj) s)) obj))
    obj))

(defn snippet-string
  "vscode.SnippetString — a snippet template. We carry `.value`; the append*
  builders mutate it and return `this` (tabstop/placeholder expansion is a known
  gap — value is taken literally for now)."
  [value]
  (let [obj #js {:value (or value "")}
        app (fn [s] (set! (.-value obj) (str (.-value obj) (or s ""))) obj)]
    (set! (.-appendText obj) app)
    (set! (.-appendTabstop obj) (fn [& _] obj))
    (set! (.-appendPlaceholder obj) (fn [& _] obj))
    (set! (.-appendChoice obj) (fn [& _] obj))
    (set! (.-appendVariable obj) (fn [& _] obj))
    obj))

(defn- uri->key [uri] (if (string? uri) uri (.toString uri)))

(defn work-space-edit
  "vscode.WorkspaceEdit — a mutable builder accumulating per-uri TextEdits. Used with
  `new vscode.WorkspaceEdit()` (constructor: the returned object wins). `_entries`
  exposes {uri-string → [#js{:range :newText}]} for the language-layer mapper."
  []
  (let [changes (atom {})
        push    (fn [uri edit] (swap! changes update (uri->key uri) (fnil conj []) edit))]
    #js {:replace  (fn [uri range new-text] (push uri #js {:range range :newText new-text}))
         :insert   (fn [uri pos text] (push uri #js {:range #js {:start pos :end pos} :newText text}))
         :delete   (fn [uri range] (push uri #js {:range range :newText ""}))
         :set      (fn [uri edits] (swap! changes assoc (uri->key uri) (vec (array-seq edits))))
         :get      (fn [uri] (clj->js (get @changes (uri->key uri) [])))
         :has      (fn [uri] (contains? @changes (uri->key uri)))
         :_entries (fn [] @changes)}))

(defn code-action
  "vscode.CodeAction — `new vscode.CodeAction(title, kind)`; `.edit`/`.command`/
  `.isPreferred` are set by the extension afterwards."
  [title kind]
  #js {:title title :kind kind :edit nil :command nil :diagnostics nil :isPreferred false})

;; CodeActionKind — the standard kind hierarchy; each carries a dotted `.value`.
(def CodeActionKind
  #js {:Empty            #js {:value ""}
       :QuickFix         #js {:value "quickfix"}
       :Refactor         #js {:value "refactor"}
       :RefactorExtract  #js {:value "refactor.extract"}
       :RefactorInline   #js {:value "refactor.inline"}
       :RefactorRewrite  #js {:value "refactor.rewrite"}
       :Source           #js {:value "source"}
       :SourceOrganizeImports #js {:value "source.organizeImports"}})
