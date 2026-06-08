(ns lt.ext.textmate-test
  "C — TextMate syntax engine (ADR 0011, one of the two walls). Loads the real
  oniguruma WASM + vscode-textmate, builds a Registry over a tiny grammar, and
  tokenizes a line — proving extension-contributed grammars produce scopes (the
  input the CM6 highlight layer will map to theme colors next)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [lt.ext.textmate :as tm]))

(def ^:private toy-grammar
  {:scopeName "source.toy"
   :patterns  [{:name "keyword.control.toy" :match "\\b(def|if|else)\\b"}
               {:name "string.quoted.double.toy" :begin "\"" :end "\""}
               {:name "comment.line.hash.toy" :match "#.*$"}]})

(defn- scopes-str [tok] (str/join " " (:scopes tok)))

(deftest tokenizes-extension-grammar-into-scopes
  (async done
    (-> (tm/load-onig!)
        (.then
         (fn [onig-lib]
           (let [reg (tm/make-registry onig-lib "source.toy"
                                       (tm/parse-grammar (js/JSON.stringify (clj->js toy-grammar))))]
             (.then
              (.loadGrammar reg "source.toy")
              (fn [grammar]
                (let [{:keys [tokens rule-stack]} (tm/tokenize-line grammar "def x = \"hi\" # note" nil)]
                  (is (pos? (count tokens)) "the line tokenized")
                  (is (some #(str/includes? (scopes-str %) "keyword.control.toy") tokens)
                      "`def` got keyword.control.toy from the grammar")
                  (is (some #(str/includes? (scopes-str %) "string.quoted.double.toy") tokens)
                      "the \"hi\" string got string.quoted.double.toy")
                  (is (some #(str/includes? (scopes-str %) "comment.line.hash.toy") tokens)
                      "the # comment got comment.line.hash.toy")
                  (is (some? rule-stack) "a rule stack is returned for multi-line continuation")
                  (done))))))))))
