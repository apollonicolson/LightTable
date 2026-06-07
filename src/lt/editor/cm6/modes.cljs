(ns lt.editor.cm6.modes
  "CM6 syntax via Lezer (M5 — the foundational modes slice).

  CM5 had a per-language mode SYSTEM (string mode names + the deploy/plugins
  language plugins). CM6 replaces it with Lezer LanguageSupport packages +
  syntaxHighlighting. This module wires the highlighting and a reconfigurable
  language Compartment, and maps a few mode/mime names to their CM6 language.

  Two sources: official Lezer LanguageSupport packages where they exist
  (javascript/json — better grammars), and @codemirror/legacy-modes wrapped via
  StreamLanguage for the long tail (clojure et al.) — the bridge that reuses the
  CM5-era stream modes for broad coverage without per-language Lezer grammars.
  Unknown modes resolve to no language (plain text), never an error.

  The registry is the single extension point; the CM5 mode-blacklist/plugin story
  (which modes deferred to language plugins) is the remaining mode work."
  (:require [clojure.string :as string]
            ["@codemirror/state" :as cm-state]
            ["@codemirror/language" :as cm-lang]
            ["@codemirror/lang-javascript" :as lang-js]
            ["@codemirror/lang-json" :as lang-json]
            ["@codemirror/legacy-modes/mode/clojure" :as lm-clojure]
            ["@codemirror/legacy-modes/mode/python" :as lm-python]
            ["@codemirror/legacy-modes/mode/ruby" :as lm-ruby]
            ["@codemirror/legacy-modes/mode/css" :as lm-css]
            ["@codemirror/legacy-modes/mode/xml" :as lm-xml]
            ["@codemirror/legacy-modes/mode/shell" :as lm-shell]
            ["@codemirror/legacy-modes/mode/sql" :as lm-sql]))

(def ^:private Compartment (.-Compartment cm-state))
(def ^:private StreamLanguage (.-StreamLanguage cm-lang))
(def ^:private javascript (.-javascript lang-js))
(def ^:private json (.-json lang-json))

(defn- legacy
  "Wrap a @codemirror/legacy-modes StreamParser as a CM6 Language."
  [parser]
  (.define StreamLanguage parser))

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
   "json"       #(json)
   ;; legacy-modes bridge (StreamLanguage) — the long tail
   "clojure"    #(legacy (.-clojure lm-clojure))
   "clj"        #(legacy (.-clojure lm-clojure))
   "cljs"       #(legacy (.-clojure lm-clojure))
   "cljc"       #(legacy (.-clojure lm-clojure))
   "edn"        #(legacy (.-clojure lm-clojure))
   "python"     #(legacy (.-python lm-python))
   "py"         #(legacy (.-python lm-python))
   "ruby"       #(legacy (.-ruby lm-ruby))
   "rb"         #(legacy (.-ruby lm-ruby))
   "css"        #(legacy (.-css lm-css))
   "xml"        #(legacy (.-xml lm-xml))
   "html"       #(legacy (.-html lm-xml))
   "shell"      #(legacy (.-shell lm-shell))
   "bash"       #(legacy (.-shell lm-shell))
   "sql"        #(legacy (.-standardSQL lm-sql))})

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
