(ns lt.time
  "EXPERIMENTAL — epochal recording over a cell (prototype, not wired into the
  editor).

  'Scroll back through program state as transactions', the lisp-y way: a cell is
  an identity holding a succession of immutable values (Hickey's epochal time).
  Recording that succession makes as-of O(1) — the value was RETAINED, so there
  is no replay (unlike Datomic, which replays datoms). Because cell values are
  immutable and structurally shared, the log is cheap and safe.

  Scope/limits (deliberate): records only state that flows through the cell;
  hidden/local mutation and external effects are invisible and do NOT rewind.
  `restore!` is destructive to the live value (not to history) — and is itself
  recorded, so the log never forgets. Cross-identity correlation ('scroll the
  whole program') needs a shared clock; this prototype records ONE identity.

  Wraps vendored sig `cell` without modifying it; graduates to sig if it proves
  out. See test/lt/time_test.cljs."
  (:require [cell :as cell]))

(defn recorded
  "Wrap cell `c`, retaining every epochal value in an append-only log. Returns
  {:cell c :log (atom [current])}. Recording is via add-watch, so only epochal
  changes (not= old new) are logged — matching cell's change semantics (an `=`
  write is a no-op epoch and is not recorded)."
  [c]
  (let [log (atom [@c])
        rec {:cell c :log log}]
    (add-watch c ::recorder (fn [_ _ _old new] (swap! log conj new)))
    rec))

(defn history
  "The retained succession of values, oldest first — the 'transactions' you can
  scroll through. Each entry is the program state at one epoch."
  [rec]
  @(:log rec))

(defn epochs
  "How many epochs have been recorded."
  [rec]
  (count (history rec)))

(defn at
  "The value at epoch `idx`. O(1): the immutable value was retained, no replay."
  [rec idx]
  (nth (history rec) idx))

(defn restore!
  "DESTRUCTIVE time-travel: set the live cell back to its value at epoch `idx`.
  Effects already performed do NOT rewind. The restore is itself an epoch, so
  the log keeps growing (Datomic-shaped 'never forget') and you can scroll
  forward again from here."
  [rec idx]
  (reset! (:cell rec) (at rec idx)))

(defn stop!
  "Detach the recorder; the cell continues, unrecorded."
  [rec]
  (remove-watch (:cell rec) ::recorder))
