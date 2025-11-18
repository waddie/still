;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.rewrite
  "Source code rewriting for snap! inline snapshots.

  Uses rewrite-clj to parse, locate, and modify snap! calls in source files
  while preserving formatting and comments."
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [still.serialize :as serialize]))

(defn- parse-file
  "Parse a source file into a rewrite-clj zipper."
  [file-path]
  (-> file-path
      slurp
      p/parse-string-all
      z/of-node))

(defn- snap!-call?
  "Check if a zipper location is a snap! function call."
  [loc]
  (and (z/list? loc)
       (when-let [first-child (z/down loc)]
         (let [sym (z/sexpr first-child)]
           (or (= sym 'snap!) (= sym 'still.core/snap!))))))

(defn- find-snap!-at-line
  "Find a snap! call at the specified line number.

  Returns the zipper location of the snap! call, or nil if not found."
  [zloc target-line]
  (loop [loc zloc]
    (if (z/end? loc)
      nil
      (let [current-line (-> loc
                             z/node
                             meta
                             :row)]
        (cond
          ;; Found snap! at target line
          (and (= current-line target-line) (snap!-call? loc)) loc
          ;; Keep searching
          :else (recur (z/next loc)))))))

(defn- count-children
  "Count the number of child nodes in a list form."
  [loc]
  (when (z/list? loc) (count (z/child-sexprs loc))))

(defn- has-expected-value?
  "Check if a snap! call already has an expected value argument.

  A snap! call has an expected value if it has 2 or more arguments:
  (snap! value) => no expected value (1 arg after snap! symbol)
  (snap! value expected) => has expected value (2 args after snap! symbol)"
  [loc]
  (when (snap!-call? loc) (>= (count-children loc) 3))) ; snap! symbol + value + expected

(defn- insert-expected-value
  "Insert an expected value as the second argument to a snap! call.

  Example:
    (snap! (compute-result))
    =>
    (snap! (compute-result) {:result 42})"
  [loc expected-value]
  (when (snap!-call? loc)
    (let [;; Move to the function symbol
          func-loc (z/down loc)
          ;; Move to the first argument (the value)
          value-loc (z/right func-loc)
          ;; Create a node for the expected value
          expected-node (n/spaces 1) ; Add space before expected value
          expected-value-node (p/parse-string (serialize/pretty-print
                                               expected-value))]
      ;; Insert expected value after the value argument
      (-> value-loc
          (z/insert-right expected-value-node)
          (z/insert-right expected-node)
          z/up))))

(defn- replace-expected-value
  "Replace an existing expected value in a snap! call.

  Example:
    (snap! (compute-result) {:result 41})
    =>
    (snap! (compute-result) {:result 42})"
  [loc expected-value]
  (when (snap!-call? loc)
    (let [;; Move to the function symbol
          func-loc (z/down loc)
          ;; Move to the first argument (the value)
          value-loc (z/right func-loc)
          ;; Move to the second argument (the expected value)
          expected-loc (z/right value-loc)
          ;; Create a node for the new expected value
          expected-value-node (p/parse-string (serialize/pretty-print
                                               expected-value))]
      ;; Replace the expected value
      (-> expected-loc
          (z/replace expected-value-node)
          z/up))))

(defn- write-zipper
  "Write a zipper back to a file, preserving formatting."
  [zloc file-path]
  (let [content (z/root-string zloc)] (spit file-path content)))

