(ns lt.objs.editor
  "Provide fns and behaviors for interfacing with a CodeMirror editor
  object. Also manage defining and loading [CodeMirror](http://codemirror.net/doc/manual.html).

  Editor objects are frequently used as arguments for functions, but often only the internal
  CodeMirror object is actually used. Where the following documentation referers to the editor,
  it is informally referring to the editor's CodeMirror object.

  Commonly encountered argument names:

  * `e` - Editor
  * `v` - Value
  * `m` - Map
  * `cm` - CodeMirror object
  * `opts` - Options
  * `ev` - Event Handler
  * `pos` - Position: depending on the context, either a Javascript object
            (e.g., `{\"line\": 0, \"ch\": 0}`) or cljs map (e.g., `{:line 0 :ch 0}`)."
  (:refer-clojure :exclude [val replace range])
  (:require [singultus.core :as crate]
            [lt.objs.context :as ctx-obj]
            [lt.editor.backend :as be]
            [lt.editor.cm6.view :as cm6-view]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.options :as cm6-options]
            [lt.editor.cm6.modes :as cm6-modes]
            [lt.editor.cm6.commands :as cm6-commands]
            [lt.editor.cm6.comment :as cm6-comment]
            [lt.editor.cm6.find :as cm6-find]
            [lt.editor.cm6.results :as cm6-results]
            [lt.editor.cm6.watches :as cm6-watches]
            [lt.editor.cm6.diagnostics :as cm6-diagnostics]
            [lt.editor.cm6.fold :as cm6-fold]
            [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.command :as cmd]
            [lt.objs.menu :as menu]
            [lt.util.events :as ev]
            [lt.util.dom :as dom]
            [lt.util.load :as load]
            [lt.objs.platform :as platform])
  (:use [lt.util.dom :only [remove-class add-class]]
        [lt.object :only [object* behavior*]])
  (:require-macros [lt.macros :refer [behavior]]))

(defn ->cm-ed
  "Return editor `e`'s CodeMirror object."
  [e]
  (if (satisfies? IDeref e)
    (:ed @e)
    e))

;; cm6? predicate removed — CM6 is the only backend, so it was always true (the
;; dual-backend discriminator). :backend-kind stays as a label / future-backend tag.

(defn ->elem
  "Return the editor's outer DOM element (the CM6 view's .dom)."
  [e]
  (cm6-view/dom (->cm-ed e)))

(defn backend
  "The Cm6Backend for editor `e` (ADR 0009). An editor object carries an explicit
  `:backend`; a bare view (some behaviors pass `(:ed @obj)`) is wrapped on the fly."
  [e]
  (if (and (satisfies? IDeref e) (:backend @e))
    (:backend @e)
    (be/cm6-backend (->cm-ed e))))

(defn exec-command
  "Run a named editor command, returning whether it was HANDLED (the CM6
  StateCommand's boolean). The seam behind pool.cljs's command bindings (ADR 0009)."
  [e command & _args]
  (cm6-commands/run (->cm-ed e) command))

(defn cmd
  "Run a named editor command on `e` (cursor motion / edit). Returns `e`."
  [e command]
  (exec-command e command)
  e)

(defn set-val
  "Set content value `v` of editor `e`'s CodeMirror object. Cursor position is lost. Returns `e`."
  [e v]
  (be/-set-val (backend e) v)
  e)

(defn set-val-and-keep-cursor
  "Same as [[set-val]] but current cursor position is kept."
  [e v]
  (let [b (backend e)
        cursor (be/-cursor b nil)]
    (be/-set-val b v)
    (be/-move-cursor b cursor)))

(defn set-options
  "Reconfigure editor `e`'s CM6 option compartments from map `m` (unknown options
  are ignored by reconfigure!). A bare view with no :cm6-compartments is skipped."
  [e m]
  (when (and (satisfies? IDeref e) (:cm6-compartments @e))
    (cm6-options/reconfigure! (->cm-ed e) (:cm6-compartments @e) m))
  e)

;; clear-history (.clearHistory) + get/set-history removed with CM5 (no callers).

;;*********************************************************
;; commands
;;*********************************************************

;;*********************************************************
;; Creating
;;*********************************************************

;; CM5 creation (headless/make), the .on/.off CM5 event helpers, and
;; wrap-object-events were removed with CM5 — object* builds a CM6 view and wires
;; events via cm6-view/update-listener + contentDOM focus/blur (ADR 0009).

