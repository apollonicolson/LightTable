(ns lt.ext.activation
  "Phase 1 of the VSCode extension host (ADR 0011): match an extension's
  `activationEvents` against a runtime trigger to decide lazy activation. Pure +
  node-tested. Covers the common events (`*`, onStartupFinished, onLanguage,
  onCommand, workspaceContains); the long tail (onView/onUri/onDebug/onNotebook…)
  is added as target extensions need it.

  A trigger is `{:kind :startup}`, `{:kind :language :value \"clojure\"}`,
  `{:kind :command :value \"x.y\"}`, or `{:kind :workspace-file :paths [...]}`."
  (:require [clojure.string :as str]))

(defn- parse-event [s]
  (if (= s "*")
    {:kind :star}
    (let [i (.indexOf s ":")]
      (if (neg? i)
        {:kind (keyword s)}
        {:kind (keyword (subs s 0 i)) :arg (subs s (inc i))}))))

(defn- glob->re
  "Tiny glob → regexp for workspaceContains: strips a leading `**/`, escapes regex
  specials except `*`, maps `*` → `[^/]*`, and suffix-matches a path segment."
  [glob]
  (let [tail    (str/replace glob #"^\*\*/" "")
        escaped (str/replace tail #"[.+^$(){}|\[\]\\]" (fn [m] (str "\\" m)))
        re      (str/replace escaped "*" "[^/]*")]
    (re-pattern (str "(?:^|/)" re "$"))))

(defn- event-matches? [event trigger]
  (case (:kind event)
    :star              (= (:kind trigger) :startup)
    :onStartupFinished (= (:kind trigger) :startup)
    :onLanguage        (and (= (:kind trigger) :language) (= (:arg event) (:value trigger)))
    :onCommand         (and (= (:kind trigger) :command)  (= (:arg event) (:value trigger)))
    :workspaceContains (and (= (:kind trigger) :workspace-file)
                            (boolean (some #(re-find (glob->re (:arg event)) %)
                                           (:paths trigger))))
    false))

(defn activates?
  "True if any of `activation-events` matches `trigger`."
  [activation-events trigger]
  (boolean (some #(event-matches? (parse-event %) trigger) activation-events)))
