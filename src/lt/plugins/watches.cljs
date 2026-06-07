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
        line (:line loc)
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
;; Pure offset transform (no headless CM5 Doc): take the editor text, and within
;; the requested extent [start,end] (or the whole doc), wrap each watch's text via
;; the :watch.src+ / :watch.custom.src+ raise-reduce. Watches are stitched in
;; document order from the original string, so positions never need re-tracking.
(defn watched-range [ed start end]
  (let [full (ed/->val ed)
        s (if start (ed/pos->index ed start) 0)
        e (if start (inc (ed/pos->index ed end)) (count full))
        watches (->> (:watches @ed)
                     (keep (fn [[id watch]]
                             (when-let [b (ed/watch-mark-bounds ed (:handle watch))]
                               (let [wf (ed/pos->index ed (:from b))
                                     wt (ed/pos->index ed (:to b))]
                                 (when (and (>= wf s) (<= wt e))
                                   {:from wf :to wt :custom (:custom watch) :id id})))))
                     (sort-by :from))]
    (loop [pos s, ws watches, acc ""]
      (if-let [{:keys [from to custom id]} (first ws)]
        (let [text (subs full from to)
              meta {:obj (object/->id ed) :id id}
              v (if-not custom
                  (object/raise-reduce ed :watch.src+ text meta text)
                  (object/raise-reduce ed :watch.custom.src+ text meta custom text))]
          (recur to (rest ws) (str acc (subs full pos from) v)))
        (str acc (subs full pos e))))))

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
                          ;; CM6: watch metadata lives in the :watches map (not on the
                          ;; marker); the highlight decoration auto-tracks. Collapse-
                          ;; cleanup on text-delete is a deferred refinement.
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