;;*********************************************************
;; Params
;;*********************************************************

(defn ->val
  "Return editor `e`'s buffer content."
  [e]
  (be/-value (backend e)))

;; ->token/->token-type (getTokenAt) + ->coords (cursorCoords) removed with CM5
;; (no callers). CM6 token info would come from syntaxTree / coordsAtPos if needed.

(defn- +class
  "Add class `klass` to editor `e`. Returns `e`."
  [e klass]
  (add-class (->elem e) (name klass))
  e)

(defn- -class
  "Remove class `klass` from editor `e`. Returns `e`."
  [e klass]
  (remove-class (->elem e) (name klass))
  e)

(defn ->cursor
  "Cursor position of editor `e` as edn {:line :ch}."
  [e & [side]]
  (be/-cursor (backend e) side))

(defn pos->index
  "Character offset of position `pos` ({:line :ch}) in editor `e`'s doc (CM6)."
  [e pos]
  (cm6/pos->offset (cm6-view/view-state (->cm-ed e)) pos))

;; cursor (getCursor) / find-marks (findMarksAt) / bookmark (setBookmark) / mark
;; (markText) removed with CM5 (no callers). CM6 watch/result marks go through the
;; cm6.watches / cm6.results decoration seams instead.

(defn option
  "Value for option `o`. CM6 has no flat getOption; the few options consumers read
  (indent/tab) come back as sensible defaults so indent logic works; else nil."
  [_e o]
  (case (keyword o)
    :indentUnit 2
    :indentWithTabs false
    :tabSize 4
    nil))

(defn selections-count
  "Number of cursors/selection ranges in editor `e` (multi-cursor count)."
  [e]
  (.. (cm6-view/view-state (->cm-ed e)) -selection -ranges -length))

(defn set-mode
  "Set the language mode for editor `e` (reconfigures the CM6 language compartment)."
  [e m]
  (cm6-modes/set-mode! (->cm-ed e) (:cm6-language @e) m)
  e)

;; ->mode (getMode) removed with CM5 (no callers).

