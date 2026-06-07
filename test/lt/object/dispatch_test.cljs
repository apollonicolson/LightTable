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

(deftest tap-stream-carries-observations
  ;; cljs tap> delivers asynchronously; drain on the next tick before asserting.
  (async done
    (let [events (atom [])
          f      (fn [x] (swap! events conj x))]
      (add-tap f)
      (d/observe-dispatched! {:behavior ::b :trigger :t :time 3})
      (d/observe-error! {:behavior ::b :trigger :t :error :boom})
      (js/setTimeout
        (fn []
          (is (some #(= % [d/dispatched-event {:behavior ::b :trigger :t :time 3}]) @events)
              "dispatched event observed")
          (is (some #(= % [d/error-event {:behavior ::b :trigger :t :error :boom}]) @events)
              "error event observed")
          (remove-tap f)
          (done))
        10))))
