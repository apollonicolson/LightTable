(ns lt.editor.cm6.decorations-test
  "Falsifier gate for lt.editor.cm6.decorations: the load-bearing property is that
  tracked decorations MOVE with edits and report deletion — the CM6 replacement
  for CM5 line-handles/marks. Layers are independent (clearing one leaves another
  intact). All over a pure EditorState (no view render)."
  (:require [clojure.test :refer [deftest is]]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.decorations :as dec]))

(defn- layer [] (dec/make-layer))
(defn- state [L s] (cm6/make-state s #js [(:field L)]))
(defn- apply-fx [st & fx]
  (-> st (.update #js {:effects (into-array fx)}) (.-state)))
(defn- edit [st from to ins]
  (-> st (.update #js {:changes #js {:from from :to to :insert ins}}) (.-state)))

(deftest add-and-track-mark
  (let [L (layer)
        s (-> (state L "hello world")
              (apply-fx ((:add L) (dec/mark :m {:class "x"}) 0 5)))]
    (is (= 1 ((:count L) s)))
    (is (= {:from 0 :to 5} ((:tracked-range L) s :m)) "mark sits where it was added")
    (is (nil? ((:tracked-range L) s :nope)) "unknown id → nil")))

(deftest mark-maps-through-insert
  (let [L (layer)
        s (-> (state L "hello world")
              (apply-fx ((:add L) (dec/mark :m) 0 5))
              (edit 0 0 "XX"))]
    (is (= {:from 2 :to 7} ((:tracked-range L) s :m))
        "inserting before the mark shifts it by the insert length")))

(deftest mark-maps-through-interior-insert
  (let [L (layer)
        s (-> (state L "hello world")
              (apply-fx ((:add L) (dec/mark :m) 0 5))
              (edit 2 2 "ZZ"))]
    (is (= {:from 0 :to 7} ((:tracked-range L) s :m))
        "inserting inside the mark grows it")))

(deftest mark-dropped-when-text-deleted
  (let [L (layer)
        s (-> (state L "hello world")
              (apply-fx ((:add L) (dec/mark :m) 0 5))
              (edit 0 5 ""))]
    (is (nil? ((:tracked-range L) s :m))
        "deleting the whole marked range drops the mark — the deletion signal")
    (is (= 0 ((:count L) s)))))

(deftest remove-by-id
  (let [L (layer)
        s (-> (state L "hello world")
              (apply-fx ((:add L) (dec/mark :a) 0 2)
                        ((:add L) (dec/mark :b) 6 9))
              (apply-fx ((:remove-by-id L) :a)))]
    (is (nil? ((:tracked-range L) s :a)) "removed")
    (is (= {:from 6 :to 9} ((:tracked-range L) s :b)) "the other survives")
    (is (= 1 ((:count L) s)))))

(deftest clear-drops-all
  (let [L (layer)
        s (-> (state L "hello world")
              (apply-fx ((:add L) (dec/mark :a) 0 2)
                        ((:add L) (dec/mark :b) 6 9))
              (apply-fx ((:clear L))))]
    (is (= 0 ((:count L) s)))))

(deftest layers-are-independent
  ;; two layers in one state: clearing one must not touch the other.
  (let [A (layer) B (layer)
        s (-> (cm6/make-state "hello world" #js [(:field A) (:field B)])
              (apply-fx ((:add A) (dec/mark :a) 0 2))
              (apply-fx ((:add B) (dec/mark :b) 6 9))
              (apply-fx ((:clear A))))]
    (is (= 0 ((:count A) s)) "layer A cleared")
    (is (= {:from 6 :to 9} ((:tracked-range B) s :b)) "layer B untouched")))

(deftest widget-point-maps
  (let [L (layer)
        el (.createElement js/document "span")
        s (-> (state L "hello world")
              (apply-fx ((:add L) (dec/widget :w el {:side 1}) 6))
              (edit 0 0 "XX"))]
    (is (= {:from 8 :to 8} ((:tracked-range L) s :w))
        "a point widget tracks its offset through an insert before it")))
