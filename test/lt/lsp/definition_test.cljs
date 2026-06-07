(ns lt.lsp.definition-test
  "Falsifier gate for lt.lsp.definition (ADR 0010 slice 4): LSP definition results
  (Location / Location[] / LocationLink[]) resolve to a single jump target, and
  file:// URIs strip to paths."
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.lsp.definition :as d]))

(deftest single-location
  (is (= {:uri "file:///a.clj" :line 4 :character 2}
         (d/definition->location
          {:uri "file:///a.clj"
           :range {:start {:line 4 :character 2} :end {:line 4 :character 8}}}))))

(deftest location-array-takes-first
  (is (= {:uri "file:///a.clj" :line 1 :character 0}
         (d/definition->location
          [{:uri "file:///a.clj" :range {:start {:line 1 :character 0}}}
           {:uri "file:///b.clj" :range {:start {:line 9 :character 9}}}]))))

(deftest location-link
  (is (= {:uri "file:///a.clj" :line 7 :character 3}
         (d/definition->location
          [{:targetUri "file:///a.clj"
            :targetRange {:start {:line 7 :character 3} :end {:line 7 :character 9}}}]))))

(deftest nil-and-empty
  (is (nil? (d/definition->location nil)))
  (is (nil? (d/definition->location [])))
  (is (nil? (d/definition->location {}))))

(deftest uri-to-path
  (is (= "/tmp/a.clj" (d/uri->path "file:///tmp/a.clj")))
  (is (nil? (d/uri->path "http://x")))
  (is (nil? (d/uri->path nil))))