(defn add-expected-value!
  "Add or update the expected value in a snap! call.

  Arguments:
  - file-path: Absolute path to the source file
  - line: Line number of the snap! call
  - expected-value: The value to insert as expected

  Returns:
  - :updated if the expected value was updated
  - :inserted if a new expected value was inserted
  - :not-found if the snap! call wasn't found
  - :error if an error occurred"
  [file-path line expected-value]
  (try
    (let [zloc (parse-file file-path)
          snap-loc (find-snap!-at-line zloc line)]
      (cond (nil? snap-loc) {:status :not-found
                             :message (str "No snap! call found at " file-path
                                           ":" line)}
            (has-expected-value? snap-loc)
            (let [updated-loc (replace-expected-value snap-loc expected-value)]
              (write-zipper updated-loc file-path)
              {:status :updated
               :message (str "Updated expected value at " file-path ":" line)})
            :else (let [updated-loc (insert-expected-value snap-loc
                                                           expected-value)]
                    (write-zipper updated-loc file-path)
                    {:status :inserted
                     :message (str "Inserted expected value at " file-path
                                   ":" line)})))
    (catch Exception e {:status :error :message (.getMessage e) :exception e})))

(defn remove-expected-value!
  "Remove the expected value from a snap! call.

  This converts:
    (snap! value expected)
  to:
    (snap! value)

  Useful for resetting a snapshot."
  [file-path line]
  (try (let [zloc (parse-file file-path)
             snap-loc (find-snap!-at-line zloc line)]
         (cond (nil? snap-loc)
               {:status :not-found
                :message (str "No snap! call found at " file-path ":" line)}
               (not (has-expected-value? snap-loc))
               {:status :no-change
                :message "snap! call has no expected value to remove"}
               :else (let [;; Navigate to the expected value argument
                           func-loc (z/down snap-loc)
                           value-loc (z/right func-loc)
                           expected-loc (z/right value-loc)
                           ;; Remove it
                           updated-loc (-> expected-loc
                                           z/remove
                                           z/up)]
                       (write-zipper updated-loc file-path)
                       {:status :removed
                        :message (str "Removed expected value at " file-path
                                      ":" line)})))
       (catch Exception e
         {:status :error :message (.getMessage e) :exception e})))

(defn find-all-snap!-calls
  "Find all snap! calls in a file.

  Returns a sequence of maps with:
  - :line - Line number
  - :column - Column number
  - :has-expected? - Whether it has an expected value
  - :value - The value expression (as string)
  - :expected - The expected value expression (if present, as string)"
  [file-path]
  (try (let [zloc (parse-file file-path)]
         (loop [loc zloc
                results []]
           (if (z/end? loc)
             results
             (if (snap!-call? loc)
               (let [meta (-> loc
                              z/node
                              meta)
                     func-loc (z/down loc)
                     value-loc (z/right func-loc)
                     expected-loc (when (has-expected-value? loc)
                                    (z/right value-loc))
                     result {:line (:row meta)
                             :column (:col meta)
                             :has-expected? (boolean expected-loc)
                             :value (z/string value-loc)
                             :expected (when expected-loc
                                         (z/string expected-loc))}]
                 (recur (z/next loc) (conj results result)))
               (recur (z/next loc) results)))))
       (catch Exception e
         (throw (ex-info (str "Failed to parse file: " file-path)
                         {:file-path file-path :error (.getMessage e)}
                         e)))))

(comment
  ;; Example usage. Find all snap! calls in a file
  (find-all-snap!-calls "test/still/core_test.clj")
  ;; => [{:line 42 :column 3 :has-expected? false :value "(compute-result)"
  ;; :expected nil}
  ;;     {:line 45 :column 3 :has-expected? true :value "(+ 1 2)" :expected
  ;;     "3"}]
  ;; Add expected value to a snap! call
  (add-expected-value! "test/still/core_test.clj" 42 {:result 123})
  ;; => {:status :inserted :message "Inserted expected value at
  ;; test/still/core_test.clj:42"}
  ;; Update expected value
  (add-expected-value! "test/still/core_test.clj" 42 {:result 456})
  ;; => {:status :updated :message "Updated expected value at
  ;; test/still/core_test.clj:42"}
  ;; Remove expected value
  (remove-expected-value! "test/still/core_test.clj" 42)
  ;; => {:status :removed :message "Removed expected value at
  ;; test/still/core_test.clj:42"}
)
