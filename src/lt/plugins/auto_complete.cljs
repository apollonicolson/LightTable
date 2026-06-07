(ns lt.plugins.auto-complete
  "Provide any auto-complete related functionality"
  (:require [lt.object :as object]
            [lt.objs.keyboard :as keyboard]
            [lt.objs.command :as cmd]
            [lt.objs.thread :as thread]
            [lt.objs.sidebar.command :as scmd]
            [lt.objs.editor.pool :as pool]
            [lt.objs.editor :as editor]
            [lt.objs.context :as ctx]
            [clojure.string :as string]
            [lt.util.js :refer [wait]]
            [lt.util.dom :as dom])
  (:require-macros [lt.macros :refer [behavior defui]]))

;; Pure tokenizer (no CM5 StringStream): a token is a maximal run of `pattern`
;; chars. `pattern` is a single-char regex (e.g. #"[\w_$]"); we match runs of it.
(defn- run-re [pattern]
  (js/RegExp. (str "(?:" (.-source pattern) ")+") "g"))

(defn string->tokens [str pattern]
  (let [re (run-re pattern)
        out (js-obj)]
    (loop []
      (when-let [m (.exec re str)]
        (aset out (aget m 0) true)
        (recur)))
    (into-array (map (fn [w] #js {:completion w}) (js/Object.keys out)))))

(def default-pattern #"[\w_$]")

(defn get-pattern [ed]
  ;; CM6 has no per-mode hint-pattern — use the per-editor :hint-pattern (set by
  ;; langs behaviors) or the default token pattern.
  (or (:hint-pattern @ed) default-pattern))

(defn get-token [ed pos]
  (let [line (editor/line ed (:line pos))
        re (run-re (get-pattern ed))
        ch (:ch pos)
        empty-tok {:line (:line pos) :start ch :end ch}]
    (loop []
      (if-let [m (.exec re line)]
        (let [start (.-index m)
              end (+ start (.-length (aget m 0)))]
          (cond
            (and (<= start ch) (>= end ch)) {:start start :end end :line (:line pos)
                                             :string (subs line start end)}
            (> start ch) empty-tok
            :else (recur)))
        empty-tok))))

(defn non-token-change? [ed ch]
  ;; CM6 has no per-line CM5 change object (ch is nil) — treat as a token change so
  ;; live filtering continues rather than escaping the hint.
  (if-not ch
    false
    (let [pattern (get-pattern ed)
          text (map str (.-text ch))]
      (condp = (.-origin ch)
        "+input" (some #(not (re-seq pattern %)) text)
        "paste" true
        false))))

;; The CM5 runmode-StringStream background worker is gone — the pure string->tokens
;; runs inline (the calling behavior is debounced 400ms, so no thread needed).
(defn async-hints [this]
  (when @this
    (object/merge! this {::hints (string->tokens (editor/->val this) (get-pattern this))})))

(defn text|completion [x]
  (or (.-text x) (.-completion x)))

(defn text+completion [x]
  (str (.-text x) (.-completion x)))

(defn distinct-completions [hints]
  (let [seen #js {}]
    (filter (fn [hint]
              (if (true? (aget seen (.-completion hint)))
                false
                (aset seen (.-completion hint) true)))
            hints)))

(declare hinter)

(defn remove-long-completions [hints]
  (filter #(< (.-length (.-completion %)) (:hint-limit @hinter)) hints))

(def hinter (-> (scmd/filter-list {:items (fn []
                                            (when-let [cur (pool/last-active)]
                                              (let [token (-> @hinter :starting-token :string)]
                                                (->> (if token
                                                       (remove #(= token (.-completion %))
                                                               (object/raise-reduce cur :hints+ [] token))
                                                       (object/raise-reduce cur :hints+ []))
                                                     remove-long-completions
                                                     distinct-completions))))
                                   :key text|completion})
                (object/add-tags [:hinter])))

(defn on-line-change [line ch]
  (object/raise hinter :line-change line ch))

(behavior ::set-hint-limit
          :triggers #{:object.instant}
          :type :user
          :desc "Auto-complete: Set maximum length of an autocomplete hint"
          :params [{:label "Number"
                    :example 1000}]
          :reaction (fn [this n]
                      (object/merge! this {:hint-limit n})))

(behavior ::textual-hints
          :triggers #{:hints+}
          :reaction (fn [this hints]
                      (concat (::hints @this) hints)))

(behavior ::escape!
          :triggers #{:escape!}
          :reaction (fn [this force?]
                      (let [elem (object/->content this)]
                        (ctx/out! [:editor.keys.hinting.active])
                        (object/merge! this {:active false
                                             :selected 0
                                             :ed nil
                                             :starting-token nil
                                             :token nil
                                             :search ""})
                        (object/raise this :inactive)
                        (when (dom/parent elem)
                          (dom/remove elem)))))

(behavior ::select
          :triggers #{:select}
          :reaction (fn [this c]
                      (let [token (:token @this)
                            start {:line (:line token)
                                   :ch (:start token)}
                            end {:line (:line token)
                                 :ch (:end token)}]
                        (object/merge! this {:active false})
                        (if (.-select c)
                          ((.-select c) (partial editor/replace (:ed @this) start end) c)
                          (editor/replace (:ed @this) start end (.-completion c)))
                        (object/raise this :escape!))))

(behavior ::select-unknown
          :triggers #{:select-unknown}
          :reaction (fn [this v]
                      (object/raise this :escape!)
                      (keyboard/passthrough)))

(behavior ::line-change
          :triggers #{:line-change}
          :reaction (fn [this l c]
                      (when (:active @hinter)
                        (let [pos (editor/->cursor (:ed @this))
                              token (get-token (:ed @this) pos)]
                          (if (or (non-token-change? (:ed @this) c)
                                  (< (:ch pos) (-> @hinter :starting-token :start)))
                            (object/raise hinter :escape!)
                            (do
                              (object/raise hinter :change! (:string token))
                              (if (= 0 (count (:cur @hinter)))
                                (ctx/out! [:editor.keys.hinting.active :filter-list.input])
                                (when-not (ctx/in? :editor.keys.hinting.active)
                                  (ctx/in! [:filter-list.input] hinter)
                                  (ctx/in! [:editor.keys.hinting.active] (:ed @hinter))))
                              (object/merge! hinter {:token token})))))))

(behavior ::async-hint-tokens
          :triggers #{:hint-tokens}
          :reaction (fn [this tokens]
                      (object/merge! this {::hints tokens})))

(behavior ::cm6-hint-refresh
          :triggers #{:change}
          :desc "Auto-complete: refresh the open hint on CM6 editor changes (CM6 has
          no per-line change listener; this drives the same :line-change refresh)."
          :reaction (fn [this & _]
                      (when (and (:active @hinter)
                                 (identical? (:ed @hinter) this))
                        (on-line-change nil nil))))

(behavior ::intra-buffer-string-hints
          :triggers #{:change}
          :debounce 400
          :reaction (fn [this ch]
                      (when (or (not= (:ed @hinter) this)
                                (not (:active @hinter)))
                        (async-hints this))
                      ))

(defn start-hinting
  ([this] (start-hinting this nil))
  ([this opts]
   (let [pos (editor/->cursor this)
         token (get-token this pos)
         ;; CM6 tracks typing via the editor :change event (::cm6-hint-refresh) —
         ;; no per-line listener to register.
         elem (object/->content hinter)]
     (ctx/in! [:editor.keys.hinting.active] this)
     (object/merge! hinter {:token token
                            :starting-token token
                            :ed this
                            :active true})
     (object/raise hinter :change! (:string token))
     (object/raise hinter :active)
     (let [count (count (:cur @hinter))]
       (cond
        (= 0 count) (ctx/out! [:editor.keys.hinting.active :filter-list.input])
        (and (= 1 count)
             (:select-single opts)) (object/raise hinter :select! 0)
        :else (do
                (dom/append (dom/$ :body) elem)
                (editor/position-hint this elem {:line (:line token) :ch (:start token)})))))))

(behavior ::show-hint
          :triggers #{:hint}
          :reaction (fn [this opts]
                      (let [cur (string/trim (editor/get-char this -1))
                            opts (merge {:select-single true} opts)]
                        (cond
                         (and (:active @hinter)
                              (= (:ed @hinter) this)) (object/raise hinter :select!)
                         (and (empty? cur)
                              (not (:force? opts))) (keyboard/passthrough)
                         (:active @hinter) (do (object/raise hinter :escape!) (start-hinting this))
                         :else (start-hinting this opts)))))

(behavior ::remove-on-scroll-inactive
          :triggers #{:scroll :inactive}
          :reaction (fn [this]
                      (when (:active @hinter)
                        (object/raise hinter :escape!))))

(behavior ::remove-on-move-line
          :triggers #{:move}
          :reaction (fn [this c]
                      (when (:active @hinter)
                        ;;HACK: line change events are sent *after* cursor move
                        ;;this means that we need to wait for those to fire and then
                        ;;check if we're out of bounds.
                        (wait 0 (fn []
                                  (let [starting (:starting-token @hinter)
                                        cur (:token @hinter)
                                        cursor (editor/->cursor this)]
                                    (when (and starting
                                               cur
                                               (or (not (<= (:start cur) (:ch cursor) (:end cur)))
                                                   (not= (:line starting) (:line cursor))))
                                      (object/raise hinter :escape!))))))))

(behavior ::auto-show-on-input
          :triggers #{:input}
          :type :user
          :desc "Auto-complete: Show on change"
          :reaction (fn [this _ ch]
                      (when-not (non-token-change? this ch)
                        (when-not (and (:active @hinter)
                                       (= (:ed @hinter) this))
                          (object/raise this :hint {:select-single false})))))

(cmd/command {:command :auto-complete.remove
              :hidden true
              :desc "Editor: Auto complete hide"
              :exec (fn []
                      (when (:active @hinter)
                        (object/raise hinter :escape!))
                      (keyboard/passthrough))})

(cmd/command {:command :auto-complete
              :hidden true
              :desc "Editor: Auto complete"
              :exec (fn []
                      (let [ed (pool/last-active)]
                        (if-not (editor/selection? ed)
                          (object/raise ed :hint)
                          (keyboard/passthrough))))})

(cmd/command {:command :auto-complete.force
              :hidden true
              :desc "Editor: Force auto complete"
              :exec (fn []
                      (let [ed (pool/last-active)]
                        (object/raise ed :hint {:force? true})))})

;;*********************************************************
;; Mode extensions
;;*********************************************************

;; CM5 set per-mode hint-patterns via extendMode; CM6 has no mode registry, so we
;; set :hint-pattern per editor from its mime (get-pattern reads it). The clojure
;; pattern keeps -, >, :, *, $, ?, <, !, +, ., / so tokens like map-indexed / swap!
;; / ->> complete as one word.
(def ^:private clj-hint-pattern #"[\w\-\>\:\*\$\?\<\!\+\.\/]")
(def ^:private mode-hint-patterns
  {"clojure" clj-hint-pattern "clj" clj-hint-pattern "cljs" clj-hint-pattern
   "cljc" clj-hint-pattern "edn" clj-hint-pattern
   "css" #"[\w\.\-\#]"})

(behavior ::set-hint-pattern
          :triggers #{:object.instant}
          :reaction (fn [this]
                      (when-let [p (some-> (-> @this :info :mime) name string/lower-case mode-hint-patterns)]
                        (object/merge! this {:hint-pattern p}))))