(defn focus
  "Return focus of editor.

  See [focus](http://codemirror.net/doc/manual.html#focus)."
  [e]
  (.focus (->cm-ed e))
  e)

(defn input-field
  "Return input field element of editor.

  See [getInputField](http://codemirror.net/doc/manual.html#getInputField)."
  [e]
  (.getInputField e))

(defn blur
  "Blurs input field `e`. Returns `e`."
  [e]
  (.blur (input-field e))
  e)

(defn refresh
  "Refreshes editor. Returns `e`.

  See [refresh](http://codemirror.net/doc/manual.html#refresh)."
  [e]
  ;; CM6 auto-measures and has no .refresh; feature-check so this is safe whether
  ;; `e` is an editor object, a raw CM5 instance, or a raw CM6 EditorView (some
  ;; behaviors pass (:ed @obj) directly).
  (let [cm (->cm-ed e)]
    (when (.-refresh cm) (.refresh cm)))
  e)

(defn on-move
  "Add function `func` to trigger when `onCursorActivity` event fires.
  `func` should take two arguments, `ed` and `delta`. Returns `e`.

  See [cursorActivity](http://codemirror.net/doc/manual.html#event_cursorActivity)"
  [e func]
  (.on e "onCursorActivity"
       (fn [ed delta]
         (func ed delta)))
  e)

(defn on-change
  "Add function `func` to trigger when `onChange` event fires.
  `func` should take two arguments, `ed` and `delta`. Returns `e`.

  See [change](http://codemirror.net/doc/manual.html#event_change)"
  [e func]
  (.on e "onChange"
       (fn [ed delta]
         (func ed delta)))
  e)

(defn on-update
  "Add function `func` to trigger when `onUpdate` event fires.
  `func` should take two arguments, `ed` and `delta`. Returns `e`.

  See [update](http://codemirror.net/doc/manual.html#event_update)"
  [e func]
  (.on e "onUpdate"
       (fn [ed delta]
         (func ed delta)))
  e)

(defn on-scroll
  "Add function `func` to trigger when `onScroll` event fires.
  `func` should take two arguments, `ed`. Returns `e`.

  See [scroll](http://codemirror.net/doc/manual.html#event_scroll)"
  [e func]
  (.on e "onScroll"
       (fn [ed]
         (func ed)))

  e)

(defn replace
  "Replace text starting at position `from` for editor with text `v`. If provided, replace will stop at position `to`.

  See [replaceRange](http://codemirror.net/doc/manual.html#replaceRange)."
  ([e from v]
   (be/-replace (backend e) from from v))
  ([e from to v]
   (be/-replace (backend e) from to v)))

(defn range
  "Returns text between positions `from` and `to` ({:line :ch})."
  [e from to]
  (cm6/range-text (cm6-view/view-state (->cm-ed e)) from to))

(defn line-count
  "Returns the number of lines in the editor.

  See [lineCount](http://codemirror.net/doc/manual.html#lineCount)."
  [e]
  (be/-line-count (backend e)))

(defn insert-at-cursor
  "Insert into editor `ed` text `s` at cursor's position. Returns `ed`."
  [ed s]
  (be/-insert-at-cursor (backend ed) s)
  ed)

(defn move-cursor
  "Moves editor `ed`'s cursor to position `pos`. If `pos` is nil then default position of line 0, ch 0 is used.

  See [setCursor](http://codemirror.net/doc/manual.html#setCursor)."
  [ed pos]
  (be/-move-cursor (backend ed) pos))

(defn scroll-to
  "Scroll editor to pixel position `x`,`y` (either may be nil)."
  [ed x y]
  (cm6-view/scroll-to! (->cm-ed ed) x y))

(defn center-cursor
  "Scrolls editor `ed` so the cursor is vertically centered."
  [ed]
  (cm6-view/center-on-offset! (->cm-ed ed) (cm6/cursor-offset (cm6-view/view-state (->cm-ed ed)))))

(defn selection?
  "True if text is selected in editor.

  See [somethingSelected](http://codemirror.net/doc/manual.html#somethingSelected)."
  [e]
  (be/-selection? (backend e)))

(defn selection-bounds
  "When text is selected, returns position `{:from x :to y}` where `x` and `y` are the cursor's start and end values."
  [e]
  (be/-selection-bounds (backend e)))

(defn selection
  "Returns currently selected text in editor.

  See [getSelection](http://codemirror.net/doc/manual.html#getSelection)."
  [e]
  (be/-selection (backend e)))

(defn set-selection
  "Sets editor's selection to `start` and `end` positions.

  See [setSelection](http://codemirror.net/doc/manual.html#setSelection)."
  [e start end]
  (be/-set-selection (backend e) start end))

;; set-extending (setExtending) removed with CM5 (no callers).

(defn replace-selection
  "Replace selection with `neue` for editor `e`.

  See [replaceSelection](http://codemirror.net/doc/manual.html#replaceSelection)."
  [e neue & [after]]
  (be/-replace-selection (backend e) neue after))

(defn undo
  "Undo one edit for editor `e`, if any exist.

  See [undo](http://codemirror.net/doc/manual.html#undo)."
  [e]
  (be/-undo (backend e)))

(defn redo
  "Redo one edit for editor `e`, if any exist.

  See [redo](http://codemirror.net/doc/manual.html#redo)."
  [e]
  (be/-redo (backend e)))

(defn copy
  "Copies currently selected text from editor."
  [e]
  (platform/copy (selection e)))

(defn cut
  "Cut currently selected text from editor."
  [e]
  (copy e)
  (replace-selection e ""))

(defn paste
  "Paste into editor's current cursor position "
  [e]
  (replace-selection e (platform/paste)))

;; char-coords (charCoords) removed with CM5 (no callers). CM6 screen coords come
;; from coordsAtPos (see position-hint).

(defn operation
  "Returns `e` rather than the return value of your function `func`.

  See [operation](http://codemirror.net/doc/manual.html#operation)."
  [e func]
  ;; CM6 batches via transactions — no operation wrapper; just run the fn.
  (func)
  e)

(defn on-click
  "Add function `func` to trigger when `:mousedown` fires.

  Returns editor `e`."
  [e func]
  (let [elem (->elem e)]
    (ev/capture elem :mousedown func)
    e))

;; extension (defineExtension) / line-widget (addLineWidget) / remove-line-widget
;; (removeLineWidget) removed with CM5 (no callers). CM6 eval-result widgets go
;; through the cm6.results decoration seam below.

;; Eval result-widget seam (ADR 0009): a CM6 decoration id (cm6.results). Decorations
;; auto-track through edits, so the CM5 move/relocate machinery has no counterpart.
(defn add-result-widget
  "Add an eval result widget DOM `el` at 0-based `line`. `opts`: {:block? — render
  below the line (underline/exception) vs inline at line end; :id — tracking id}.
  Returns the decoration id."
  [e line el {:keys [id] :as opts}]
  (cm6-results/add! (->cm-ed e) id line el opts))

(defn remove-result-widget
  "Remove the result widget `id`. (`_block?` kept for call-site symmetry.)"
  [e id _block?]
  (cm6-results/remove! (->cm-ed e) id))

(defn result-widget-present?
  "True if result `id` is still attached (its line not deleted)."
  [e id]
  (cm6-results/present? (->cm-ed e) id))

;; Watch-mark seam (ADR 0009): a CM6 watches-layer decoration id. Watch metadata
;; (custom expr) lives in the watches plugin's :watches map.
(defn add-watch-mark
  "Mark range [from, to) ({:line :ch}) as a watch highlight. Returns the id."
  [e from to]
  (let [v (->cm-ed e)
        st (cm6-view/view-state v)
        id (keyword (str "lt-watch-" (gensym)))]
    (cm6-watches/add! v id (cm6/pos->offset st from) (cm6/pos->offset st to))))

(defn watch-mark-bounds
  "Current {:from {:line :ch} :to {:line :ch}} of watch `id`, or nil if gone."
  [e id]
  (let [v (->cm-ed e)
        st (cm6-view/view-state v)]
    (when-let [r (cm6-watches/bounds v id)]
      {:from (cm6/offset->pos st (:from r)) :to (cm6/offset->pos st (:to r))})))

(defn clear-watch-mark
  "Remove watch highlight `id`."
  [e id]
  (cm6-watches/remove! (->cm-ed e) id))

;; LSP diagnostics seam (ADR 0010): render a server's publishDiagnostics into the
;; editor's diagnostics decoration layer. `diagnostics` is a vector of LSP
;; Diagnostic maps ({:range {:start/:end {:line :character}} :severity :message}).
(defn set-diagnostics
  "Replace the editor's rendered diagnostics. Returns the count."
  [e diagnostics]
  (cm6-diagnostics/set-diagnostics! (->cm-ed e) diagnostics))

(defn clear-diagnostics
  "Remove all rendered diagnostics from editor `e`."
  [e]
  (cm6-diagnostics/clear! (->cm-ed e)))

(defn diagnostic-count
  "Number of diagnostics currently rendered in editor `e`."
  [e]
  (cm6-diagnostics/count-diagnostics (cm6-view/view-state (->cm-ed e))))

(defn line
  "Returns the content of line `l` from editor `e`.

  See [getLine](http://codemirror.net/doc/manual.html#getLine)."
  [e l]
  (be/-line (backend e) l))

(defn first-line
  "Returns the first line of editor `e`.

  See [firstLine](http://codemirror.net/doc/manual.html#firstLine)."
  [e]
  (be/-first-line (backend e)))

(defn last-line
  "Returns the last line of editor `e`.

  See [lastLine](http://codemirror.net/doc/manual.html#lastLine)."
  [e]
  (be/-last-line (backend e)))

;; line-handle (getLineHandle) / lh->line (getLineNumber) removed with CM5 — CM6
;; has no LineHandle; consumers key by line number.

(defn line-length
  "Returns the length of line `l` from editor `e`."
  [e l]
  (count (line e l)))

(defn select-all
  "Select all lines from editor `e`."
  [e]
  (set-selection e
                 {:line (first-line e) :ch 0}
                 {:line (last-line e)}))

(defn set-line
  "Replace content at line `l` with `text` for editor `e`."
  [e l text]
  (let [length (line-length e l)]
    (replace e
             {:line l :ch 0}
             {:line l :ch length}
             text)))

;; Line CSS classes (CM5 add/removeLineClass) — a CM6 line-decoration impl is a
;; deferred refinement; these are no-ops for now (only the langs behavior-helper
;; line highlight, a cosmetic cue, depends on them). No crash on CM6.
(defn +line-class [_e _lh _plane _class] nil)
(defn -line-class [_e _lh _plane _class] nil)

;; show-hints (CodeMirror.showHint addon) removed with CM5 (no callers).

;; inner-mode (CM5 innerMode) removed — CM6 has no stream modes; auto-complete's
;; hint-pattern falls back to the per-editor :hint-pattern / default.

(defn position-hint
  "Position popup `elem` (already in the DOM) at editor position `pos` ({:line :ch})
  using the CM6 view's screen coords (coordsAtPos)."
  [e elem pos]
  (let [v (->cm-ed e)
        off (cm6/pos->offset (cm6-view/view-state v) pos)]
    (when-let [coords (.coordsAtPos v off)]
      (set! (.. elem -style -position) "fixed")
      (set! (.. elem -style -left) (str (.-left coords) "px"))
      (set! (.. elem -style -top) (str (.-bottom coords) "px")))))

(defn adjust-loc
  "Adjust position `loc` with integer offset `dir` and the key `axis`. Axis should either be `:line` or `:ch`.
  If `axis` is not specified, defaults to `:ch`."
  ([loc dir]
   (adjust-loc loc dir :ch))
  ([loc dir axis]
   (when loc
     (update-in loc [axis] + dir))))

(defn get-char
  "Returns the characters found from integer offest `dir` to the current cursor position.

  See range."
  [ed dir]
  (be/-get-char (backend ed) dir))

(defn- indent! [e dir]
  ;; CM6 indentMore/indentLess act on the current selection (CM5 "add"/"subtract").
  (if (= "subtract" (str dir))
    (cm6-view/indent-less! (->cm-ed e))
    (cm6-view/indent-more! (->cm-ed e))))

(defn indent-line
  "Indent line `l` by `dir` (\"add\"/\"subtract\"). CM6 indents via the selection,
  so this selects line `l` first."
  [e l dir]
  (set-selection e {:line l :ch 0} {:line l :ch 0})
  (indent! e dir))

(defn indent-lines
  "Indent the lines spanned by `from`..`to` by `dir`."
  [e from to dir]
  (set-selection e {:line (:line from) :ch 0} {:line (:line to) :ch 0})
  (indent! e dir))

(defn indent-selection
  "Indent the current selection by `dir` (\"add\"/\"subtract\")."
  [e dir]
  (indent! e dir))

;; Comment seam (ADR 0009). CM6 toggleComment/lineComment StateCommands act on the
;; view's CURRENT selection, so from/to/opts are unused (the view holds the user's
;; selection). Comment tokens come from the language's commentTokens languageData
;; (cm6.modes attaches them to the legacy modes).
(defn line-comment
  "Line-comment the current selection (CM6 lineComment)."
  [e _from _to _opts] (cm6-comment/line! (->cm-ed e)))

(defn uncomment
  "Uncomment the current selection (CM6 toggleLineComment removes when commented)."
  [e _from _to _opts] (cm6-comment/uncomment! (->cm-ed e)))

(defn block-comment
  "Block-comment the current selection (CM6 blockComment)."
  [e _from _to _opts] (cm6-comment/block! (->cm-ed e)))

(defn toggle-comment
  "Toggle comment over the current selection (CM6 toggleComment)."
  [e _from _to _opts] (cm6-comment/toggle! (->cm-ed e)))

;; Search seam (ADR 0009): cm6.find (cm6.search match computation + a highlight
;; decoration layer). `opts` carries {:reverse? :regexp? :case-sensitive?};
;; stateless — find.cljs passes the query each call.
(defn search
  "Begin a search for `query`: move to the first match at/after the cursor and
  highlight all matches. Returns the match or nil."
  [e query opts]
  (let [v (->cm-ed e)]
    (cm6-find/search! v query opts (cm6/cursor-offset (cm6-view/view-state v)))))

(defn find-next "Move to the next match (wrapping)." [e query opts]
  (cm6-find/next! (->cm-ed e) query opts))

(defn find-prev "Move to the previous match (wrapping)." [e query opts]
  (cm6-find/prev! (->cm-ed e) query opts))

(defn clear-search "Clear the current search highlight." [e]
  (cm6-find/clear! (->cm-ed e)))

(defn replace-search
  "Replace the current match, or `all?` matches, with `replacement`."
  [e query replacement opts all?]
  (let [v (->cm-ed e)]
    (if all?
      (cm6-find/replace-all! v query replacement opts)
      (when-not (cm6-find/replace-current! v query replacement opts)
        (when (cm6-find/next! v query opts)
          (cm6-find/replace-current! v query replacement opts))))))

(defn ->generation
  "Returns an integer that can be used to test if edits have occurred.

  See [changeGeneration](http://codemirror.net/doc/manual.html#changeGeneration)."
  [e]
  (be/-generation (backend e)))

(defn dirty?
  "Returns true if document is not clean for generation `gen`. The document is not clean if it has been modified since it was in a clean state.

  See [isClean](http://codemirror.net/doc/manual.html#isClean)."
  [e gen]
  (be/-dirty? (backend e) gen))

;; get-doc removed — CM6 has no detachable Doc; save-as seeds from (editor/->val).

(defn fold-code
  "Toggle the fold at the cursor (CM6 foldCode StateCommand; the editor carries the
  codeFolding extension). `loc` is accepted for call-site compat but ignored —
  CM6 folds at the selection."
  ([e] (cm6-fold/fold-code! (->cm-ed e)))
  ([e _loc] (cm6-fold/fold-code! (->cm-ed e))))

(defn- gutter-widths [e]
  (let [gutter-div (dom/$ :div.CodeMirror-gutters (object/->content e))
        gutter-divs (dom/$$ :div.CodeMirror-gutter gutter-div)
        current-widths (reduce (fn [res gutter]
                                 (let [gutter-class (clojure.string/replace-first (dom/attr gutter "class") "CodeMirror-gutter " "")]
                                   (assoc res gutter-class (dom/width gutter)))
                                 ) {} gutter-divs)]
    current-widths))

(defn- update-gutters [e class-names class-widths]
  (let [gutter-div (dom/$ :div.CodeMirror-gutters (object/->content e))]
    (operation e (fn[]
                   (set-options e {:gutters (clj->js class-names)})
                   (doseq [[k v] class-widths]
                     (if-let [gutter (dom/$ (str "div." k) gutter-div)]
                       (dom/set-css gutter {"width" (str v "px")})))))))

(defn add-gutter
  "Add gutter with `class-name` of specified `width` to editor `e`."
  [e class-name width]
  (let [gutter-classes (set (conj (js->clj (option e "gutters")) class-name))
        current-widths (gutter-widths e)
        new-gutter-widths (assoc current-widths class-name width)]
    (update-gutters e gutter-classes new-gutter-widths)))

(defn remove-gutter
  "Remove gutter with `class-name` from editor `e`."
  [e class-name]
  (let [gutter-classes (remove #{class-name} (js->clj (option e "gutters")))
        current-widths (gutter-widths e)]
    (update-gutters e gutter-classes current-widths)))

;;*********************************************************
;; Object
;;*********************************************************

;; The CM5 library is no longer loaded into the renderer — the editor is CM6 (the
;; @codemirror/* packages, bundled by shadow). (One background worker still requires
;; codemirror's runmode StringStream to parse .behaviors files — ADR 0009 follow-up.)

(object* ::editor
         :tags #{:editor :editor.inline-result :editor.keys.normal}
         :init (fn [obj info]
                 ;; CM6 is the ONLY backend (ADR 0009 — CM5 removed). A detached
                 ;; EditorView; its .dom is the tab element; the seam drives it.
                 (let [compartments (cm6-options/make-compartments)
                       lang-compartment (cm6-modes/make-compartment)
                       ;; CM6's single updateListener → the LightTable :change/:move
                       ;; triggers (vs CM5's per-event .on wiring).
                       events (cm6-view/update-listener
                                (fn [update]
                                  (when (.-docChanged update) (object/raise obj :change update))
                                  (when (.-selectionSet update) (object/raise obj :move update))))
                       extra (.concat (cm6-options/initial-extensions compartments)
                                      #js [events
                                           cm6-view/editing-keymap
                                           cm6-modes/syntax-highlighting
                                           (:field cm6-find/layer)
                                           (:field cm6-results/layer)
                                           (:field cm6-watches/layer)
                                           (:field cm6-diagnostics/layer)
                                           (cm6-fold/extension)
                                           (cm6-modes/initial lang-compartment (:mime info))])
                       ;; Seed from :content (transient editors) or, for a file editor,
                       ;; from the doc's :content (opener passes :doc, not :content). The
                       ;; doc object is the manager's record (path/mtime); editing + save
                       ;; flow through the CM6 view/backend.
                       seed (or (:content info)
                                (when-let [d (:doc info)] (:text (deref d)))
                                "")
                       state (cm6/make-state seed extra)
                       view (cm6-view/create-view nil {:state state})]
                   (object/merge! obj {:ed view
                                       :backend (be/cm6-backend view)
                                       :backend-kind :cm6
                                       :cm6-compartments compartments
                                       :cm6-language lang-compartment
                                       :info (dissoc info :content :doc)})
                   ;; focus/blur on the contenteditable drive :focus→:active /
                   ;; :blur→:inactive (pool last-active tracking).
                   (let [cd (.-contentDOM view)]
                     (.addEventListener cd "focus" (fn [_] (object/raise obj :focus)))
                     (.addEventListener cd "blur"  (fn [_] (object/raise obj :blur))))
                   (cm6-view/dom view))))


;;*********************************************************
;; Behaviors
;;*********************************************************


(behavior ::wrap
          :triggers #{:object.instant :lt.object/tags-removed}
          :desc "Editor: Wrap lines"
          :exclusive [::no-wrap]
          :type :user
          :reaction (fn [obj]
                      (set-options obj {:lineWrapping true})))

(behavior ::no-wrap
          :triggers #{:object.instant :lt.object/tags-removed}
          :desc "Editor: Unwrap lines"
          :exclusive [::wrap]
          :type :user
          :reaction (fn [obj]
                      (set-options obj {:lineWrapping false})))

(behavior ::line-numbers
          :triggers #{:object.instant :lt.object/tags-removed}
          :desc "Editor: Show line numbers"
          :exclusive [::hide-line-numbers]
          :type :user
          :reaction (fn [this]
                      (set-options this {:lineNumbers true})))

(behavior ::hide-line-numbers
          :triggers #{:object.instant :lt.object/tags-removed}
          :desc "Editor: Hide line numbers"
          :exclusive [::line-numbers]
          :type :user
          :reaction (fn [this]
                      (set-options this {:lineNumbers false})))

(behavior ::fold-gutter
          :triggers #{:object.instant :lt.object/tags-removed}
          :desc "Editor: Show fold gutter"
          :exclusive [::hide-fold-gutter]
          :type :user
          :reaction (fn [this]
                      (set-options this {:foldGutter true
                                         :gutters (clj->js ["CodeMirror-foldgutter"])})))

(behavior ::hide-fold-gutter
          :triggers #{:object.instant :lt.object/tags-removed}
          :desc "Editor: Hide fold gutter"
          :exclusive [::fold-gutter]
          :type :user
          :reaction (fn [this]
                      (set-options this {:foldGutter false})))

(behavior ::scroll-past-end
          :triggers #{:object.instant :lt.object/tags-removed}
          :desc "Editor: Allow scrolling past the end of the file"
          :exclusive true
          :type :user
          :reaction (fn [this]
                      (set-options this {:scrollPastEnd true})))

(behavior ::tab-settings
          :triggers #{:object.instant}
          :desc "Editor: indent settings (tab size, etc)"
          :params [{:label "Use tabs?"
                    :type :boolean}
                   {:label "Tab size in spaces"
                    :type :number}
                   {:label "Spaces per indent"
                    :type :number}]
          :type :user
          :exclusive true
          :reaction (fn [obj use-tabs? tab-size indent-unit]
                      (set-options obj {:tabSize tab-size
                                        :indentWithTabs use-tabs?
                                        :indentUnit indent-unit})))

(behavior ::set-codemirror-flags
          :triggers #{:object.instant}
          :type :user
          :desc "Editor: Set CodeMirror flags"
          :params [{:label "Flags map"
                    :ex "{:undoDepth 1000}"}]
          :reaction (fn [this flags]
                      (set-options this flags)))

(behavior ::read-only
          :triggers #{:object.instant}
          :desc "Editor: make editor read-only"
          :exclusive [::not-read-only]
          :reaction (fn [this]
                      (object/update! this [:info :name] str " (read-only)")
                      (set-options this {:readOnly true})))

(behavior ::not-read-only
          :triggers #{:object.instant}
          :desc "Editor: make editor writable"
          :exclusive [::read-only]
          :reaction (fn [this]
                      (set-options this {:readOnly false})))

(behavior ::blink-rate
          :triggers #{:object.instant}
          :desc "Editor: set cursor blink rate"
          :exclusive true
          :type :user
          :reaction (fn [this rate]
                      (if rate
                        (set-options this {:cursorBlinkRate rate})
                        (set-options this {:cursorBlinkRate 0}))))

(behavior ::active-on-focus
          :triggers #{:focus}
          :reaction (fn [obj]
                      (object/add-tags obj [:editor.active])
                      (object/raise obj :active)))

(behavior ::inactive-on-blur
          :triggers #{:blur}
          :reaction (fn [obj]
                      (object/remove-tags obj [:editor.active])
                      (object/raise obj :inactive)))

(behavior ::refresh!
          :triggers #{:refresh!}
          :reaction (fn [this]
                      (refresh this)))

(behavior ::on-tags-added
          :triggers #{:lt.object/tags-added}
          :reaction (fn [this added]
                      (doseq [a added
                              :when a]
                        (ctx-obj/in! a this))))

(behavior ::on-tags-removed
          :triggers #{:lt.object/tags-removed}
          :reaction (fn [this removed]
                      (doseq [r removed
                              :when r]
                        (ctx-obj/out! r this))))

(behavior ::context-on-active
          :triggers #{:active}
          :reaction (fn [obj]
                      ;;TODO: this is probably inefficient due to inactive
                      (ctx-obj/in! (:tags @obj) obj)))


(behavior ::context-on-inactive
          :triggers #{:inactive}
          :reaction (fn [obj]
                      (let [tags (:tags @obj)
                            cur-editor (ctx-obj/->obj :editor)]
                        ;;blur comes after the focus of a second editor
                        ;;so only go out if I was the editor that is active
                        (ctx-obj/out! tags)
                        (when (and cur-editor
                                   (not= cur-editor obj))
                          (ctx-obj/in! (:tags @cur-editor) cur-editor))
                        (object/raise obj :deactivated))))

(behavior ::refresh-on-show
          :triggers #{:show}
          :reaction (fn [obj]
                      (refresh (:ed @obj))
                      (object/raise obj :focus!)))


(behavior ::focus
          :triggers #{:focus!}
          :reaction (fn [obj]
                      (focus (:ed @obj))))

(behavior ::destroy-on-close
          :triggers #{:close.force}
          :reaction (fn [obj]
                      (object/raise obj :closed)
                      (object/destroy! obj)))

(behavior ::highlight-current-line
          :triggers #{:object.instant}
          :type :user
          :desc "Editor: Highlight the current line"
          :exclusive true
          :reaction (fn [this]
                      (set-options this {:styleActiveLine true})))

(behavior ::on-change
          :debounce 300
          :triggers #{:change}
          :type :user
          :desc "Editor: On change execute command"
          :params [{:label "command"}]
          :reaction (fn [this cmd & args]
                      (apply cmd/exec! cmd args)))

(behavior ::menu!
          :triggers #{:menu!}
          :reaction (fn [this e]
                      (let [items (sort-by :order (object/raise-reduce this :menu+ []))]
                        (-> (menu/menu items)
                            (menu/show-menu)))
                      (dom/prevent e)
                      (dom/stop-propagation e)))


(behavior ::copy-paste-menu+
          :triggers #{:menu+}
          :reaction (fn [this items]
                      (conj items
                            {:label "Copy"
                             :order 1
                             :enabled (boolean (selection? this))
                             :click (fn []
                                      (copy this))}
                            {:label "Cut"
                             :order 2
                             :enabled (boolean (selection? this))
                             :click (fn []
                                      (cut this))}
                            {:label "Paste"
                             :order 3
                             :enabled (boolean (not (empty? (platform/paste))))
                             :click (fn []
                                      (paste this))}
                            {:type "separator"
                             :order 4}
                            {:label "Select all"
                             :order 5
                             :click (fn []
                                      (select-all this))})))

;; ::init-codemirror behavior + mode-blacklist removed with CM5 (loaded CM5
;; addons/modes and set the CM5 Tab keymap to expand-tab). CM6 brings its own
;; bracket-matching/closing, comments, folding, active-line, and language modes
;; (cm6.modes) via the object*'s initial extensions.

;; ::load-addon (App: Load CodeMirror addon path) removed with CM5 — CM6 features
;; come from @codemirror packages in the editor's extension set, not loadable addons.

;; ::set-rulers behavior removed with CM5 (called .getOption + loaded the CM5
;; rulers addon; no CM6 counterpart wired).

(behavior ::autoclose-brackets
          :triggers #{:object.instant}
          :desc "Editor: Enable autoclose brackets"
          :type :user
          :params [{:label "map"
                    :example "{:pairs \"()[]{}''\\\"\\\"\" :explode \"[]{}\"}"}]
          :reaction (fn [this opts]
                      (if opts
                        (set-options this {:autoCloseBrackets (clj->js opts)})
                        (set-options this {:autoCloseBrackets true}))))
