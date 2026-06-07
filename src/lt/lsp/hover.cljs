(ns lt.lsp.hover
  "Pure mapping: an LSP Hover result → display text (ADR 0010 slice 4). Handles
  every contents shape the spec allows — MarkupContent {:kind :value}, a
  MarkedString (string or {:language :value}), an array of those, or a bare
  string. Node-loadable + node-tested; the tooltip rendering is editor-coupled."
  (:require [clojure.string :as str]))

(defn- marked->text [m]
  (cond
    (string? m) m
    (map? m)    (or (:value m) "")   ; MarkupContent or {:language :value}
    :else       ""))

(defn hover->text
  "The plain text of an LSP hover result, or nil if there's nothing to show."
  [result]
  (when result
    (let [c (:contents result)
          s (cond
              (nil? c)         nil
              (string? c)      c
              (sequential? c)  (->> c (map marked->text) (remove str/blank?) (str/join "\n\n"))
              (map? c)         (marked->text c)
              :else            nil)]
      (when-not (str/blank? s) s))))
