(ns lt.util.load-test
  "Unit tests for the pure string/data logic in `lt.util.load`.

  Runs under shadow-cljs `:node-test` (Node, no DOM, no Electron, no browser).
  Only the functions that depend on nothing more than `clojure.string`, the
  Node `path` library, and plain JS objects are exercised here. Functions that
  touch `js/window`, `js/document`, or `js/require` of arbitrary modules are
  intentionally not tested (see the test file's accompanying report)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.util.load :as load]))

;; ---------------------------------------------------------------------------
;; absolute?
;; ---------------------------------------------------------------------------
;; Regex: #"^\s*[\\\/]|([\w]+:[\\\/])"
;; True when the path begins (after optional leading whitespace) with a `/` or
;; `\`, OR when it contains a Windows-style drive prefix like `C:\` or `C:/`.

(deftest absolute?-unix-absolute
  (testing "leading forward slash is absolute"
    (is (true? (load/absolute? "/foo/bar/baz")))
    (is (true? (load/absolute? "/foo/bar/baz.txt")))
    (is (true? (load/absolute? "/")))))

(deftest absolute?-leading-whitespace
  (testing "leading whitespace before a slash is still absolute"
    (is (true? (load/absolute? "   /foo/bar")))
    (is (true? (load/absolute? "\t/foo")))))

(deftest absolute?-backslash
  (testing "a leading backslash is absolute"
    (is (true? (load/absolute? "\\foo\\bar")))))

(deftest absolute?-windows-drive
  (testing "a Windows drive prefix is absolute"
    (is (true? (load/absolute? "C:\\foo\\bar")))
    (is (true? (load/absolute? "C:/foo/bar")))
    (is (true? (load/absolute? "D:\\")))))

(deftest absolute?-relative
  (testing "relative paths are not absolute"
    (is (false? (load/absolute? "./foo/bar")))
    (is (false? (load/absolute? "foo/bar")))
    (is (false? (load/absolute? "../foo")))
    (is (false? (load/absolute? "foo.txt")))
    (is (false? (load/absolute? "")))))

(deftest absolute?-returns-boolean
  (testing "always returns a boolean, never a seq/nil"
    (is (boolean? (load/absolute? "/foo")))
    (is (boolean? (load/absolute? "foo")))))

;; ---------------------------------------------------------------------------
;; abs-source-mapping-url (private)
;; ---------------------------------------------------------------------------
;; Rewrites a relative `//# sourceMappingURL=...map` comment to an absolute,
;; forward-slash path. Leaves code untouched when there is no mapping comment,
;; or when the mapping is already absolute.

(deftest abs-source-mapping-url-no-mapping
  (testing "code without a sourceMappingURL comment is returned unchanged"
    (let [code "var a = 1;\nvar b = 2;"]
      (is (= code (#'load/abs-source-mapping-url code "/some/dir/file.js"))))))

(deftest abs-source-mapping-url-already-absolute
  (testing "an already-absolute mapping url is left unchanged"
    (let [code "var a = 1;\n//# sourceMappingURL=/abs/path/file.js.map"]
      (is (= code (#'load/abs-source-mapping-url code "/some/dir/file.js"))))))

(deftest abs-source-mapping-url-relative-rewritten
  (testing "a relative mapping url is rewritten to an absolute, forward-slash path"
    (let [code "var a = 1;\n//# sourceMappingURL=file.js.map"
          result (#'load/abs-source-mapping-url code "/some/dir/file.js")]
      ;; result keeps the body, replaces the comment with an absolute URL
      (is (re-find #"\n//# sourceMappingURL=" result))
      ;; the rewritten path is absolute and uses forward slashes
      (is (re-find #"\n//# sourceMappingURL=/" result))
      (is (not (re-find #"\\" result)))
      ;; the resolved url should point at the resolved map under /some/dir
      (is (re-find #"/some/dir/file\.js\.map" result)))))

;; ---------------------------------------------------------------------------
;; prep (private)
;; ---------------------------------------------------------------------------
;; Pipes code through abs-source-mapping-url, then appends a sourceURL comment
;; with the URI-encoded file path.

(deftest prep-appends-source-url
  (testing "prep appends a URI-encoded sourceURL comment"
    (let [code "var a = 1;"
          result (#'load/prep code "/some/dir/file.js")]
      (is (re-find #"\n\n//# sourceURL=" result))
      ;; original code is preserved at the front
      (is (= "var a = 1;" (subs result 0 (count code))))
      ;; the appended url is encodeURI of the file path
      (is (re-find (re-pattern (js/encodeURI "/some/dir/file.js")) result)))))

(deftest prep-encodes-special-chars
  (testing "spaces in the file path are URI-encoded in the sourceURL"
    (let [result (#'load/prep "x" "/some dir/file.js")]
      (is (re-find #"%20" result))
      (is (not (re-find #"sourceURL=/some dir" result))))))

(deftest prep-runs-source-mapping-first
  (testing "prep also rewrites a relative source mapping url"
    (let [code "var a = 1;\n//# sourceMappingURL=file.js.map"
          result (#'load/prep code "/some/dir/file.js")]
      ;; sourceMappingURL rewritten to absolute
      (is (re-find #"\n//# sourceMappingURL=/some/dir/file\.js\.map" result))
      ;; and a sourceURL is appended on top
      (is (re-find #"\n\n//# sourceURL=" result)))))

;; ---------------------------------------------------------------------------
;; provided-ancestors
;; ---------------------------------------------------------------------------
;; Counts keys of the `provided` object whose name contains `parent` as a
;; substring. `provided` starts as an empty JS object, so with no mutation the
;; count is 0 for any input.

(deftest provided-ancestors-empty
  (testing "with the default empty `provided`, no ancestors are found"
    (is (= 0 (load/provided-ancestors "anything")))
    (is (= 0 (load/provided-ancestors "")))))

;; ---------------------------------------------------------------------------
;; only-ancestors?
;; ---------------------------------------------------------------------------
;; True when (key count of cur) <= (provided-ancestors s). Since
;; provided-ancestors is 0 for a fresh `provided`, this is true only when `cur`
;; has zero own keys.

(deftest only-ancestors?-empty-object
  (testing "an object with no keys has <= 0 ancestors -> true"
    (is (true? (load/only-ancestors? #js {} "foo")))))

(deftest only-ancestors?-non-empty-object
  (testing "an object with keys exceeds the 0 ancestors of a fresh `provided` -> false"
    (is (false? (load/only-ancestors? #js {:a 1} "foo")))
    (is (false? (load/only-ancestors? #js {:a 1 :b 2} "foo")))))

(deftest only-ancestors?-returns-boolean
  (testing "returns a boolean"
    (is (boolean? (load/only-ancestors? #js {} "foo")))
    (is (boolean? (load/only-ancestors? #js {:a 1} "foo")))))
