(ns lt.ext.vscode.window
  "Phase 3b of the VSCode extension host (ADR 0011): the `vscode.window` namespace —
  messages, status bar, output channels. The editor-coupled surfaces (real notifos /
  status bar) are injected sinks (wired in Electron); the logic + an audit log are
  node-loadable + tested. `activeTextEditor` etc. arrive with the editor wiring."
  (:require [lt.ext.vscode.types :as t]))

(defonce ^:private message-sink (atom nil))   ; (fn [level text items]) -> chosen item
(defonce message-log (atom []))               ; audit / test visibility
(defonce ^:private status-sink (atom nil))    ; (fn [text])

(defn set-message-sink! [f] (reset! message-sink f))
(defn set-status-sink! [f] (reset! status-sink f))

(defn- show-message [level text items]
  (swap! message-log conj {:level level :text text})
  (.resolve js/Promise (when-let [f @message-sink] (f level text items))))

(defn create-output-channel [name]
  (let [buf (atom "")]
    #js {:name       name
         :append     (fn [s] (swap! buf str s))
         :appendLine (fn [s] (swap! buf str s "\n"))
         :replace    (fn [s] (reset! buf s))
         :clear      (fn [] (reset! buf ""))
         :show       (fn [& _] nil)
         :hide       (fn [] nil)
         :dispose    (fn [] (reset! buf ""))
         :_value     (fn [] @buf)}))   ; test accessor (not part of the vscode API)

(defn ns-object []
  #js {:showInformationMessage (fn [text & items] (show-message :info text (vec items)))
       :showWarningMessage     (fn [text & items] (show-message :warning text (vec items)))
       :showErrorMessage       (fn [text & items] (show-message :error text (vec items)))
       :setStatusBarMessage    (fn [text & _] (when-let [f @status-sink] (f text))
                                 (t/disposable (fn [] nil)))
       :createOutputChannel    (fn [name & _] (create-output-channel name))})

(defn reset-window! []
  (reset! message-sink nil) (reset! message-log []) (reset! status-sink nil))
