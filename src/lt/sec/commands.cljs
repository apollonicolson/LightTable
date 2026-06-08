(ns lt.sec.commands
  "Security control-center commands (the command-driven first cut — a DOM
  permission-center panel binds the same lt.sec.center API later). Registering
  these makes the capability gate inspectable + controllable as real LightTable
  commands. Editor-coupled (lt.objs.command); loaded by the app."
  (:require [lt.objs.command :as cmd]
            [lt.sec.center :as center]))

(cmd/command {:command :security.summary
              :desc "Security: audit summary (grants per profile + journal tallies)"
              :exec (fn [] (clj->js (center/summary)))})

(cmd/command {:command :security.grants
              :desc "Security: grants in the active profile"
              :exec (fn [] (clj->js (center/all-grants)))})

(cmd/command {:command :security.journal
              :desc "Security: the effect journal (every allow/granted/deny)"
              :exec (fn [] (clj->js (center/journal)))})

(cmd/command {:command :security.revoke-all
              :desc "Security: revoke all capabilities held by a principal"
              :exec (fn [principal]
                      (center/revoke-all! principal)
                      (clj->js (center/all-grants)))})
