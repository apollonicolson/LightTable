(ns lt.editor.cm6.decorations-test
  "Falsifier gate for lt.editor.cm6.decorations: the load-bearing property is that
  tracked decorations MOVE with edits and report deletion — the CM6 replacement
  for CM5 line-handles/marks. All over a pure EditorState (no view render)."
  (:require [clojure.test :refer [deftest is]]
            [lt.editor.cm6 :as cm6]
            [lt.editor.cm6.decorations :as dec]))

(defn- state [s] (cm6/make-state s #js [dec/deco-field]))
(defn- apply-fx [st & fx]
  (-> st (.update #js {:effects (into-array fx)}) (.-state)))
(defn- edit [st from to ins]
  (-> st (.update #js {:changes #js {:from from :to to :insert ins}}) (.-state)))

(deftest add-and-track-mark
  (let [s (-> (state "hello world")
              (apply-fx (dec/add (dec/mark :m {:class "x"}) 0 5)))]
    (is (= 1 (dec/decoration-count s)))
    (is (= {:from 0 :to 5} (dec/tracked-range s :m)) "mark sits where it was added")
    (is (nil? (dec/tracked-range s :nope)) "unknown id → nil")))

(deftest mark-maps-through-insert
  (let [s (-> (state "hello world")
              (apply-fx (dec/add (dec/mark :m) 0 5))
              (edit 0 0 "XX"))]
    (is (= {:from 2 :to 7} (dec/tracked-range s :m))
        "inserting before the mark shifts it by the insert length")))

(deftest mark-maps-through-interior-insert
  (let [s (-> (state "hello world")
              (apply-fx (dec/add (dec/mark :m) 0 5))
              (edit 2 2 "ZZ"))]
    (is (= {:from 0 :to 7} (dec/tracked-range s :m))
        "inserting inside the mark grows it")))

(deftest mark-dropped-when-text-deleted
  (let [s (-> (state "hello world")
              (apply-fx (dec/add (dec/mark :m) 0 5))
              (edit 0 5 ""))]
    (is (nil? (dec/tracked-range s :m))
        "deleting the whole marked range drops the mark — the deletion signal")
    (is (= 0 (dec/decoration-count s)))))

(deftest remove-by-id
  (let [s (-> (state "hello world")
              (apply-fx (dec/add (dec/mark :a) 0 2)
                        (dec/add (dec/mark :b) 6 9))
              (apply-fx (dec/remove-by-id :a)))]
    (is (nil? (dec/tracked-range s :a)) "removed")
    (is (= {:from 6 :to 9} (dec/tracked-range s :b)) "the other survives")
    (is (= 1 (dec/decoration-count s)))))

(deftest clear-drops-all
  (let [s (-> (state "hello world")
              (apply-fx (dec/add (dec/mark :a) 0 2)
                        (dec/add (dec/mark :b) 6 9))
              (apply-fx (dec/clear)))]
    (is (= 0 (dec/decoration-count s)))))

(deftest widget-point-maps
  (let [el (.createElement js/document "span")
        s (-> (state "hello world")
              (apply-fx (dec/add (dec/widget :w el {:side 1}) 6))
              (edit 0 0 "XX"))]
    (is (= {:from 8 :to 8} (dec/tracked-range s :w))
        "a point widget tracks its offset through an insert before it")))
