(ns lt.object.dispatch-test
  "Behavior gate for BOT's open invocation + tap observation (lt.object.dispatch).
  Pins: the default :dispatch is synchronous apply (identical to historical BOT);
  a behavior's :dispatch key opens an alternate invocation strategy via defmethod;
  observation flows on the tap> stream."
  (:require [clojure.test :refer [deftest is testing async]]
            [lt.object.dispatch :as d]))

(deftest default-dispatch-applies-reaction-with-obj-and-args
  (let [seen (atom nil)
        beh  {:name ::echo
              :reaction (fn [obj a b] (reset! seen [obj a b]) :done)}]
    (is (= :done (d/invoke beh :the-obj '(1 2)))
        "default invoke returns the reaction's value")
    (is (= [:the-obj 1 2] @seen)
        "reaction receives obj then the spread args")))

;; Open extension: a behavior can select an alternate invocation strategy via its
;; :dispatch key. Register a strategy that records instead of applying.
(defmethod d/invoke ::record [beh _obj _args]
  (reset! (:sink beh) (:name beh))
  :recorded)

(deftest dispatch-key-routes-to-registered-defmethod
  (let [sink (atom nil)
        beh  {:name ::noop :dispatch ::record :sink sink
              :reaction (fn [_obj] (throw (js/Error. "should not run")))}]
    (is (= :recorded (d/invoke beh :obj '()))
        ":dispatch key selects the alternate strategy")
    (is (= ::noop @sink) "alternate strategy ran instead of :reaction")))

(deftest dispatch-observation-is-opt-in
  ;; default off → no event even with a subscriber attached
  (async done
    (let [events (atom [])
          f      (fn [x] (swap! events conj x))]
      (add-tap f)
      (d/observe! false)
      (d/observe-dispatched! ::b :t 1)            ; off → no-op, no tap
      (js/setTimeout
        (fn []
          (is (empty? (filter #(= (first %) d/dispatched-event) @events))
              "no dispatched event emitted while observation is off")
          (remove-tap f)
          (done))
        10))))

(deftest tap-stream-carries-observations-when-enabled
  ;; cljs tap> delivers asynchronously; drain on the next tick before asserting.
  (async done
    (let [events (atom [])
          f      (fn [x] (swap! events conj x))]
      (add-tap f)
      (d/observe! true)
      (d/observe-dispatched! ::b :t 3)
      (d/observe-error! ::b :t :boom)             ; errors always emit
      (js/setTimeout
        (fn []
          (is (some #(= % [d/dispatched-event {:behavior ::b :trigger :t :time 3}]) @events)
              "dispatched event observed when enabled")
          (is (some #(= % [d/error-event {:behavior ::b :trigger :t :error :boom}]) @events)
              "error event observed")
          (d/observe! false)
          (remove-tap f)
          (done))
        10))))
