(ns lt.editor.cm6.modes
  "CM6 syntax via Lezer (M5 — the foundational modes slice).

  CM5 had a per-language mode SYSTEM (string mode names + the deploy/plugins
  language plugins). CM6 replaces it with Lezer LanguageSupport packages +
  syntaxHighlighting. This module wires the highlighting and a reconfigurable
  language Compartment, and maps a few mode/mime names to their CM6 language.

  FOUNDATIONAL ONLY: javascript/typescript/jsx + json so far. The full language
  set (clojure et al.) + the CM5 mode-blacklist/plugin story is the remaining
  modes work. Unknown modes resolve to no language (plain text), never an error."
  (:require [clojure.string :as string]
            ["@codemirror/state" :as cm-state]
            ["@codemirror/language" :as cm-lang]
            ["@codemirror/lang-javascript" :as lang-js]
            ["@codemirror/lang-json" :as lang-json]))

(def ^:private Compartment (.-Compartment cm-state))
(def ^:private javascript (.-javascript lang-js))
(def ^:private json (.-json lang-json))

(def syntax-highlighting
  "Generic highlighting extension — paints whatever language the compartment
  holds. Goes in the base extensions."
  (.syntaxHighlighting cm-lang (.-defaultHighlightStyle cm-lang)))

;; mode/mime name (lower-cased) -> thunk producing a CM6 LanguageSupport.
(def ^:private registry
  {"javascript" #(javascript)
   "js"         #(javascript)
   "jsx"        #(javascript #js {:jsx true})
   "typescript" #(javascript #js {:typescript true})
   "ts"         #(javascript #js {:typescript true})
   "tsx"        #(javascript #js {:typescript true :jsx true})
   "json"       #(json)})

(defn supports?
  "Does CM6 have a language for `mode` yet?"
  [mode]
  (contains? registry (some-> mode name string/lower-case)))

(defn- ext-for [mode]
  (if-let [f (registry (some-> mode name string/lower-case))]
    (f)
    #js []))

(defn make-compartment [] (Compartment.))

(defn initial
  "Seed the language compartment for an editor opened with `mode` (may be nil)."
  [compartment mode]
  (.of compartment (ext-for mode)))

(defn set-mode!
  "Reconfigure the live `view`'s language compartment to `mode`. Returns the view."
  [view compartment mode]
  (.dispatch view #js {:effects (.reconfigure compartment (ext-for mode))})
  view)
