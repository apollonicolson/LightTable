(ns lt.ext.sandbox-host-refactor-test
  "C — extension refactor features: rename (→ WorkspaceEdit) + code actions (quick
  fixes, → CodeAction with a WorkspaceEdit). Exercises the new vscode.WorkspaceEdit /
  CodeAction / CodeActionKind shim types, constructed by a no-Node extension and
  mapped to serializable edit data over the membrane."
  (:require [cljs.test :refer-macros [deftest is async]]
            [lt.ext.membrane :as m]
            [lt.ext.sandbox-host :as host]
            [lt.ext.vscode.commands :as commands]
            [lt.ext.vscode.languages :as languages]
            [lt.sec.gate :as gate]))

(def ^:private refactor-ext
  "const vscode = require('vscode');
   exports.activate = (ctx) => {
     vscode.languages.registerRenameProvider('*', {
       provideRenameEdits: (doc, pos, newName) => {
         const we = new vscode.WorkspaceEdit();
         we.replace('file:///z.clj', { start: { line: 1, character: 6 }, end: { line: 1, character: 11 } }, newName);
         return we;
       }
     });
     vscode.languages.registerCodeActionsProvider('*', {
       provideCodeActions: (doc, range, ctx) => {
         const fix = new vscode.CodeAction('Add docstring', vscode.CodeActionKind.QuickFix);
         fix.isPreferred = true;
         const we = new vscode.WorkspaceEdit();
         we.insert('file:///z.clj', { line: 1, character: 0 }, '\"doc\" ');
         fix.edit = we;
         return [ fix, { title: 'Run thing', command: 'z.run' } ];
       }
     });
     return { ok: true };
   };")

(deftest rename-and-code-actions-through-the-host
  (gate/reset-gate!)
  (commands/reset-registry!)
  (languages/reset-languages!)
  (let [[main-t host-t] (m/loopback)
        main    (m/endpoint main-t)
        host-ep (m/endpoint host-t)]
    (host/start! host-ep "ext.refactor")
    (async done
      (let [act ((:request main) {:t :activate :id "ext.refactor" :code refactor-ext})]
        (is (true? (.-ok (:data act))) "refactor extension activated"))
      ((:notify main) {:t :doc :op :open :uri "file:///z.clj" :text "(ns z)\n(defn greet [n] n)"})
      ;; rename → WorkspaceEdit → serializable changes
      (let [res ((:request main) {:t :invoke :feature :rename :uri "file:///z.clj"
                                  :line 1 :character 6 :new-name "welcome"})
            chg (get-in (:data res) [:changes "file:///z.clj"])]
        (is (= "welcome" (:new-text (first chg))) "rename WorkspaceEdit crossed back as edit data")
        (is (= 6 (get-in (first chg) [:range :start :character])) "edit range mapped"))
      ;; code actions → [CodeAction(with edit) + Command]
      (let [res ((:request main) {:t :invoke :feature :code-actions :uri "file:///z.clj"
                                  :line 1 :character 0 :end-line 1 :end-character 5})
            as  (:data res)]
        (is (= ["Add docstring" "Run thing"] (mapv :title as)) "both actions crossed back")
        (is (= "quickfix" (:kind (first as))) "CodeActionKind.QuickFix mapped to its value")
        (is (true? (:is-preferred (first as))) "isPreferred preserved")
        (is (= "\"doc\" " (get-in (first as) [:edit :changes "file:///z.clj" 0 :new-text]))
            "the action's WorkspaceEdit insert mapped")
        (is (= "z.run" (:command (second as))) "a plain Command's id mapped"))
      (done))))
