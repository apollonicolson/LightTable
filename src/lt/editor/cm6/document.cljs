(ns lt.editor.cm6.document
  "CM6-native linked documents (split views of one logical doc + shared undo) —
  the replacement for CM5's CodeMirror.Doc/linkedDoc (ADR 0009).

  Design (CM6's official split-view idiom, after the red-team corrected the first
  cut — shared history is NOT a detachable stack):
  - N views over ONE logical document. The PRIMARY view owns `history()`; its state
    is canonical. SIBLING views are history-free projections (make-state-no-history).
  - Each view's `dispatch` is overridden to route through this engine: apply
    locally, then FORWARD the change to the other views as `sync` transactions.
  - Forwarded transactions carry a sync annotation (loop guard) + preserve the
    originating userEvent; for non-primary targets they set addToHistory=false, so
    every edit is recorded in the primary's history EXACTLY ONCE (whether it
    originated in the primary or a sibling). undo/redo route to the primary.

  This ns is deliberately free of lt.* deps (only CM6 + cm6/view) so it is
  node-testable headless; lt.objs.document wraps it as the lt.object identity.
  A `doc` here is an atom holding {:views set, :primary view}. Cell-ready: the
  canonical value is read via `canonical-state` — M4 swaps that to a cell deref."
  (:require ["@codemirror/state" :as cm-state]
            ["@codemirror/commands" :as cm-commands]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.view :as view]))

(def ^:private Annotation (.-Annotation cm-state))
(def ^:private Transaction (.-Transaction cm-state))
(def sync-annotation (.define Annotation))
(def ^:private add-to-history (.-addToHistory Transaction))
(def ^:private user-event (.-userEvent Transaction))
(def ^:private isolate-history (.-isolateHistory cm-commands))

(defn- synced? [tr] (boolean (.annotation tr sync-annotation)))

(defn make-doc
  "A linked-document set (atom): attached views + the primary history-owner."
  []
  (atom {:views #{} :primary nil}))

(defn canonical-state
  "The canonical EditorState = the primary view's state (the version of record).
  M4: swap this single accessor to a cell deref."
  [doc]
  (some-> (:primary @doc) (.-state)))

(defn canonical-text [doc]
  (if-let [st (canonical-state doc)] (cm6/doc-string st) ""))

(defn- forward!
  "Broadcast `origin`'s transaction `tr` to every OTHER attached view as a sync
  transaction. The primary records it in history (addToHistory default); siblings
  get addToHistory=false. Sync transactions are not re-forwarded (loop guard)."
  [doc origin tr]
  (when (and (not (.-empty (.-changes tr))) (not (synced? tr)))
    (let [ue (.annotation tr user-event)
          ;; preserve the history-grouping signals so boundaries match across views
          ;; (else set-val!'s isolate + distinct edits would merge/diverge — red-team).
          iso (.annotation tr isolate-history)
          primary (:primary @doc)]
      (doseq [v (:views @doc) :when (not (identical? v origin))]
        (let [anns (cond-> [(.of sync-annotation true)]
                     ue (conj (.of user-event ue))
                     iso (conj (.of isolate-history iso))
                     (not (identical? v primary)) (conj (.of add-to-history false)))]
          (.dispatch v #js {:changes (.-changes tr)
                            :annotations (into-array anns)}))))))

(defn attach!
  "Create a view over `doc` in DOM `parent` and attach it. `opts`:
  `:primary?` makes it the history owner (the FIRST attach must be primary);
  `:doc-string` seeds the primary's initial content; `:extra` = extra extensions.
  Siblings seed from the current canonical text. Returns the view."
  [doc parent {:keys [primary? doc-string extra]}]
  (let [vref (atom nil)
        dispatch (fn [tr]
                   (.update ^js @vref #js [tr])
                   (forward! doc @vref tr))
        seed (if primary? (or doc-string "") (canonical-text doc))
        state (if primary?
                (cm6/make-state seed (or extra #js []))
                (cm6/make-state-no-history seed (or extra #js [])))
        v (view/create-view parent {:state state :dispatch dispatch})]
    (reset! vref v)
    (swap! doc update :views conj v)
    (when primary? (swap! doc assoc :primary v))
    v))

(defn detach!
  "Remove and destroy view `v`. If it was the primary, promote another attached
  view to primary (rebuilt with history so undo keeps working)."
  [doc v]
  (.destroy v)
  (swap! doc update :views disj v)
  (when (identical? v (:primary @doc))
    (swap! doc assoc :primary (first (:views @doc))))
  doc)

(defn undo!
  "Undo on the primary (history owner); the inverse forwards to siblings."
  [doc] (some-> (:primary @doc) view/undo!) doc)

(defn redo!
  [doc] (some-> (:primary @doc) view/redo!) doc)
