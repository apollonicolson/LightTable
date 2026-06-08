(ns lt.ext.host
  "Phase 1 extension-host skeleton (ADR 0011): read a manifest, build an
  ExtensionContext, load the extension's `main` module, and run activate/deactivate.

  In-renderer/node for now — this de-risks the API shape. The born-sandboxed,
  separate-process host (and routing module-load + fs through the capability
  broker) is a later phase; the structure here is what moves behind the RPC
  membrane unchanged."
  (:require [lt.ext.manifest :as manifest]
            [lt.ext.activation :as activation]))

(def ^:private fs   (js/require "fs"))
(def ^:private path (js/require "path"))

(defn read-manifest
  "Read + parse `dir`/package.json into a descriptor with `:dir` attached."
  [dir]
  (let [p   (.join path dir "package.json")
        txt (.readFileSync fs p "utf8")
        m   (js->clj (.parse js/JSON txt) :keywordize-keys true)]
    (assoc (manifest/parse m) :dir dir)))

(defn- memento []
  (let [store (atom {})]
    #js {:get    (fn [k d] (get @store (keyword k) d))
         :update (fn [k v] (swap! store assoc (keyword k) v) (.resolve js/Promise))
         :keys   (fn [] (clj->js (map name (keys @store))))}))

(defn- make-context
  "The ExtensionContext passed to activate(). Minimal but real: subscriptions
  (disposed on deactivate), extensionPath, asAbsolutePath, global/workspace state."
  [desc]
  #js {:subscriptions   #js []
       :extensionPath   (:dir desc)
       :asAbsolutePath  (fn [rel] (.resolve path (:dir desc) rel))
       :globalState     (memento)
       :workspaceState  (memento)})

(defn activate!
  "Load the extension's main module and call `activate(context)`. Returns an active
  record `{:desc :module :context :api :active? true}`."
  [desc]
  (let [main-path (.resolve path (:dir desc) (:main desc))
        mod       (js/require main-path)
        ctx       (make-context desc)
        api       (when (fn? (.-activate mod)) ((.-activate mod) ctx))]
    {:desc desc :module mod :context ctx :api api :active? true}))

(defn deactivate!
  "Dispose the context's subscriptions and call `deactivate()` if present."
  [active]
  (let [mod (:module active)
        ctx (:context active)]
    (doseq [d (array-seq (.-subscriptions ctx))]
      (when (and d (fn? (.-dispose d))) (.dispose d)))
    (when (fn? (.-deactivate mod)) ((.-deactivate mod)))
    (assoc active :active? false)))

(defn should-activate?
  "True if `desc` should activate for `trigger` (see lt.ext.activation)."
  [desc trigger]
  (activation/activates? (:activation-events desc) trigger))
