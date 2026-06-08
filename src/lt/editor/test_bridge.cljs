(ns lt.editor.test-bridge
  "A guarded test seam for the CM6 parity suite (M5, ADR 0008).

  The characterization parity suite must drive `lt.objs.editor`'s PUBLIC API (the
  backend-agnostic seam) and assert observable behavior — so the same suite runs
  unchanged against the CM5 backend (current truth) and, later, the CM6 backend.
  CLJS namespaces aren't on `window` under shadow's module scoping, so this ns
  exposes the seam on `window.__lt_test` for Playwright's `page.evaluate`.

  OFF by default. `install!` is a no-op unless `process.env.LT_TEST_BRIDGE` is
  set — it never ships behavior into a normal run. Every fn operates on the
  active editor (`pool/last-active`) and speaks JS-friendly values."
  (:require [lt.object :as object]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            [lt.objs.document :as document]
            [lt.editor.cm6.find :as cm6-find]
            [lt.editor.cm6.results :as cm6-results]
            [lt.editor.cm6.view :as cm6-view]
            [lt.plugins.auto-complete :as ac]
            [lt.lsp.connector :as lsp-conn]
            [lt.objs.command :as cmd]
            [lt.ext.host :as ext-host]
            [lt.ext.vscode.api :as ext-api]
            [lt.ext.vscode.command-bridge :as ext-cmd-bridge]
            [lt.ext.vscode.workspace :as ext-ws]
            [lt.ext.vscode.window :as ext-window]
            [lt.ext.vscode.languages :as ext-langs]
            [lt.ext.vscode.document :as ext-doc]
            [lt.ext.vscode.types :as ext-types]
            [lt.objs.tabs :as tabs])
  (:require-macros [lt.macros :refer [behavior]]))

(defn- ed [] (pool/last-active))

(defonce ^:private ext-active (atom nil))
(defonce ^:private ext-uri->editor (atom {}))

;; Live doc-sync: an editor's CM6 :change → vscode.workspace onDidChangeTextDocument
;; (ADR 0011 phase 3b). Attached to an editor by wsTrackActive with its ::ws-uri.
(behavior ::ws-doc-sync
          :triggers #{:change}
          :reaction (fn [this update]
                      (when-let [uri (::ws-uri @this)]
                        (ext-ws/notify-change! uri (.-changes update) (editor/->val this)))))

;; Proves the CM6 updateListener actually drives LightTable behaviors: counts
;; :change raises on the editor object (attached in openCm6).
(behavior ::count-changes
          :triggers #{:change}
          :reaction (fn [this & _]
                      (object/update! this [::change-count] (fnil inc 0))))

(defn- js-pos [p] (js->clj p :keywordize-keys true))

