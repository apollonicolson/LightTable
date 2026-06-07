(ns lt.editor.cm6.search
  "CM6-native find/replace over an immutable EditorState — the replacement for
  CM5's `search.js`/`searchcursor.js` addons (ADR 0009).

  Pure state functions: a query + a state yield match offset ranges, and
  find.cljs jumps to a match via the EXISTING seam (set-selection + scroll) and
  replaces via the seam. So there is no separate search StateField, no search
  panel, and no view coupling — find is just \"compute the match range over the
  canonical state, then move the cursor there.\" `SearchCursor`/`RegExpCursor`
  (from @codemirror/search) are pure `Text` iterators, so this whole namespace is
  node-testable headless.

  `opts` (all optional): `:regexp?` treat query as a regexp; `:case-sensitive?`
  (default false — case-insensitive, matching the find-bar's usual expectation);
  `:from`/`:to` restrict the search to an offset range.

  Cost note: match-ranges scans the whole doc each call (O(n)), so next/prev-match
  rescan per keystroke. CM5's addon rescanned similarly; for interactive find over
  source files this is fine. A large-file path would cache the match set."
  (:require ["@codemirror/search" :as cm-search]))

(def ^:private SearchCursor (.-SearchCursor cm-search))
(def ^:private RegExpCursor (.-RegExpCursor cm-search))

(defn- lower [s] (.toLowerCase s))

(defn- cursor
  "A fresh match iterator over [from,to) of `state`'s doc for `query`."
  [state query {:keys [regexp? case-sensitive? from to]}]
  (let [doc (.-doc state)
        from (or from 0)
        to (or to (.-length doc))]
    (if regexp?
      (RegExpCursor. doc query #js {:ignoreCase (not case-sensitive?)} from to)
      (SearchCursor. doc query from to (when-not case-sensitive? lower)))))

(defn match-ranges
  "All non-overlapping matches of `query` in `state`, in document order, as a
  vector of {:from :to} offset ranges. Empty when `query` is empty or unmatched."
  ([state query] (match-ranges state query nil))
  ([state query opts]
   (if (empty? query)
     []
     (let [c (cursor state query opts)]
       (loop [out (transient [])]
         (if (.-done (.next c))
           (persistent! out)
           (recur (conj! out {:from (.. c -value -from) :to (.. c -value -to)}))))))))

(defn next-match
  "The first match starting at or after offset `pos`, wrapping to the document
  start if none follows `pos`. nil only when there are no matches at all."
  ([state query pos] (next-match state query pos nil))
  ([state query pos opts]
   (let [all (match-ranges state query opts)]
     (or (first (filter #(>= (:from %) pos) all))
         (first all)))))

(defn prev-match
  "The last match ending at or before offset `pos`, wrapping to the document end
  if none precedes `pos`. nil only when there are no matches at all."
  ([state query pos] (prev-match state query pos nil))
  ([state query pos opts]
   (let [all (match-ranges state query opts)]
     (or (last (filter #(<= (:to %) pos) all))
         (last all)))))

(defn replace-all
  "Return a NEW state with every match of `query` replaced by `replacement`,
  applied as ONE transaction (so it is a single undo step). Returns `state`
  unchanged when there is no match."
  ([state query replacement] (replace-all state query replacement nil))
  ([state query replacement opts]
   (let [ranges (match-ranges state query opts)]
     (if (empty? ranges)
       state
       (-> state
           (.update #js {:changes (clj->js (mapv (fn [{:keys [from to]}]
                                                   {:from from :to to :insert replacement})
                                                 ranges))})
           (.-state))))))
