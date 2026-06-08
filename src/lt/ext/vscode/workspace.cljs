(ns lt.ext.vscode.workspace
  "Phase 3b of the VSCode extension host (ADR 0011): the `vscode.workspace` namespace
  — the open-document registry, the onDidOpen/Change/Close events (the change event
  carries the doc-sync content-changes from a CM6 ChangeSet), and getConfiguration.
  Node-loadable + tested; the live wiring (editor :change → notify-change!) is the
  editor-coupled bridge."
  (:require [lt.ext.vscode.document :as doc]
            [lt.ext.vscode.types :as t]))

(defonce ^:private docs      (atom {}))   ; uri -> TextDocument
(defonce ^:private listeners (atom {:open #{} :change #{} :close #{}}))
(defonce ^:private config    (atom {}))   ; flat dotted key -> value

(defn set-config! [m] (reset! config m))

(defn- fire [kind ev] (doseq [l (get @listeners kind)] (l ev)))
(defn- on [kind l]
  (swap! listeners update kind conj l)
  (t/disposable (fn [] (swap! listeners update kind disj l))))

(defn open-document!
  "Register a document (`{:uri :languageId :version :text}`) and fire onDidOpen."
  [opts]
  (let [d (doc/make-text-document opts)]
    (swap! docs assoc (:uri opts) d)
    (fire :open d)
    d))

(defn notify-change!
  "An edit landed: rebuild the doc at `uri` from `new-text` (version++) and fire
  onDidChangeTextDocument with the content-changes derived from `changeset` (the
  CM6 ChangeSet) against the previous doc."
  [uri changeset new-text]
  (when-let [old (get @docs uri)]
    (let [changes (doc/content-changes old changeset)
          nd (doc/make-text-document {:uri uri
                                      :languageId (.-languageId old)
                                      :version (inc (.-version old))
                                      :text new-text})]
      (swap! docs assoc uri nd)
      (fire :change #js {:document nd :contentChanges changes})
      nd)))

(defn close-document! [uri]
  (when-let [d (get @docs uri)]
    (swap! docs dissoc uri)
    (fire :close d)))

(defn get-configuration [section]
  (let [prefix (if section (str section ".") "")]
    #js {:get    (fn [key & [dflt]] (get @config (str prefix key) dflt))
         :has    (fn [key] (contains? @config (str prefix key)))
         :update (fn [key value & _]
                   (swap! config assoc (str prefix key) value)
                   (.resolve js/Promise nil))}))

(defn ns-object []
  (let [ws #js {:getConfiguration        (fn [& [section]] (get-configuration section))
                :onDidOpenTextDocument   (fn [l] (on :open l))
                :onDidChangeTextDocument (fn [l] (on :change l))
                :onDidCloseTextDocument  (fn [l] (on :close l))
                :workspaceFolders        #js []}]
    ;; textDocuments is a live getter in vscode, not a static array.
    (js/Object.defineProperty ws "textDocuments"
                              #js {:get (fn [] (clj->js (vec (vals @docs)))) :enumerable true})
    ws))

(defn reset-workspace! []
  (reset! docs {})
  (reset! listeners {:open #{} :change #{} :close #{}})
  (reset! config {}))
