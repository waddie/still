;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.location
  "Source code location tracking for snap! calls.

  Captures file path, line, and column information from the call site to enable
  automatic source editing."
  (:require [babashka.fs :as fs]
            [clojure.string :as s]))

(defn resolve-file-path
  "Resolve a source file name to an absolute path.

  Searches in:
  1. src/ directory
  2. test/ directory
  3. dev/ directory
  4. Current working directory

  Returns nil if file cannot be found."
  [filename]
  ;; Handle nil filename (happens when *file* is nil in REPL context)
  (when filename
    ;; If already an absolute path and exists, return it
    (if (and (fs/absolute? filename) (fs/exists? filename))
      (str (fs/absolutize filename))
      ;; Otherwise, search for it
      (first (for [base-path ["src" "test" "dev" "."]
                   :let      [candidate (fs/file base-path filename)]
                   :when     (fs/exists? candidate)]
               (str (fs/absolutize candidate)))))))

(defn file-from-ns
  "Convert a namespace to a source file path.

  Examples:
    (file-from-ns 'still.core) => \"src/still/core.clj\" or \"src/still/core.cljc\"
    (file-from-ns 'my.test) => \"test/my/test.clj\""
  [ns-sym]
  (let [ns-path    (-> (str ns-sym)
                       (s/replace "-" "_")
                       (s/replace "." "/"))
        candidates [(str "src/" ns-path ".clj") (str "src/" ns-path ".cljc")
                    (str "test/" ns-path ".clj") (str "test/" ns-path ".cljc")
                    (str "dev/" ns-path ".clj") (str "dev/" ns-path ".cljc")]]
    (first (filter fs/exists? candidates))))

(defn location-string
  "Format a location map as a human-readable string.

  Examples:
    (location-string {:file \"test.clj\" :line 42})
    ;; => \"test.clj:42\"

    (location-string {:file \"test.clj\" :line 42 :column 10})
    ;; => \"test.clj:42:10\""
  [{:keys [file line column]}]
  (cond (and file line column) (str file ":" line ":" column)
        (and file line) (str file ":" line)
        file file
        :else "<unknown location>"))

(comment
  ;; Example usage. Resolve a file
  (resolve-file-path "still/core.cljc")
  ;; => "/Users/waddie/source/still/src/still/core.cljc"
  ;; Convert namespace to file
  (file-from-ns 'still.core)
  ;; => "src/still/core.cljc"
)
