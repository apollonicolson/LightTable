(ns lt.objs.eval
  "Provide objects for doing evals through clients and displaying inline
  results from evals"
  (:require [lt.object :as object]
            [lt.objs.canvas :as canvas]
            [lt.objs.editor :as ed]
            [lt.objs.menu :as menu]
            [lt.objs.files :as files]
            [lt.objs.editor.pool :as pool]
            [lt.objs.clients :as clients]
            [lt.util.cljs :refer [->dottedkw]]
            [lt.objs.sidebar.command :as cmd]
            [lt.objs.notifos :as notifos]
            [lt.objs.popup :as popup]
            [singultus.core :as crate]
            [singultus.binding :refer [bound]]
            [lt.objs.console :as console]
            [lt.util.dom :as dom]
            [clojure.string :as string]
            [cljs.reader :as reader]
            [lt.objs.platform :as platform])
  (:require-macros [lt.macros :refer [behavior defui]]))

(defui button [label & [cb]]
  [:div.button.right label]
  :click (fn []
           (when cb
             (cb))))

(defn unescape-unicode [s]
  (string/replace s
                  #"\\x(..)"
                  (fn [res r]
                    (js/String.fromCharCode (js/parseInt r 16)))))

(let [ev-id (atom 0)]
  (defn append-source-file [code file]
    (str code "\n\n//# sourceURL=" (or file "evalresult") "[eval" (swap! ev-id inc) "]")))

(defn pad [code lines]
  (str (reduce str (repeat lines "\n"))
       code))

(defn cljs-result-format [n]
  (cond
   (coll? n) (pr-str n)
   (fn? n) (str "(fn " (.-name n) " ..)")
   (nil? n) "nil"
   (= (pr-str n) "#<[object Object]>") (console/inspect n)
   :else (pr-str n)))

(behavior ::on-selected-cb
          :triggers #{:selected}
          :reaction (fn [obj client]
                      (let [cb (@obj :cb)]
                        (cb client))))

(behavior ::on-selected-destroy
          :triggers #{:selected}
          :reaction (fn [obj client]
                      (object/raise obj :destroy)
                      ))

(def eval-queue (atom []))

(behavior ::queue-on-no-client
          :triggers #{:no-client}
          :reaction (fn [this queue-item]
                      (swap! eval-queue conj queue-item)
                      ))

(behavior ::alert-on-no-client
          :triggers #{:no-client}
          :reaction (fn [this]
                      (popup/popup! {:header "No client available."
                                     :body "We don't know what kind of client you want for this one. Try starting a client by choosing one of the connection types in the connect panel."
                                     :buttons [{:label "Connect a client"
                                                :action (fn []
                                                          (cmd/exec! :show-add-connection))}]})
                      ))

(behavior ::queue!
          :triggers #{:queue!}
          :reaction (fn [this queue-item]
                      (swap! eval-queue conj queue-item)
                      ))


(defn drain [queue]
  (vec (remove
        (fn [cur]
          (let [[_ _ cb] cur
                [result client] (apply clients/discover cur)]
            (when (= :found result)
              (cb client)
              true)))
        queue)))

(behavior ::on-connect-check-queue
          :triggers #{:connect}
          :reaction (fn [this]
                      (swap! eval-queue drain)
                      ))

(object/object* ::evaler
                :tags #{:evaler}
                :init (fn []))

(def evaler (object/create ::evaler))

(object/add-behavior! clients/clients ::on-connect-check-queue)

(defn try-read [r]
  (try
    (reader/read-string r)
    (catch :default e
      (console/error e))))

(defn find-client [{:keys [origin command info key create] :as opts}]
  (let [[result client] (clients/discover command info)
        key (or key :default)]
    (condp = result
      :none (if create
              (create opts)
              (do
                (notifos/done-working)
                (object/raise evaler :no-client opts)
                (clients/placeholder)))
      :found client
      :select (do
                (object/raise evaler :select-client client (fn [client]
                                                             (clients/swap-client! (-> @origin :client key) client)
                                                             (object/update! origin [:client] assoc key client)))
                (clients/placeholder))
                )))

