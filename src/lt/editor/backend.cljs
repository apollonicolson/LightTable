(ns lt.editor.backend
  "Pluggable editor backend behind the lt.objs.editor seam (M5 slice 4, ADR 0008).

  lt.objs.editor's public API is the contract. Its capability fns delegate to an
  IEditorBackend so the SAME seam drives either CM5 (the existing instance) or
  CM6 (an EditorView via lt.editor.cm6). This is branch-by-abstraction: CM5 is
  the default and stays the live editor until CM6 passes the parity suite; the
  flag then flips. Only the migrated capabilities (value/cursor/selection/line/
  history) are on the protocol so far — others stay direct-CM5 (per-capability
  migration). Positions are LightTable edn `{:line :ch}`, 0-based.

  A closed, performance-sensitive op set → a protocol (not multimethods, which
  fit the OPEN behavior set of the BOT dispatch work in lt.object.dispatch)."
  (:require [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.view :as view]))

(defprotocol IEditorBackend
  (-value [b])
  (-set-val [b v])
  (-cursor [b side])
  (-move-cursor [b pos])
  (-line-count [b])
  (-line [b n])
  (-first-line [b])
  (-last-line [b])
  (-replace [b from to v])
  (-selection? [b])
  (-selection [b])
  (-selection-bounds [b])
  (-set-selection [b from to])
  (-replace-selection [b v after])
  (-insert-at-cursor [b v])
  (-get-char [b dir])
  (-undo [b])
  (-redo [b])
  (-generation [b])
  (-dirty? [b gen]))

;; ── CM5 backend — mirrors the existing lt.objs.editor CodeMirror-5 calls ──────
(deftype Cm5Backend [cm]
  IEditorBackend
  (-value [_] (.getValue cm))
  (-set-val [_ v] (.setValue cm (or v "")))
  (-cursor [_ side] (let [p (.getCursor cm side)] {:line (.-line p) :ch (.-ch p)}))
  (-move-cursor [_ pos] (.setCursor cm (clj->js (or pos {:line 0 :ch 0}))))
  (-line-count [_] (.lineCount cm))
  (-line [_ n] (.getLine cm n))
  (-first-line [_] (.firstLine cm))
  (-last-line [_] (.lastLine cm))
  (-replace [_ from to v] (.replaceRange cm v (clj->js from) (clj->js to)))
  (-selection? [_] (.somethingSelected cm))
  (-selection [_] (.getSelection cm))
  (-selection-bounds [this] (when (.somethingSelected cm)
                              {:from (-cursor this "start") :to (-cursor this "end")}))
  (-set-selection [_ from to] (.setSelection cm (clj->js from) (clj->js to)))
  (-replace-selection [_ v after] (.replaceSelection cm v (name (or after :end)) "+input"))
  (-insert-at-cursor [this v] (.replaceRange cm v (clj->js (-cursor this nil))))
  (-get-char [this dir] (let [c (-cursor this nil)
                              a (update c :ch + dir)
                              [from to] (if (> dir 0) [c a] [a c])]
                          (.getRange cm (clj->js from) (clj->js to))))
  (-undo [_] (.undo cm))
  (-redo [_] (.redo cm))
  (-generation [_] (.changeGeneration cm))
  (-dirty? [_ gen] (not (.isClean cm gen))))

;; ── CM6 backend — reads from view.state via cm6; writes dispatch to the view ──
(deftype Cm6Backend [view]
  IEditorBackend
  (-value [_] (cm6/doc-string (view/view-state view)))
  ;; set-val reseeds the doc and isolates history (so a later edit's undo stops
  ;; here) while preserving the view's extensions/option compartments.
  (-set-val [_ v] (view/set-val! view (or v "")))
  (-cursor [_ side] (let [st (view/view-state view)
                          m (.. st -selection -main)
                          off (case side
                                "start"  (.-from m)
                                "end"    (.-to m)
                                "anchor" (.-anchor m)
                                (.-head m))]
                      (cm6/offset->pos st off)))
  (-move-cursor [_ pos] (let [st (view/view-state view)]
                          (view/move-cursor! view (cm6/pos->offset st (or pos {:line 0 :ch 0})))))
  (-line-count [_] (cm6/line-count (view/view-state view)))
  (-line [_ n] (cm6/line-text (view/view-state view) n))
  (-first-line [_] (cm6/first-line (view/view-state view)))
  (-last-line [_] (cm6/last-line (view/view-state view)))
  (-replace [_ from to v] (let [st (view/view-state view)]
                            (view/replace! view (cm6/pos->offset st from) (cm6/pos->offset st to) v)))
  (-selection? [_] (cm6/selection? (view/view-state view)))
  (-selection [_] (cm6/selected-text (view/view-state view)))
  (-selection-bounds [_] (let [st (view/view-state view)]
                           (when (cm6/selection? st) (cm6/selection-bounds st))))
  (-set-selection [_ from to] (let [st (view/view-state view)]
                                (view/set-selection! view (cm6/pos->offset st from) (cm6/pos->offset st to))))
  ;; CM6 supports "end" placement so far; :around/:start fall back to end (CM6 is
  ;; not the live editor yet — extend when those modes are exercised on CM6).
  (-replace-selection [_ v _after] (view/replace-selection! view v))
  (-insert-at-cursor [_ v] (view/insert-at-cursor! view v))
  (-get-char [_ dir] (cm6/get-char (view/view-state view) dir))
  (-undo [_] (view/undo! view))
  (-redo [_] (view/redo! view))
  (-generation [_] (cm6/->generation (view/view-state view)))
  (-dirty? [_ gen] (cm6/dirty? (view/view-state view) gen)))

(defn cm5-backend [cm]   (->Cm5Backend cm))
(defn cm6-backend [view] (->Cm6Backend view))

;; ── the feature flag ─────────────────────────────────────────────────────────
;; Selects the backend for NEWLY created editors. Default :cm5 — CM6 stays a
;; parallel backend until it passes the parity suite, then this flips to :cm6.
(defonce ^:private !kind (atom :cm5))
(defn active-kind [] @!kind)
(defn use-backend! [k] (reset! !kind k))
(defn cm6-active? [] (= :cm6 @!kind))
