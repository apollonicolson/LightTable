(ns lt.lsp.service-test
  "Falsifier gate for lt.lsp.service (ADR 0010 slice 2 layer-1): the language-
  client service drives document sync with correct versions, routes inbound
  publishDiagnostics to subscribers + the registry, and tears docs down — all
  through a fake in-memory transport (no editor, no live server)."
  (:require [clojure.test :refer [deftest is]]
            [defport.lsp.client :as lsp]
            [lt.lsp.service :as svc]))

(defn- fake-transport
  "Records outbound messages into `sent`; never delivers inbound (tests drive
  inbound via lsp/dispatch-incoming!)."
  [sent]
  (reify lsp/ClientTransport
    (transport-start! [this] this)
    (transport-send!  [this msg] (swap! sent conj msg) this)
    (transport-recv!  [_] ::lsp/no-message)
    (transport-stop!  [this] this)
    (transport-alive? [_] true)))

(defn- methods-of [sent] (map :method @sent))
(defn- last-sent [sent] (last @sent))

(deftest open-sends-didopen-and-registers
  (let [sent (atom [])
        s (-> (svc/create (fake-transport sent))
              (svc/open-doc! "file:///a.clj" "clojure" "(ns a)"))]
    (is (= ["textDocument/didOpen"] (methods-of sent)))
    (is (svc/open? s "file:///a.clj"))
    (is (= 1 (svc/doc-version s "file:///a.clj")))
    (let [p (:params (last-sent sent))]
      (is (= "file:///a.clj" (get-in p [:textDocument :uri])))
      (is (= "clojure" (get-in p [:textDocument :languageId])))
      (is (= "(ns a)" (get-in p [:textDocument :text]))))))

(deftest change-bumps-version-and-full-syncs
  (let [sent (atom [])
        s (-> (svc/create (fake-transport sent))
              (svc/open-doc! "file:///a.clj" "clojure" "x")
              (svc/change-doc! "file:///a.clj" "xy")
              (svc/change-doc! "file:///a.clj" "xyz"))]
    (is (= 3 (svc/doc-version s "file:///a.clj")) "version bumps per change")
    (is (= ["textDocument/didOpen" "textDocument/didChange" "textDocument/didChange"]
           (methods-of sent)))
    (let [p (:params (last-sent sent))]
      (is (= 3 (get-in p [:textDocument :version])))
      (is (= [{:text "xyz"}] (:contentChanges p)) "full-text sync"))))

(deftest publish-diagnostics-routes-to-registry-and-subscriber
  (let [sent (atom [])
        s (svc/create (fake-transport sent))
        seen (atom nil)
        _ (svc/on-diagnostics s (fn [uri diags] (reset! seen [uri (count diags)])))
        diag {:range {:start {:line 0 :character 0} :end {:line 0 :character 1}}
              :severity 1 :message "boom"}]
    (lsp/dispatch-incoming! (svc/client s)
                            {:jsonrpc "2.0"
                             :method "textDocument/publishDiagnostics"
                             :params {:uri "file:///a.clj" :diagnostics [diag]}})
    (is (= ["file:///a.clj" 1] @seen) "subscriber fired with uri + diagnostics")
    (is (= [diag] (svc/diagnostics-for s "file:///a.clj")) "registry updated")
    (is (= [] (svc/diagnostics-for s "file:///other")) "unknown uri → empty")))

(deftest republish-replaces-diagnostics
  (let [s (svc/create (fake-transport (atom [])))
        pub (fn [diags] (lsp/dispatch-incoming! (svc/client s)
                                                 {:jsonrpc "2.0"
                                                  :method "textDocument/publishDiagnostics"
                                                  :params {:uri "file:///a.clj" :diagnostics diags}}))]
    (pub [{:message "one"} {:message "two"}])
    (pub [{:message "fixed"}])
    (is (= 1 (count (svc/diagnostics-for s "file:///a.clj"))) "latest publish replaces, not appends")))

(deftest close-clears-and-sends-didclose
  (let [sent (atom [])
        s (-> (svc/create (fake-transport sent))
              (svc/open-doc! "file:///a.clj" "clojure" "x"))
        _ (lsp/dispatch-incoming! (svc/client s)
                                  {:jsonrpc "2.0"
                                   :method "textDocument/publishDiagnostics"
                                   :params {:uri "file:///a.clj" :diagnostics [{:message "x"}]}})
        s (svc/close-doc! s "file:///a.clj")]
    (is (not (svc/open? s "file:///a.clj")) "doc unregistered")
    (is (= [] (svc/diagnostics-for s "file:///a.clj")) "diagnostics dropped")
    (is (= "textDocument/didClose" (:method (last-sent sent))))))
