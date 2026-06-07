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
(def ^:private LanguageSupport (.-LanguageSupport cm-lang))
(def ^:private javascript (.-javascript lang-js))
(def ^:private json (.-json lang-json))

(defn- legacy
  "Wrap a @codemirror/legacy-modes StreamParser as a CM6 Language. With `data` (a
  CLJS map of languageData, e.g. {:commentTokens {:line \";;\"}}) it returns a
  LanguageSupport carrying that data — CM6 reads comment tokens (and more) from
  languageData, which a bare StreamLanguage does NOT define, so toggleComment
  would otherwise no-op on these modes."
  ([parser] (.define StreamLanguage parser))
  ([parser data]
   (let [lang (.define StreamLanguage parser)]
     (LanguageSupport. lang #js [(.of (.-data lang) (clj->js data))]))))

(def syntax-highlighting
  "Generic highlighting extension — paints whatever language the compartment
  holds. Goes in the base extensions."
  (.syntaxHighlighting cm-lang (.-defaultHighlightStyle cm-lang)))

;; Clojure-family line comment (";;" matches LightTable/clojuregist convention).
(def ^:private clj-comments {:commentTokens {:line ";;"}})

;; mode/mime name (lower-cased) -> thunk producing a CM6 LanguageSupport.
(def ^:private registry
  {"javascript" #(javascript)
   "js"         #(javascript)
   "jsx"        #(javascript #js {:jsx true})
   "typescript" #(javascript #js {:typescript true})
   "ts"         #(javascript #js {:typescript true})
   "tsx"        #(javascript #js {:typescript true :jsx true})
   "json"       #(json)
   ;; legacy-modes bridge (StreamLanguage) — the long tail. commentTokens attached
   ;; so toggleComment works (the legacy parsers don't carry languageData).
   "clojure"    #(legacy (.-clojure lm-clojure) clj-comments)
   "clj"        #(legacy (.-clojure lm-clojure) clj-comments)
   "cljs"       #(legacy (.-clojure lm-clojure) clj-comments)
   "cljc"       #(legacy (.-clojure lm-clojure) clj-comments)
   "edn"        #(legacy (.-clojure lm-clojure) clj-comments)
   "python"     #(legacy (.-python lm-python) {:commentTokens {:line "#"}})
   "py"         #(legacy (.-python lm-python) {:commentTokens {:line "#"}})
   "ruby"       #(legacy (.-ruby lm-ruby) {:commentTokens {:line "#"}})
   "rb"         #(legacy (.-ruby lm-ruby) {:commentTokens {:line "#"}})
   "css"        #(legacy (.-css lm-css) {:commentTokens {:block {:open "/*" :close "*/"}}})
   "xml"        #(legacy (.-xml lm-xml) {:commentTokens {:block {:open "<!--" :close "-->"}}})
   "html"       #(legacy (.-html lm-xml) {:commentTokens {:block {:open "<!--" :close "-->"}}})
   "shell"      #(legacy (.-shell lm-shell) {:commentTokens {:line "#"}})
   "bash"       #(legacy (.-shell lm-shell) {:commentTokens {:line "#"}})
   "sql"        #(legacy (.-standardSQL lm-sql) {:commentTokens {:line "--"}})})

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
