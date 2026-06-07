(ns lt.editor.backend
  "Editor backend behind the lt.objs.editor seam (ADR 0008/0009). CM6 is now the
  ONLY backend — the CM5 deftype + the backend feature-flag were removed once the
  default flipped. The seam's capability fns delegate to this single Cm6Backend
  (value/cursor/selection/line/history). Positions are LightTable edn `{:line :ch}`,
  0-based.

  A closed op set → a protocol (kept as the seam's one capability interface)."
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

(defn cm6-backend [view] (->Cm6Backend view))