(defn get-client! [{:keys [origin command key create] :as opts}]
  (let [key (or key :default)
        cur (-> @origin :client key)]
    (if (and cur (clients/available? cur))
      cur
      (let [neue (find-client opts)]
        (object/update! origin [:client] assoc key neue)
        (object/raise origin :set-client neue)
        neue))))

;;****************************************************
;; inline result
;;****************************************************

(defn ->result-class [this trunc]
  (str (:class this)
       "-result result-mark"
       (when (or (:open this)
                 (not trunc))
         " open"
         )))

(defn truncate-result
  ([r] (truncate-result r nil))
  ([r opts]
   (when (string? r)
     (let [nl (.indexOf r "\n")
           len (if (> nl -1)
                 nl
                 (:trunc-length opts 50))]
       (if (> (count r) len)
         (str (subs r 0 len)  " …")
         r)))))

(defui ->inline-res [this info]
  (let [r (:result info)
        truncated (truncate-result r info)]
    [:span {:class (bound this #(->result-class % truncated))}
     (when truncated
       [:span.truncated truncated])
     [:span.full r]])
  :mousewheel (fn [e]
                (dom/stop-propagation e))
  :click (fn [e]
           (dom/prevent e)
           (object/raise this :click))
  :contextmenu (fn [e]
                 (dom/prevent e)
                 (object/raise this :menu! e))
  :dblclick (fn [e]
              (dom/prevent e)
              (object/raise this :double-click)))

(behavior ::result-menu+
          :triggers #{:menu+}
          :reaction (fn [this items]
                      (conj items
                            {:label "Remove result"
                             :order 1
                             :click (fn [] (object/raise this :clear!))}
                            {:label "Copy result"
                             :order 2
                             :click (fn [] (object/raise this :copy))})))

(behavior ::expand-on-click
          :triggers #{:click :expand!}
          :reaction (fn [this]
                      (object/merge! this {:open true})
                      (object/raise this :changed)
                      ))

(behavior ::shrink-on-double-click
          :triggers #{:double-click :shrink!}
          :reaction (fn [this]
                      (object/merge! this {:open false})
                      (object/raise this :changed)
                      (ed/focus (:ed @this))))

(behavior ::destroy-on-cleared
          :triggers #{:cleared}
          :reaction (fn [this]
                      (object/destroy! this)))

;; Registry key for an editor's :widgets map. CM5 keys by LineHandle (a stable
;; identity); CM6 has no line handles, so key by line NUMBER. CM6 result widgets
;; auto-track, so a stale number only risks a duplicate on the rare cross-eval line
;; shift, never a crash (ADR 0009).
;; CM6 has no LineHandle — key the :widgets registry by line NUMBER (ADR 0009).
(defn- widget-key [_edobj line-num type]
  [line-num type])

(behavior ::clear-mark
          :triggers #{:clear!}
          :reaction (fn [this]
                      (ed/remove-result-widget (:ed @this) (:widget-id @this) false)
                      (object/raise this :clear)
                      (object/raise this :cleared)))

(behavior ::copy-result
          :triggers #{:copy}
          :reaction (fn [this]
                      (platform/copy (:result @this))))

(behavior ::changed
          :triggers #{:changed}
          ;; CM6 widgets re-render via the decoration field — nothing to do (the CM5
          ;; .changed re-measure had no CM6 counterpart). Kept so the :changed
          ;; trigger still has a (no-op) handler.
          :reaction (fn [_this]))

(behavior ::update!
          :triggers #{:update!}
          :reaction (fn [this res]
                      (let [content (object/->content this)
                            full (dom/$ :.full content)
                            scroll (dom/scroll-top full)]
                        (when-let [t (truncate-result res)]
                          (when-let [trunc (dom/$ :.truncated content)]
                            (dom/html trunc t)))
                        (dom/html full res)
                        (dom/scroll-top full scroll))))

;; CM5 had ::move-mark (relocate the bookmark as text shifted) — CM6 decorations
;; auto-track, so the whole behavior is gone (its :move! trigger is never raised).


(object/object* ::inline-result
                :triggers #{:click :double-click :clear!}
                :tags #{:inline :inline.result}
                :init (fn [this info]
                        ;; CM6: an inline widget decoration that auto-tracks (no
                        ;; bookmark, no per-line listeners — that was the CM5 path).
                        (let [content (->inline-res this info)
                              id (keyword (str "lt-ir-" (gensym)))]
                          (ed/add-result-widget (:ed info) (-> info :loc :line) content
                                                {:id id :block? false})
                          (object/merge! this (assoc info :widget-id id))
                          content)))






(behavior ::inline-results
          :triggers #{:editor.result}
          :reaction (fn [this res loc opts]
                      (let [type (or (:type opts) :inline)
                            line (:line loc)
                            res-obj (object/create ::inline-result {:ed this
                                                                    :class (name type)
                                                                    :opts opts
                                                                    :result res
                                                                    :loc loc
                                                                    :line line})]
                        (when-let [prev (get (@this :widgets) [line type])]
                          (when (:open @prev)
                            (object/merge! res-obj {:open true}))
                          (object/raise prev :clear!))
                        (when (:start-line loc)
                          (doseq [widget (map #(get (@this :widgets) (widget-key this % type)) (range (:start-line loc) (:line loc)))
                                  :when widget]
                            (object/raise widget :clear!)))
                        (object/update! this [:widgets] assoc [line type] res-obj))))

;;****************************************************
;; underline result
;;****************************************************

(defn ->spacing [text]
  (when text
    (-> (re-seq #"^\s+" text)
        (first))))

(defui ->underline-result [this info]
  [:div {:class (str "underline-result " (when (-> info :class) (:class info)))}
   [:span.spacer (->spacing (ed/line (:ed info) (-> info :loc :line)))]
   [:pre (:result info)]]
  :click (fn [e]
           (dom/prevent e)
           (object/raise this :click))
  :contextmenu (fn [e]
                 (dom/prevent e)
                 (object/raise this :menu! e))
  :dblclick (fn [e]
              (dom/prevent e)
              (object/raise this :double-click)))

(object/object* ::underline-result
                :tags #{:inline :inline.underline-result}
                :init (fn [this info]
                        (let [content (->underline-result this info)
                              id (keyword (str "lt-ur-" (gensym)))]
                          (ed/add-result-widget (:ed info) (-> info :loc :line) content
                                                {:id id :block? true})
                          (object/merge! this (assoc info :widget-id id))
                          content)))

(behavior ::underline-results
          :triggers #{:editor.result.underline}
          :reaction (fn [this res loc opts]
                      (let [line (:line loc)
                            res-obj (object/create ::underline-result {:ed this
                                                                       :opts opts
                                                                       :result res
                                                                       :loc loc
                                                                       :line line})]
                        (when-let [prev (get (@this :widgets) [line :underline])]
                          (when (:open @prev)
                            (object/merge! res-obj {:open true}))
                          (object/raise prev :clear!))
                        (when (:start-line loc)
                          (doseq [widget (map #(get (@this :widgets) (widget-key this % :underline)) (range (:start-line loc) (:line loc)))
                                  :when widget]
                            (object/raise widget :clear!)))
                        (object/update! this [:widgets] assoc [line :underline] res-obj))))

(behavior ::copy-underline-result
          :triggers #{:copy}
          :reaction (fn [this]
                      (platform/copy (string/join "\n"
                                                  (map #(.-innerText %) (.-children (:result @this)))))))

;;****************************************************
;; inline exception
;;****************************************************

(defn ->exception-class [this]
  (str "inline-exception " (when (:open this)
                             "open"
                             )))

(defui ->inline-exception [this info]
  [:div {:class (bound this ->exception-class)}
   [:span.spacer (->spacing (ed/line (:ed info) (-> info :loc :line)))]
   [:pre (str (:ex info))]]
  :click (fn []
           (object/raise this :click))
  :contextmenu (fn [e]
                 (object/raise this :menu! e))
  :dblclick (fn []
              (object/raise this :double-click)))

(behavior ::ex-shrink-on-double-click
          :triggers #{:double-click :shrink!}
          :reaction (fn [this]
                      (ed/focus (:ed @this))
                      (object/raise this :clear!)))


(behavior ::ex-clear
          :triggers #{:clear!}
          :reaction (fn [this]
                      (ed/remove-result-widget (:ed @this) (:widget-id @this) true)
                      (object/raise this :clear)
                      (object/raise this :cleared)))

(behavior ::ex-menu+
          :triggers #{:menu+}
          :reaction (fn [this items]
                      (conj items
                            {:label "Remove exception"
                             :click (fn [] (object/raise this :clear!))}
                            {:label "Copy exception"
                             :click (fn [] (object/raise this :copy))})))

(behavior ::copy-exception
          :triggers #{:copy}
          :reaction (fn [this]
                      (platform/copy (:ex @this))))

(object/object* ::inline-exception
                :triggers #{:click :double-click :clear!}
                :tags #{:inline :inline.exception}
                :init (fn [this info]
                        (if-not (-> info :loc :line)
                          (notifos/set-msg! (str (:ex info)) {:class "error"})
                          (let [content (->inline-exception this info)
                                id (keyword (str "lt-ex-" (gensym)))]
                            (ed/add-result-widget (:ed info) (-> info :loc :line) content
                                                  {:id id :block? true})
                            (object/merge! this (assoc info :widget-id id))
                            content))))

(behavior ::inline-exceptions
          :triggers #{:editor.exception}
          :reaction (fn [this ex loc]
                      (when (and ex loc (>= (:line loc) 0))
                        (let [line (:line loc)
                              ex-obj (object/create ::inline-exception {:ed this
                                                                        :ex ex
                                                                        :loc loc
                                                                        :line line})]
                          (doseq [prev [(get (@this :widgets) [line :inline])
                                        (get (@this :widgets) [line :underline])]
                                  :when prev]
                            (when (:open @prev)
                              (object/merge! ex-obj {:open true}))
                            (object/raise prev :clear!))
                          (when (:start-line loc)
                            (doseq [type [:inline :underline]
                                    widget (map #(get (@this :widgets) (widget-key this % type)) (range (:start-line loc) (:line loc)))
                                    :when widget]
                              (object/raise widget :clear!)))
                          (object/update! this [:widgets] assoc [line :inline] ex-obj)))))

(behavior ::eval-on-change
          :triggers #{:change}
          :desc "Editor: Eval when the editor changes"
          :type :user
          :debounce 300
          :reaction (fn [this]
                      (object/raise this :eval)))

(cmd/command {:command :clear-inline-results
              :desc "Eval: Clear inline results"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (doseq [[_ w] (:widgets @ed)]
                          (object/raise w :clear!))))})

(cmd/command {:command :eval-editor
              :desc "Eval: Eval editor contents"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :eval)))})

(cmd/command {:command :eval-editor-form
              :desc "Eval: Eval a form in editor"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :eval.one)))})

(cmd/command {:command :eval.custom
              :desc "Eval: Eval custom expression in editor"
              :hidden true
              :exec (fn [exp opts]
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :eval.custom exp opts)))})


(cmd/command {:command :eval.cancel-all!
              :desc "Eval: Cancel evaluation for the current client"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (when (:client @ed)
                          (doseq [[_ client] (:client @ed)]
                            (clients/cancel-all! client)))))})

(cmd/command {:command :editor.disconnect-clients
              :desc "Editor: Disconnect clients attached to editor"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (doseq [client (-> @ed :client vals)]
                          (clients/close! client))))})

