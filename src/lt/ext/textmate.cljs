(ns lt.ext.textmate
  "TextMate grammar tokenization for extension-contributed syntaxes (ADR 0011 — one
  of the 'two walls'). Wraps vscode-textmate + vscode-oniguruma (the SAME engine
  VSCode/Shiki use): load the oniguruma WASM once, build a Registry over a grammar
  source, tokenize lines → scopes. The scope→CM6-highlight mapping (decorations +
  theme) is the next slice (5-tm-b); this is the engine core, node-gated."
  (:require ["vscode-textmate" :as tm]
            ["vscode-oniguruma" :as onig]
            [clojure.string :as str]
            [lt.util.broker :as broker]))

(defonce ^:private onig-ready (atom nil))

(defn load-onig!
  "Load the oniguruma WASM once (idempotent). Returns a Promise of the onigLib
  ({createOnigScanner createOnigString}) the Registry needs."
  []
  (or @onig-ready
      (let [wasm (.readFileSync broker/fs
                                (.join broker/path (.cwd js/process)
                                       "node_modules" "vscode-oniguruma" "release" "onig.wasm"))
            p    (-> (onig/loadWASM #js {:data wasm})
                     (.then (fn [_] #js {:createOnigScanner onig/createOnigScanner
                                         :createOnigString  onig/createOnigString})))]
        (reset! onig-ready p)
        p)))

(defn parse-grammar
  "Parse a .tmLanguage JSON string into an IRawGrammar."
  [json-string]
  (tm/parseRawGrammar json-string "grammar.json"))

(defn make-registry
  "A Registry resolving a single grammar `scope-name` → `grammar` (an IRawGrammar)."
  [onig-lib scope-name grammar]
  (tm/Registry. #js {:onigLib     (.resolve js/Promise onig-lib)
                     :loadGrammar (fn [scope] (.resolve js/Promise (when (= scope scope-name) grammar)))}))

(defn tokenize-line
  "grammar.tokenizeLine(text, ruleStack) → {:tokens [{:start :end :scopes}] :rule-stack}.
  Pass the returned :rule-stack as the next line's `rule-stack` for multi-line state."
  [^js grammar text rule-stack]
  (let [^js r (.tokenizeLine grammar text (or rule-stack tm/INITIAL))]
    {:tokens     (mapv (fn [^js t] {:start  (.-startIndex t)
                                    :end    (.-endIndex t)
                                    :scopes (vec (array-seq (.-scopes t)))})
                       (array-seq (.-tokens r)))
     :rule-stack (.-ruleStack r)}))

;; ── scope → highlight class (the bridge to CM6 rendering) ─────────────────────
;; TextMate scopes are dotted + most-specific-last; map by longest matching prefix
;; (specific → general) to a stable `tok-*` class the editor theme styles.
(def ^:private scope-classes
  [["comment"               "tok-comment"]
   ["string"                "tok-string"]
   ["constant.numeric"      "tok-number"]
   ["constant.character"    "tok-string"]
   ["constant.language"     "tok-constant"]
   ["constant"              "tok-constant"]
   ["keyword.operator"      "tok-operator"]
   ["keyword"               "tok-keyword"]
   ["storage"               "tok-keyword"]
   ["entity.name.function"  "tok-function"]
   ["entity.name.type"      "tok-type"]
   ["entity.name.tag"       "tok-tag"]
   ["entity.name"           "tok-name"]
   ["support.function"      "tok-function"]
   ["support.type"          "tok-type"]
   ["variable.parameter"    "tok-parameter"]
   ["variable"              "tok-variable"]
   ["punctuation"           "tok-punctuation"]])

(defn scope->class
  "A token's TextMate scope stack → a highlight class (most-specific scope wins).
  nil for unrecognized/plain text."
  [scopes]
  (some (fn [scope]
          (some (fn [[prefix cls]] (when (str/starts-with? scope prefix) cls)) scope-classes))
        (reverse scopes)))

(defn line-spans
  "Tokenized-line `:tokens` → [{:start :end :class}] highlight spans (line-relative
  columns; unclassified tokens dropped). The CM6 highlight layer offsets these by the
  line start and emits mark decorations."
  [tokens]
  (vec (keep (fn [t] (when-let [c (scope->class (:scopes t))]
                       {:start (:start t) :end (:end t) :class c}))
             tokens)))
