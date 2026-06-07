(ns lt.objs.document
  "Document object — file content + metadata (ADR 0009). CM5 wrapped a
  CodeMirror.Doc here; CM6 keeps the content in the EditorView, so a document is
  now just the manager's record: the initial `:content` (which a CM6 editor seeds
  from), the path/mtime/mime, and the sub-doc/close bookkeeping. Editing and save
  flow through the editor backend, not the document."
  (:require [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.popup :as popup])
  (:require-macros [lt.macros :refer [behavior defui]])
  (:refer-clojure :exclude [replace]))


;;***************************************************
;; Document
;;***************************************************

(def doc-keys [:line-ending :mime])

(object/object* ::document
                :sub-docs #{::this}
                :tags #{:document}
                :init (fn [this info]
                        ;; :content is RESERVED by lt.object (it becomes the object's
                        ;; rendered DOM = the :init return). Store the document text
                        ;; under :text so the CM6 editor can seed from it.
                        (object/merge! this (-> info
                                                (dissoc :content)
                                                (assoc :text (:content info))))
                        nil))


(behavior ::close-document-on-editor-close
          :for #{:editor}
          :triggers #{:closed}
          :reaction (fn [editor]
                      (when-let [doc (:doc @editor)]
                        (object/raise doc :close.force))))

(behavior ::close-linked-document
          :for #{:document}
          :triggers #{:close.force}
          :reaction (fn [this]
                      (when-let [root (:root @this)]
                        (object/update! root [:sub-docs] disj this))
                      (object/destroy! this)))

(behavior ::try-close-root-document
          :for #{:document}
          :triggers #{:try-close}
          :reaction (fn [this]
                      (when (= #{::this} (:sub-docs @this))
                        (object/raise this :close.force))))

(behavior ::close-root-document
          :for #{:document}
          :triggers #{:close.force}
          :reaction (fn [this]
                      (if (and (= #{::this} (:sub-docs @this))
                               (not (object/has-tag? this :document.linked)))
                        (object/destroy! this)
                        (object/update! this [:sub-docs] disj ::this))))

(def default-linked-doc-options {})

(behavior ::set-linked-doc-options
          :triggers #{:object.instant}
          :type :user
          :exclusive true
          :desc "Doc: Set default options for new linked docs"
          :reaction (fn [this opts]
                      (set! default-linked-doc-options opts)))

(defn create [info]
  (object/create ::document info))

(defn create-sub
  ([doc] (create-sub doc nil))
  ([doc info]
   ;; CM5 shared one Doc via linkedDoc (split views edited together). CM6 has no
   ;; detachable shared Doc — a sub-doc is an INDEPENDENT copy of the content;
   ;; live split-view sync is the deferred lt.editor.cm6.document integration.
   (let [info (merge default-linked-doc-options info)
         neue (create (merge (select-keys @doc doc-keys)
                             {:content (:text @doc)}
                             info
                             {:root doc}))]
     (object/add-tags neue [:document.linked])
     (object/update! doc [:sub-docs] conj neue))))

;; ->snapshot / latest-snapshot? / ->val / set-val / replace (all CM5-Doc based,
;; no callers) removed with CM5 — the editor backend is the source of truth.

;;***************************************************
;; Manager
;;***************************************************

(declare manager)

(defn register-doc [doc path]
  (object/update! manager [:files] assoc path doc))

(defn open [path cb]
  (files/open path (fn [data]
                     (let [d (create {:content (:content data)
                                      :line-ending (:line-ending data)
                                      :mtime (files/stats path)
                                      :mime (:type data)})]
                       (register-doc d path)
                       (when cb
                         (cb d)))))
  )

(defn linked-open [ed ldoc-options path cb]
  (create-sub (:doc @ed) ldoc-options)
  (files/open path (fn [data]
                     (let [d (-> @ed :doc deref :sub-docs last)]
                       (when cb
                         (cb d))))))

(defn check-mtime [prev updated]
  (if (and prev updated)
    (= (.getTime (.-mtime prev)) (.getTime (.-mtime updated)))
    true))

(defui button [label & [cb]]
       [:div.button.right label]
       :click (fn []
                (when cb
                  (cb))))

(defn overwrite-warn [cb]
  (popup/popup! {:header "This file was modified."
                 :body [:p "It looks like this file was modified outside of Light Table and saving
                  would overwrite those changes. Do you want to overwrite or cancel?"]
                 :buttons [{:label "Overwrite file"
                            :action cb}
                           {:label "Cancel"}]}))

(defn path->doc [path]
  (-> @manager :files (get path)))

(defn ->stats [path]
  (-> (path->doc path) deref :mtime))

(defn update-stats [path]
  (object/merge! (get-in @manager [:files path]) {:mtime (files/stats path)}))

(defn move-doc [old neue]
  (when-let [old-d (path->doc old)]
    (object/update! manager [:files] assoc neue old-d)
    (object/update! manager [:files] dissoc old)
    (update-stats neue)))

(defn save* [path content cb]
  (files/save path content (fn [data]
                             (update-stats path)
                             (when cb
                             	(cb data)))))

(defn save [path content cb]
  (let [updated (files/stats path)
        safe? (check-mtime (->stats path) updated)]
    (if-not safe?
      (overwrite-warn #(save* path content cb))
      (save* path content cb))))


(object/object* ::doc-manager
                :triggers []
                :behaviors []
                :files {}
                :init (fn []
                        ))

(def manager (object/create ::doc-manager))
