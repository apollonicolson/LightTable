(ns lt.editor.cm6.fold
  "CM6 code folding — the replacement for CM5's fold addon (ADR 0009). `extension`
  goes in the editor's extension set (enables folding state + gutter-less folding);
  `fold-code!`/`unfold-code!` run the @codemirror/language fold StateCommands on a
  live view (they need real layout, so Electron-tested)."
  (:require ["@codemirror/language" :as cm-lang]))

(def ^:private code-folding (.-codeFolding cm-lang))
(def ^:private fold-cmd (.-foldCode cm-lang))
(def ^:private unfold-cmd (.-unfoldCode cm-lang))

(defn extension [] (code-folding))

(defn fold-code!   [view] (fold-cmd view) view)
(defn unfold-code! [view] (unfold-cmd view) view)
