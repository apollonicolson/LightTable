(ns lt.plugins.watches
  "Provide watch related commands"
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.editor :as ed]
            [lt.objs.editor.pool :as pool])
  (:require-macros [lt.macros :refer [behavior]]))

(defn inline [this opts loc]
  (let [type (or (:type opts) :inline)
        ;; CM6 has no line handles → key by line number (mirrors eval's manager).
        line (if (ed/cm6? this) (:line loc) (ed/line-handle this (:line loc)))
        res-obj (object/create :lt.objs.eval/inline-result {:ed this
                                                            :class (or (:class opts) (name type))
                                                            :opts opts
                                                            :loc loc
                                                            :line line})]
    (object/add-tags res-obj [:inline.watch])
    (object/update! this [:widgets] assoc [line type] res-obj)
    res-obj))

(defn- pos<= [a b]
  (or (< (:line a) (:line b))
      (and (= (:line a) (:line b)) (<= (:ch a) (:ch b)))))

(defn- within? [cur {:keys [from to]}]
  (and from to (pos<= from cur) (pos<= cur to)))

;; src->watch param removed: it was always ignored here (source transformation
;; runs through the :watch.src+ / :watch.custom.src+ raise-reduce below), and its
;; only caller passed a dangling global ref (lt.objs.langs.js/src->watch) to a JS
;; language plugin not bundled in this build.
(defn watched-range [ed start end]
  (let [doc (.Doc js/CodeMirror (ed/->val ed))
        range (when start
                (ed/mark doc start (update-in end [:ch] inc) {:inclusiveLeft true :inclusiveRight true}))
        ;;add watch ranges
        ;; Watch positions come from the backend-aware seam (CM5 marker .find / CM6
        ;; tracked-range); the transform still runs in a headless CM5 Doc, which is
        ;; independent of the editor backend.
        watches (doall (filter identity
                               (for [[id watch] (:watches @ed)
                                     :let [pos (ed/watch-mark-bounds ed (:handle watch))
                                           mark (when pos (ed/mark doc (:from pos) (:to pos) {:className "watched"}))]]
                                 (when mark
                                   (set! (.-custom mark) (:custom watch))
                                   (set! (.-ltwatchid mark) id)
                                   mark))))]
    ;;replace watched ranges with code
    (doseq [watch watches
            :let [pos (.find watch)
                  text (ed/range doc (.-from pos) (.-to pos))
                  meta {:obj (object/->id ed)
                        :id (.-ltwatchid watch)}
                  v (if-not (.-custom watch)
                      (object/raise-reduce ed :watch.src+ text meta text)
                      (object/raise-reduce ed :watch.custom.src+ text meta (.-custom watch) text))]]
      (ed/replace doc (.-from pos) (.-to pos) v))
    (if range
      (let [pos (.find range)]
        (ed/range doc (.-from pos) (.-to pos)))
      (ed/->val doc))))

(behavior ::clear!
          :triggers #{:clear}
          :reaction (fn [inline-watch]
                      (let [ed (-> @inline-watch :ed)
                            id (-> @inline-watch :opts :id)]
                        (when-let [w (-> @ed :watches (get id))]
                          (ed/clear-watch-mark ed (:handle w)))
                        (object/update! ed [:watches] dissoc id)
                        (object/raise ed :unwatch))))

(behavior ::watch!
          :triggers #{:watch!}
          :reaction (fn [this opts]
                      (when-let [sel (ed/selection-bounds this)]
                        (let [id (-> (gensym "watch")
                                     (str))
                              handle (ed/add-watch-mark this (:from sel) (:to sel))
                              res (inline this (merge {:type :watch :id id} opts) (:to sel))]
                          ;; CM5: the marker's "hide" event clears on text-delete + the
                          ;; metadata lives on the marker. CM6: metadata lives in the
                          ;; :watches map; collapse-cleanup is a deferred refinement.
                          (when-not (ed/cm6? this)
                            (.on handle "hide" (fn [] (object/raise res :clear!)))
                            (set! (.-custom handle) (when (:exp opts) opts))
                            (set! (.-lttype handle) :watch)
                            (set! (.-ltwatchid handle) id))
                          (object/update! this [:watches] assoc id {:handle handle
                                                                    :custom (when (:exp opts) opts)
                                                                    :inline-result res})
                          (object/raise this :watch)))))

(behavior ::unwatch!
          :triggers #{:unwatch!}
          :reaction (fn [this]
                      (when-let [cur (ed/->cursor this)]
                        ;; find the watch whose range contains the cursor (replaces the
                        ;; CM5 find-marks-at + .lttype filter; works on both backends).
                        (doseq [[_ w] (:watches @this)
                                :when (within? cur (ed/watch-mark-bounds this (:handle w)))]
                          (object/raise (:inline-result w) :clear!)))))

(behavior ::eval-on-watch-or-unwatch
          :triggers #{:unwatch :watch}
          :reaction (fn [this]
                      (when (ed/selection? this)
                        (let [cursor (ed/->cursor this)]
                          (ed/set-selection this cursor cursor)))
                      (object/raise this :eval.one)))

(cmd/command {:command :editor.watch.watch-selection
              :desc "Editor: Watch selection"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :watch!))
                      )})

(cmd/command {:command :editor.watch.custom-watch-selection
              :desc "Editor: Custom watch selection"
              :hidden true
              :exec (fn [exp opts]
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :watch! (assoc opts :exp exp)))
                      )})


(cmd/command {:command :editor.watch.unwatch
              :desc "Editor: Remove watch under cursor"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :unwatch!))
                      )})

(cmd/command {:command :editor.watch.remove-all
              :desc "Editor: Clear all watches"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (doseq [w (vals (:watches @ed))]
                          (object/raise (:inline-result w) :clear!))))})

