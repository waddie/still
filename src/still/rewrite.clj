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
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [still.serialize :as serialize]))

(defn ^:private parse-file
  "Parse a source file into a rewrite-clj zipper."
  [file-path]
  (-> file-path
      slurp
      p/parse-string-all
      z/of-node))

(defn ^:private snap!-call?
  "Check if a zipper location is a snap! function call."
  [loc]
  (and (z/list? loc)
       (when-let [first-child (z/down loc)]
         (let [sym (z/sexpr first-child)]
           (or (= sym 'snap!) (= sym 'still.core/snap!))))))

(defn ^:private find-snap!-at-line
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

(defn ^:private count-children
  "Count the number of child nodes in a list form."
  [loc]
  (when (z/list? loc) (count (z/child-sexprs loc))))

(defn ^:private has-expected-value?
  "Check if a snap! call already has an expected value argument.

  A snap! call has an expected value if it has 2 or more arguments:
  (snap! value) => no expected value (1 arg after snap! symbol)
  (snap! value expected) => has expected value (2 args after snap! symbol)"
  [loc]
  (when (snap!-call? loc) (>= (count-children loc) 3))) ; snap! symbol + value + expected

(defn ^:private value-signature
  "Canonical string of a snap! call's value expression, or nil if it can't be
  read. Matches the signature the snap! macro computes from the same source
  form (pr-str with print-length/level unbound)."
  [loc]
  (when-let [value-loc (some-> loc
                               z/down
                               z/right)]
    (try (binding [*print-length* nil
                   *print-level*  nil]
           (pr-str (z/sexpr value-loc)))
         (catch Exception _ nil))))

(defn ^:private find-snap!-by-value
  "Locate the snap! call to edit.

  The value expression's text does not drift when other snap! calls in the
  same file are edited, so it is the primary key; `line` (compile-time) only
  breaks ties among calls with identical value expressions.

  - value-sig nil (REPL/explicit path): fall back to line lookup.
  - exactly one signature match: that call.
  - several matches: prefer still-bare calls (an already-edited sibling is no
    longer bare), then the one whose row is nearest to `line`.
  - no match (value expr that doesn't round-trip, e.g. a fn literal): fall
    back to line lookup."
  [zloc line value-sig]
  (if (nil? value-sig)
    (find-snap!-at-line zloc line)
    (let [calls   (loop [loc zloc
                         acc []]
                    (if (z/end? loc)
                      acc
                      (recur (z/next loc)
                             (if (snap!-call? loc)
                               (conj acc
                                     {:has-expected? (has-expected-value? loc)
                                      :loc           loc
                                      :row           (-> loc
                                                         z/node
                                                         meta
                                                         :row)
                                      :sig           (value-signature loc)})
                               acc))))
          matches (filter #(= value-sig (:sig %)) calls)]
      (case (count matches)
        0 (find-snap!-at-line zloc line)
        1 (:loc (first matches))
        (let [bare (remove :has-expected? matches)
              pool (if (seq bare) bare matches)]
          (:loc (apply min-key
                       #(Math/abs (long (- (long (:row %)) line)))
                       pool)))))))

(defn ^:private insert-expected-value
  "Insert an expected value as the second argument to a snap! call.

  Example:
    (snap! (compute-result))
    =>
    (snap! (compute-result) {:result 42})"
  [loc expected-value]
  (when (snap!-call? loc)
    (let [;; Move to the function symbol
          func-loc  (z/down loc)
          ;; Move to the first argument (the value)
          value-loc (z/right func-loc)
          ;; Create a node for the expected value
          expected-value-node (p/parse-string (serialize/format-value-for-source
                                               expected-value))]
      ;; Insert expected value after the value argument
      (-> value-loc
          (z/insert-right expected-value-node)
          z/up))))

(defn ^:private replace-expected-value
  "Replace an existing expected value in a snap! call.

  Example:
    (snap! (compute-result) {:result 41})
    =>
    (snap! (compute-result) {:result 42})"
  [loc expected-value]
  (when (snap!-call? loc)
    (let [;; Move to the function symbol
          func-loc            (z/down loc)
          ;; Move to the first argument (the value)
          value-loc           (z/right func-loc)
          ;; Move to the second argument (the expected value)
          expected-loc        (z/right value-loc)
          ;; Create a node for the new expected value
          expected-value-node (p/parse-string (serialize/format-value-for-source
                                               expected-value))]
      ;; Replace the expected value
      (-> expected-loc
          (z/replace expected-value-node)
          z/up))))

(defn ^:private write-zipper
  "Write a zipper back to a file, preserving formatting."
  [zloc file-path]
  (let [content (z/root-string zloc)]
    (spit file-path content)))

(defn add-expected-value!
  "Add or update the expected value in a snap! call.

  Arguments:
  - file-path: Absolute path to the source file
  - line: Line number of the snap! call (compile-time, pre-edit)
  - expected-value: The value to insert as expected
  - value-sig (optional): canonical string of the value expression, used to
    locate the call regardless of line drift. When omitted or nil, the call
    is located by line number alone.

  Returns:
  - :updated if the expected value was updated
  - :inserted if a new expected value was inserted
  - :not-found if the snap! call wasn't found
  - :error if an error occurred"
  ([file-path line expected-value]
   (add-expected-value! file-path line expected-value nil))
  ([file-path line expected-value value-sig]
   (try (let [zloc     (parse-file file-path)
              snap-loc (find-snap!-by-value zloc line value-sig)]
          (cond
            (nil? snap-loc) {:message (str "No snap! call found at " file-path
                                           ":" line)
                             :status  :not-found}
            (has-expected-value? snap-loc)
            (let [updated-loc (replace-expected-value snap-loc expected-value)]
              (write-zipper updated-loc file-path)
              {:message (str "Updated expected value at " file-path ":" line)
               :status  :updated})
            :else
            (let [updated-loc (insert-expected-value snap-loc expected-value)]
              (write-zipper updated-loc file-path)
              {:message (str "Inserted expected value at " file-path ":" line)
               :status  :inserted})))
        (catch Exception e
          {:exception e
           :message   (.getMessage e)
           :status    :error}))))

(defn remove-expected-value!
  "Remove the expected value from a snap! call.

  This converts:
    (snap! value expected)
  to:
    (snap! value)

  Useful for resetting a snapshot."
  [file-path line]
  (try (let [zloc     (parse-file file-path)
             snap-loc (find-snap!-at-line zloc line)]
         (cond (nil? snap-loc)
               {:message (str "No snap! call found at " file-path ":" line)
                :status  :not-found}
               (not (has-expected-value? snap-loc))
               {:message "snap! call has no expected value to remove"
                :status  :no-change}
               :else (let [;; Navigate to the expected value argument
                           func-loc     (z/down snap-loc)
                           value-loc    (z/right func-loc)
                           expected-loc (z/right value-loc)
                           ;; Remove it
                           updated-loc  (-> expected-loc
                                            z/remove
                                            z/up)]
                       (write-zipper updated-loc file-path)
                       {:message (str "Removed expected value at " file-path
                                      ":" line)
                        :status  :removed})))
       (catch Exception e
         {:exception e
          :message   (.getMessage e)
          :status    :error})))

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
         (loop [loc     zloc
                results []]
           (if (z/end? loc)
             results
             (if (snap!-call? loc)
               (let [meta         (-> loc
                                      z/node
                                      meta)
                     func-loc     (z/down loc)
                     value-loc    (z/right func-loc)
                     expected-loc (when (has-expected-value? loc)
                                    (z/right value-loc))
                     result       {:column        (:col meta)
                                   :expected      (when expected-loc
                                                    (z/string expected-loc))
                                   :has-expected? (boolean expected-loc)
                                   :line          (:row meta)
                                   :value         (z/string value-loc)}]
                 (recur (z/next loc) (conj results result)))
               (recur (z/next loc) results)))))
       (catch Exception e
         (throw (ex-info (str "Failed to parse file: " file-path)
                         {:error     (.getMessage e)
                          :file-path file-path}
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
