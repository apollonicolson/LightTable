(ns lt.objs.deploy
  "Provide behaviors to check for app updates and fns for downloading
  and unpacking downloaded assets"
  (:require [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.notifos :as notifos]
            [lt.util.load :as load]
            [clojure.string :as string])
  (:require-macros [lt.macros :refer [behavior defui]]))

(def fs (js/require "fs"))
(def zlib (js/require "zlib"))
(def tar (load/node-module "tar"))
(def home-path (files/lt-home ""))
(def request-strict-ssl true)

(def version-regex #"^\d+\.\d+\.\d+(-.*)?$")

(defn get-versions []
  (let [vstr (:content (files/open-sync (files/lt-home "core/version.json")))]
    (js->clj (.parse js/JSON vstr) :keywordize-keys true)))

(def version-timeout (* 60 60 1000))
(def version (get-versions))

(defn str->version [s]
  (let [[major minor patch] (string/split s ".")]
    {:major (js/parseInt major)
     :minor (js/parseInt minor)
     :patch (js/parseInt patch)}))

(defn compare-versions [v1 v2]
  (if (= v1 v2)
    false
    (not (or (< (:major v2) (:major v1))
             (and (= (:major v2) (:major v1))
                  (< (:minor v2) (:minor v1)))
             (and (= (:major v2) (:major v1))
                  (= (:minor v2) (:minor v1))
                  (< (:patch v2) (:patch v1)))))))

(defn is-newer?
  "Returns true if second version is newer/greater than first version."
  [v1 v2]
  (compare-versions (str->version v1) (str->version v2)))

(defn download-file [from to cb]
  ;; TODO strictSSL: native fetch has no per-request rejectUnauthorized.
  ;; Honoring request-strict-ssl=false would require an undici Agent with a
  ;; custom connect option; using default TLS (strict) for now.
  ;; TODO proxy: http_proxy/https_proxy env vars are not honored here; native
  ;; fetch needs an undici ProxyAgent to route through a proxy.
  (let [out (.createWriteStream fs to)
        headers (js-obj "User-Agent" "Light Table")]
    (-> (js/fetch from (js-obj "headers" headers))
        (.then (fn [resp]
                 (when-not (= (.-status resp) 200)
                   (notifos/done-working)
                   (throw (js/Error. (str "Error downloading: " from " status code: " (.-status resp)))))
                 (-> (.arrayBuffer resp)
                     (.then (fn [buf]
                              (.write out (js/Buffer.from buf))
                              (.end out)
                              (.on out "finish" cb))))))
        (.catch (fn [err]
                  (notifos/done-working)
                  (throw err))))))

(defn untar [from to cb]
  ;; tar v7's `x` does not create `cwd` if it is missing (the old tar v0.x
  ;; `.Extract` did); ensure the target dir exists first to preserve behavior.
  (.mkdirSync fs to (js-obj "recursive" true))
  (-> (.x tar (js-obj "file" from
                      "cwd" to
                      "gzip" true))
      (.then cb)))

(defn binary-version
  "Binary/electron version. The two versions are in sync since binaries updates
  only occur with electron updates."
  []
  (aget js/process.versions "electron"))

(defui button [label & [cb]]
       [:div.button.right label]
       :click (fn []
                (when cb
                  (cb))))

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::strict-ssl
          :triggers #{:object.instant}
          :type :user
          :exclusive [::disable-strict-ssl]
          :desc "Enables strict SSL certificate checking when downloading LT and LT plugin repos (default setting)"
          :reaction (fn [this]
                      (set! request-strict-ssl true)))

(behavior ::disable-strict-ssl
          :triggers #{:object.instant}
          :type :user
          :exclusive [::strict-ssl]
          :desc "Disables strict SSL certificate checking when downloading LT and LT plugin repos"
          :details "In some enterprise environments with SSL proxies strict certificate checking will fail due to MITM certificates used for monitoring SSL traffic. This option allows these network requests to succeed in such environments."
          :reaction (fn [this]
                      (set! request-strict-ssl false)))
