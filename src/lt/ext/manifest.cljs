(ns lt.ext.manifest
  "Phase 1 of the VSCode extension host (ADR 0011): parse + normalize an extension
  `package.json` into a descriptor. Pure — no fs, no editor; node-loadable + tested.
  The declarative `contributes`/`activationEvents` are read here without running
  any extension code (activation is lazy; see lt.ext.activation / lt.ext.host).")

(defn parse
  "Normalize a keywordized package.json map into an extension descriptor."
  [m]
  {:id                (when (and (:publisher m) (:name m))
                        (str (:publisher m) "." (:name m)))
   :name              (:name m)
   :publisher         (:publisher m)
   :version           (:version m)
   :main              (:main m)
   :engine            (get-in m [:engines :vscode])
   :activation-events (vec (:activationEvents m))
   :contributes       (:contributes m)})

(defn errors
  "Seq of validation problems for a descriptor (empty = valid)."
  [desc]
  (cond-> []
    (not (:name desc))      (conj "missing name")
    (not (:publisher desc)) (conj "missing publisher")
    (not (:main desc))      (conj "missing main")
    (not (:engine desc))    (conj "missing engines.vscode")))

(defn valid? [desc] (empty? (errors desc)))

(defn contributes
  "The declarative contribution list for `point` (e.g. :commands, :languages)."
  [desc point]
  (get-in desc [:contributes point]))
