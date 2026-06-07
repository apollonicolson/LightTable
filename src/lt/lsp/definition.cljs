(ns lt.lsp.definition
  "Pure mapping: an LSP definition result → a single jump target (ADR 0010 slice
  4). Handles Location, Location[], and LocationLink[] (and null). Node-loadable
  + node-tested; the navigation (open file at line) is editor-coupled."
  (:require [clojure.string :as str]))

(defn- ->loc
  "Normalize one Location or LocationLink to {:uri :line :character} (range start)."
  [m]
  (let [uri   (or (:uri m) (:targetUri m))
        range (or (:range m) (:targetRange m) (:targetSelectionRange m))
        start (:start range)]
    (when (and uri start)
      {:uri uri :line (:line start) :character (:character start)})))

(defn definition->location
  "First jump target of an LSP definition result, or nil if there's none."
  [result]
  (cond
    (nil? result)        nil
    (sequential? result) (some ->loc result)
    (map? result)        (->loc result)
    :else                nil))

(defn uri->path
  "Strip a file:// URI to a filesystem path (Linux/posix)."
  [uri]
  (when (and uri (str/starts-with? uri "file://"))
    (subs uri (count "file://"))))
