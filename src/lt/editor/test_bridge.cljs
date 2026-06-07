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
            [lt.objs.tabs :as tabs])
  (:require-macros [lt.macros :refer [behavior]]))

(defn- ed [] (pool/last-active))

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
       :backendKind (fn [] (when-let [e (ed)] (name (or (:backend-kind @e) :cm5))))
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
       :lineComment   (fn [] (when-let [e (ed)] (editor/line-comment e nil nil nil)) nil)})

(defn install!
  "Expose the editor seam on window.__lt_test when LT_TEST_BRIDGE is set. No-op
  otherwise. Safe to call once at the end of renderer load."
  []
  (when (and (exists? js/process)
             (.. js/process -env -LT_TEST_BRIDGE))
    (set! (.-__lt_test js/window) (bridge))))