(defn- bridge []
  #js {:active   (fn [] (boolean (ed)))
       ;; Open a CM6-backed editor in a real tab and make it active — so the SAME
       ;; parity assertions run against a LIVE CM6 editor (the ADR 0008 swap check).
       :openCm6  (fn [content]
                   (let [e (pool/create {:backend :cm6 :content (or content "")})]
                     (object/add-behavior! e ::count-changes)
                     (tabs/add! e)
                     (tabs/active! e)
                     (boolean e)))
       ;; Open a CM6 editor backed by a DOC (not inline :content) — exercises the
       ;; doc-model seed path that the flip relies on for file editors.
       :openCm6Doc (fn [content]
                     (let [d (document/create {:content (or content "") :mime "clojure"})
                           e (pool/create {:backend :cm6 :doc d})]
                       (object/add-behavior! e ::count-changes)
                       (tabs/add! e)
                       (tabs/active! e)
                       (boolean e)))
       ;; Open a DEFAULT editor (no :backend) — after the flip this must be CM6.
       :openDefault (fn [content]
                      (let [e (pool/create {:content (or content "")})]
                        (tabs/add! e)
                        (tabs/active! e)
                        (boolean e)))
       :backendKind (fn [] (when-let [e (ed)] (name (or (:backend-kind @e) :cm5))))
       :focus    (fn [] (when-let [e (ed)] (editor/focus e)) nil)
       :range    (fn [from to] (when-let [e (ed)] (editor/range e (js-pos from) (js-pos to))))
       :indentSelection (fn [dir] (when-let [e (ed)] (editor/indent-selection e dir)) nil)
       :centerCursor (fn [] (when-let [e (ed)] (editor/center-cursor e)) nil)
       :foldCode (fn [] (when-let [e (ed)] (editor/fold-code e)) nil)
       :changeCount (fn [] (when-let [e (ed)] (::change-count @e 0)))
       :val      (fn [] (when-let [e (ed)] (editor/->val e)))
       :setVal   (fn [v] (when-let [e (ed)] (editor/set-val e v)) nil)
       :setOptions (fn [opts] (when-let [e (ed)] (editor/set-options e (js->clj opts :keywordize-keys true))) nil)
       :setMode  (fn [m] (when-let [e (ed)] (editor/set-mode e m)) nil)
       :execCommand (fn [c] (when-let [e (ed)] (editor/cmd e (keyword c))) nil)
       :cursor   (fn [] (when-let [e (ed)] (clj->js (editor/->cursor e))))
       :moveCursor (fn [pos] (when-let [e (ed)] (editor/move-cursor e (js-pos pos))) nil)
       :lineCount (fn [] (when-let [e (ed)] (editor/line-count e)))
       :line     (fn [n] (when-let [e (ed)] (editor/line e n)))
       :firstLine (fn [] (when-let [e (ed)] (editor/first-line e)))
       :lastLine (fn [] (when-let [e (ed)] (editor/last-line e)))
       :replace  (fn [from to v] (when-let [e (ed)] (editor/replace e (js-pos from) (js-pos to) v)) nil)
       :setSelection (fn [from to] (when-let [e (ed)] (editor/set-selection e (js-pos from) (js-pos to))) nil)
       :selectionText (fn [] (when-let [e (ed)] (editor/selection e)))
       :selectionBounds (fn [] (when-let [e (ed)] (clj->js (editor/selection-bounds e))))
       :somethingSelected (fn [] (when-let [e (ed)] (editor/selection? e)))
       :replaceSelection (fn [v] (when-let [e (ed)] (editor/replace-selection e v)) nil)
       :insertAtCursor (fn [v] (when-let [e (ed)] (editor/insert-at-cursor e v)) nil)
       :getChar  (fn [dir] (when-let [e (ed)] (editor/get-char e dir)))
       :undo     (fn [] (when-let [e (ed)] (editor/undo e)) nil)
       :redo     (fn [] (when-let [e (ed)] (editor/redo e)) nil)
       ;; comment seam (ADR 0009) — CM6 path uses the live selection, so from/to
       ;; are nil; pool/do-commenting passes the real ones in production.
       :toggleComment (fn [] (when-let [e (ed)] (editor/toggle-comment e nil nil nil)) nil)
       :lineComment   (fn [] (when-let [e (ed)] (editor/line-comment e nil nil nil)) nil)
       ;; search seam (ADR 0009). opts is a JS map e.g. {reverse:false}.
       :search       (fn [q opts] (when-let [e (ed)] (clj->js (editor/search e q (js->clj opts :keywordize-keys true)))))
       :findNext     (fn [q opts] (when-let [e (ed)] (clj->js (editor/find-next e q (js->clj opts :keywordize-keys true)))))
       :findPrev     (fn [q opts] (when-let [e (ed)] (clj->js (editor/find-prev e q (js->clj opts :keywordize-keys true)))))
       :clearSearch  (fn [] (when-let [e (ed)] (editor/clear-search e)) nil)
       :replaceSearch (fn [q r opts all?] (when-let [e (ed)] (editor/replace-search e q r (js->clj opts :keywordize-keys true) all?)))
       :searchMatchCount (fn [] (when-let [e (ed)]
                                  ((:count cm6-find/layer) (cm6-view/view-state (editor/->cm-ed e)))))
       ;; eval result-widget seam (ADR 0009): add a result widget at a line and
       ;; track it by id (the CM6 decoration that replaces CM5 bookmarks/widgets).
       :addResult (fn [id line text block?]
                    (when-let [e (ed)]
                      (let [el (.createElement js/document "div")]
                        (set! (.-className el) "cm6-eval-result")
                        (set! (.-textContent el) text)
                        (editor/add-result-widget e line el {:id (keyword id) :block? (boolean block?)}))
                      nil))
       :resultPresent (fn [id] (when-let [e (ed)] (boolean (editor/result-widget-present? e (keyword id)))))
       :resultLine    (fn [id] (when-let [e (ed)]
                                 (cm6-results/line-of (editor/->cm-ed e) (keyword id))))
       :resultCount   (fn [] (when-let [e (ed)]
                               (cm6-results/count-results (editor/->cm-ed e))))
       :removeResult  (fn [id block?] (when-let [e (ed)] (editor/remove-result-widget e (keyword id) (boolean block?))) nil)
       ;; LSP diagnostics seam (ADR 0010): push LSP diagnostics onto the active
       ;; editor + read back the rendered count (the live cm6.diagnostics layer).
       :setDiagnostics  (fn [diags] (when-let [e (ed)]
                                      (editor/set-diagnostics e (js->clj diags :keywordize-keys true))) nil)
       :diagnosticCount (fn [] (when-let [e (ed)] (editor/diagnostic-count e)))
       :clearDiagnostics (fn [] (when-let [e (ed)] (editor/clear-diagnostics e)) nil)
       ;; LSP connector seam (ADR 0010 slice 2d): configure a server (argv) and
       ;; open the active editor as a doc — diagnostics then arrive FROM the
       ;; server and render via the connector (no manual setDiagnostics).
       :lspOpenActive (fn [argv uri]
                        (when-let [e (ed)]
                          (lsp-conn/configure! (js->clj argv))
                          (lsp-conn/open! e uri "clojure" (editor/->val e)))
                        nil)
       :lspReset      (fn [] (lsp-conn/reset-all!) nil)
       ;; VSCode extension host (ADR 0011 phase 2b): load an extension from `dir`,
       ;; injecting the vscode shim + bridging its commands into lt.objs.command.
       :extLoad       (fn [dir]
                        (ext-host/install-vscode! (ext-api/make-vscode))
                        (ext-cmd-bridge/install!)
                        ;; phase 4b: route DiagnosticCollection sets to the live editor
                        (ext-langs/set-diagnostic-sink!
                         (fn [uri diags]
                           (when-let [e (get @ext-uri->editor uri)]
                             (editor/set-diagnostics e diags))))
                        (let [active (ext-host/activate! (ext-host/read-manifest dir))]
                          (reset! ext-active active)
                          (boolean active)))
       ;; phase 4b: invoke language completion providers for the doc at `uri`, map to
       ;; hints, cache them on the editor (the ::lsp-hints :hints+ source renders them)
       :extProvideCompletion
       (fn [uri line ch language-id]
         (js/Promise.
          (fn [resolve _reject]
            (if-let [e (get @ext-uri->editor uri)]
              (let [d   (ext-doc/make-text-document {:uri uri :languageId (or language-id "clojure")
                                                     :text (editor/->val e)})
                    pos (ext-types/->Position line ch)]
                (.then (ext-langs/provide-completions d pos)
                       (fn [items]
                         (object/merge! e {:lsp/completions (ext-langs/completion-items->hints items)})
                         (resolve (.-length items)))))
              (resolve 0)))))
       ;; run a command through the LIVE LightTable command system (proves the
       ;; extension's vscode command became a real lt.objs.command), return result.
       :extExec       (fn [id & args] (apply cmd/exec! (keyword id) args))
       :extDeactivate (fn [] (when-let [a @ext-active] (ext-host/deactivate! a)) nil)
       :extApiField   (fn [k] (when-let [a @ext-active] (aget (:api a) k)))
       :extApiCall    (fn [k & args] (when-let [a @ext-active]
                                       (apply (aget (:api a) k) args)))
       ;; phase 3b: window/workspace test access + live doc tracking
       :extReset      (fn [] (ext-window/reset-window!) (ext-ws/reset-workspace!)
                        (ext-langs/reset-languages!) (reset! ext-uri->editor {})
                        (reset! ext-active nil) nil)
       :wsSetConfig   (fn [m] (ext-ws/set-config! (js->clj m)) nil)
       :windowMessages (fn [] (clj->js @ext-window/message-log))
       :wsTrackActive (fn [uri]
                        (when-let [e (ed)]
                          (ext-ws/open-document! {:uri uri :languageId "clojure"
                                                  :text (editor/->val e)})
                          (object/merge! e {::ws-uri uri})
                          (swap! ext-uri->editor assoc uri e)
                          (object/add-behavior! e ::ws-doc-sync))
                        nil)
       ;; request LSP completion at (line,character) for the active editor; resolves
       ;; (a promise) with the completion strings, and caches them as :lsp/completions.
       :lspComplete   (fn [uri line character]
                        (js/Promise.
                         (fn [resolve _reject]
                           (if-let [e (ed)]
                             (lsp-conn/complete! e uri line character
                                                 (fn [hints]
                                                   (resolve (.map hints (fn [h] (.-completion h))))))
                             (resolve #js [])))))
       ;; raise :hints+ on the active editor and return the merged completion
       ;; strings — proves the ::lsp-hints source is wired into the real hint flow.
       :editorHints   (fn [] (when-let [e (ed)]
                               (clj->js (map #(.-completion %)
                                             (object/raise-reduce e :hints+ [])))))
       ;; request LSP hover at (line,character) for the active editor; resolves
       ;; (a promise) with the hover text and renders the tooltip.
       :lspHover      (fn [uri line character]
                        (js/Promise.
                         (fn [resolve _reject]
                           (if-let [e (ed)]
                             (lsp-conn/hover! e uri line character (fn [text] (resolve text)))
                             (resolve nil)))))
       ;; request go-to-definition; resolves (a promise) with the location
       ;; {uri,line,character} the server returned (mapped). No navigation.
       :lspDefinition (fn [uri line character]
                        (js/Promise.
                         (fn [resolve _reject]
                           (lsp-conn/definition! uri line character (fn [loc] (resolve (clj->js loc)))))))
       ;; drive the full eval manager path (::inline-results etc.) on the active editor
       :evalResult    (fn [text line] (when-let [e (ed)] (object/raise e :editor.result text {:line line} {:type :inline})) nil)
       :evalException (fn [ex line] (when-let [e (ed)] (object/raise e :editor.exception ex {:line line})) nil)
       ;; auto-complete: prove get-pattern/get-token work on CM6 (no inner-mode crash)
       ;; and that the hint popup opens without throwing.
       :hintTokenAt (fn [line ch] (when-let [e (ed)] (clj->js (ac/get-token e {:line line :ch ch}))))
       :hintPatternOk (fn [] (when-let [e (ed)] (boolean (ac/get-pattern e))))
       :seedHints   (fn [words] (when-let [e (ed)]
                                  (object/merge! e {(keyword "lt.plugins.auto-complete" "hints")
                                                    (clj->js (mapv (fn [w] {:completion w}) words))})) nil)
       :showHint    (fn [] (when-let [e (ed)] (object/raise e :hint {:force? true})) nil)
       :hintActive  (fn [] (boolean (:active @ac/hinter)))
       ;; watches: ensure the :watchable tag (watch behaviors bind to it), then drive
       ;; the watch/unwatch commands. watchCount reads the editor's :watches map.
       :watch          (fn [] (when-let [e (ed)] (object/add-tags e [:watchable]) (object/raise e :watch!)) nil)
       :unwatchAtCursor (fn [] (when-let [e (ed)] (object/raise e :unwatch!)) nil)
       :watchCount     (fn [] (when-let [e (ed)] (count (:watches @e))))})

(defn install!
  "Expose the editor seam on window.__lt_test when LT_TEST_BRIDGE is set. No-op
  otherwise. Safe to call once at the end of renderer load."
  []
  (when (and (exists? js/process)
             (.. js/process -env -LT_TEST_BRIDGE))
    (set! (.-__lt_test js/window) (bridge))))
