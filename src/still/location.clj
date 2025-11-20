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
  (:require [clojure.java.io :as io]
            [clojure.string :as s]))

(defn get-call-site
  "Get the call site information from the current stack trace.

  Returns a map with:
  - :file - Source file path
  - :line - Line number
  - :column - Column number (if available)
  - :ns - Namespace

  The depth parameter controls how far up the stack to look:
  - depth 0: the call to get-call-site itself
  - depth 1: the caller of get-call-site
  - depth 2: the caller's caller, etc.

  For snap! calls, we typically want depth 1 or 2."
  [depth]
  (let [stack-trace (.getStackTrace (Thread/currentThread))
        frame       (nth stack-trace depth nil)]
    (when frame
      {:file (.getFileName frame)
       :line (.getLineNumber frame)
       :class (.getClassName frame)
       :method (.getMethodName frame)})))

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
    (let [as-file (try (io/file filename) (catch Exception _ nil))]
      (if (and as-file (.isAbsolute as-file) (.exists as-file))
        (.getAbsolutePath as-file)
        ;; Otherwise search for it
        (let [search-paths  ["src" "test" "dev" "."]
              file-variants [filename
                             ;; Try converting .class files to .clj
                             (clojure.string/replace filename
                                                     #"\.class$"
                                                     ".clj")
                             (clojure.string/replace filename
                                                     #"\.class$"
                                                     ".cljc")]]
          (first (for [base-path search-paths
                       variant   file-variants
                       :let      [candidate (try (io/file base-path variant)
                                                 (catch Exception _ nil))]
                       :when     (and candidate (.exists candidate))]
                   (.getAbsolutePath candidate))))))))

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
    (first (filter #(.exists (io/file %)) candidates))))

(defn capture-location
  "Capture the current source location for snap! calls.

  This should be called from within a macro to capture the macro expansion site.
  Returns a map suitable for passing to still.rewrite functions."
  []
  (let [call-site (get-call-site 2) ; Skip this fn and its caller
        file-path (when (:file call-site)
                    (resolve-file-path (:file call-site)))]
    (when file-path (assoc call-site :absolute-path file-path))))

(defmacro with-location
  "Capture location information at macro expansion time.

  Returns a vector [value location-map] where location-map contains:
  - :file - Source file name
  - :line - Line number
  - :absolute-path - Absolute file path (if resolvable)

  Example:
    (with-location (+ 1 2))
    ;; => [3 {:file \"my_test.clj\" :line 42 :absolute-path \"/path/to/my_test.clj\"}]"
  [expr]
  (let [file          *file*
        line          (:line (meta &form))
        column        (:column (meta &form))
        absolute-path (when file (resolve-file-path file))]
    `[~expr
      {:file ~file :line ~line :column ~column :absolute-path ~absolute-path}]))

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
  ;; Example usage. Get current call site
  (get-call-site 0)
  ;; => {:file "location.clj" :line 145 :class "still.location" :method
  ;; "eval"}
  ;; Resolve a file
  (resolve-file-path "still/core.cljc")
  ;; => "/Users/waddie/source/still/src/still/core.cljc"
  ;; Convert namespace to file
  (file-from-ns 'still.core)
  ;; => "src/still/core.cljc"
  ;; Capture location with macro
  (with-location (+ 1 2))
  ;; => [3 {:file "location.clj" :line 155 ...}]
)
