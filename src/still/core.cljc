;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.core
  "Core snapshot testing API for Still.

  Provides two main functions:
  - snap: Filesystem-based snapshot testing with dual behaviour (test vs REPL)
  - snap!: Inline snapshot testing with automatic source code editing"
  (:require [still.config :as config]
            [still.snapshot :as snapshot]
            [still.diff :as diff]
            [still.serialize :as serialize]
            #?@(:clj [[clojure.test :as t] [still.location :as location]
                      [still.rewrite :as rewrite]]
                :cljs [[cljs.test :as t :include-macros true]])))

(defn in-test-context?
  "Check if we're currently inside a deftest context.

  Returns true if called within a clojure.test/deftest block, false otherwise."
  []
  ;; *testing-vars* is a dynamic var from clojure.test/cljs.test
  #?(:clj
       #_{:clj-kondo/ignore [:unresolved-var]}
       (seq t/*testing-vars*)
     :cljs
       #_{:clj-kondo/ignore [:unresolved-var]}
       (seq t/*testing-vars*)))

(defn- handle-snapshot-mismatch
  "Handle a snapshot mismatch.

  Behaviour depends on configuration and context:
  - If auto-update is enabled, update the snapshot and return true
  - If in test context, use clojure.test/is to fail the test
  - If in REPL context, print a message and return false"
  [snapshot-key expected actual]
  (let [auto-update? (config/auto-update?)
        in-test? (in-test-context?)]
    (cond
      ;; Auto-update mode: update snapshot and pass
      auto-update? (do (snapshot/update-snapshot! snapshot-key actual)
                       (when-not in-test?
                         (println "✓ Snapshot updated:" snapshot-key))
                       true)
      ;; Test context: use clojure.test/is for assertion
      in-test? (t/is (= expected actual)
                     (diff/mismatch-message snapshot-key
                                            expected
                                            actual
                                            {:color? (config/color?)}))
      ;; REPL context: print message and return false
      :else (do (println "✗ Snapshot mismatch:" snapshot-key)
                (println (diff/mismatch-message snapshot-key
                                                expected
                                                actual
                                                {:color? (config/color?)}))
                false))))

(defn- handle-snapshot-match
  "Handle a snapshot match.

  In REPL context, prints a success message.
  In test context, uses clojure.test/is to record the pass."
  [snapshot-key expected actual]
  (let [in-test? (in-test-context?)]
    (if in-test?
      (t/is (= expected actual) (str "Snapshot matches: " snapshot-key))
      (do (println "✓ Snapshot matches:" snapshot-key) true))))

(defn- handle-new-snapshot
  "Handle creation of a new snapshot.

  Creates the snapshot file and provides appropriate feedback."
  [snapshot-key value]
  (snapshot/write-snapshot! snapshot-key value)
  (let [in-test? (in-test-context?)]
    (when-not in-test? (println "✓ Snapshot created:" snapshot-key))
    (if in-test? (t/is true (str "Snapshot created: " snapshot-key)) true)))

(defn snap
  "Filesystem-based snapshot testing.

  Compares a value against a stored snapshot. Behaviour adapts based on context:

  Inside deftest (test context):
  - Uses clojure.test/is for assertions
  - Failures appear in test runner output
  - Integrates with CI/CD pipelines

  Outside deftest (REPL context):
  - Returns boolean (true if match, false if mismatch)
  - Prints friendly messages to stdout
  - No test framework overhead

  Arguments:
  - snapshot-key: Keyword identifying this snapshot (e.g., :user-creation)
  - value: The value to snapshot

  Configuration:
  - :auto-update? true: Automatically update mismatched snapshots
  - :snapshot-dir: Directory for snapshot files (default: test/still)

  Examples:
    ;; In a test
    (deftest user-creation-test
      (let [user (create-user {:name \"Alice\"})]
        (snap :user-creation user)))

    ;; In the REPL
    (snap :api-response (fetch-data))
    ;; => ✓ Snapshot matches: :api-response
    ;; => true"
  [snapshot-key value]
  (if-not (config/enabled?)
    ;; If snapshots are disabled, just return true (pass-through)
    (if (in-test-context?) (t/is true "Snapshots disabled") true)
    ;; Snapshots enabled - do normal processing
    (let [serialized-value (serialize/serialize-value value)]
      (if (snapshot/snapshot-exists? snapshot-key)
        ;; Compare with existing snapshot
        (let [stored-snapshot (snapshot/read-snapshot snapshot-key)]
          (if (diff/equal? stored-snapshot serialized-value)
            (handle-snapshot-match snapshot-key
                                   stored-snapshot
                                   serialized-value)
            (handle-snapshot-mismatch snapshot-key
                                      stored-snapshot
                                      serialized-value)))
        ;; Create new snapshot
        (handle-new-snapshot snapshot-key serialized-value)))))

(defn- compare-inline-snapshots
  "Common comparison logic for inline snapshots.

  Returns true if snapshots match, false otherwise.
  Handles both test and REPL contexts appropriately."
  [serialized-expected serialized-value location-info]
  (let [in-test? (in-test-context?)
        matches? (diff/equal? serialized-expected serialized-value)]
    (if matches?
      ;; Snapshots match
      (if in-test?
        (t/is (= serialized-expected serialized-value)
              "Inline snapshot matches")
        (do (println "✓ Inline snapshot matches") true))
      ;; Snapshots don't match
      (let [mismatch-msg
            (if location-info
              (str "Inline snapshot mismatch"
                   #?(:clj (str " at "
                                (location/location-string location-info)))
                   ":\n" (diff/diff-str serialized-expected serialized-value))
              (str "Inline snapshot mismatch:\n"
                   (diff/diff-str serialized-expected serialized-value)))]
        (if in-test?
          (t/is (= serialized-expected serialized-value) mismatch-msg)
          (do (println (if location-info
                         #?(:clj (str "✗ Inline snapshot mismatch at "
                                      (location/location-string location-info))
                            :cljs "✗ Inline snapshot mismatch")
                         "✗ Inline snapshot mismatch"))
              (println (diff/diff-str serialized-expected serialized-value))
              false))))))

#?(:clj (defn snap!-impl
          "Implementation of snap! comparison logic for JVM."
          [value expected location]
          (let [serialized-value (serialize/serialize-value value)
                serialized-expected (serialize/serialize-value expected)]
            (compare-inline-snapshots serialized-expected
                                      serialized-value
                                      location))))

#?(:clj
     (defmacro snap!
       "Inline snapshot testing with automatic source editing.

     Like snap, but stores the expected value directly in the source code instead
     of in a separate file. When called without an expected value, automatically
     edits the source file to add the value.

     Arguments:
     - value-expr: The expression to snapshot
     - expected (optional): The expected value (typically added by snap! itself)

     Behaviour:
     - If expected is provided: compares value against expected
     - If expected is not provided: edits source file to add value as expected
     - In test context: uses clojure.test/is
     - In REPL context: returns boolean and prints messages

     Examples:
       ;; First run: source file will be edited
       (snap! (compute-result))
       ;; After edit, the line becomes:
       (snap! (compute-result) {:result 42})

       ;; Future runs: compares against inline value
       (snap! (compute-result) {:result 42})

     Note: This feature requires write access to source files and only works on JVM.
     It uses rewrite-clj to preserve formatting and comments."
       ([value-expr]
        ;; No expected value - need to edit source
        (let [file *file*
              line (:line (meta &form))
              absolute-path (location/resolve-file-path file)]
          `(let [value# ~value-expr
                 serialized# (serialize/serialize-value value#)
                 location#
                 {:file ~file :line ~line :absolute-path ~absolute-path}]
             (if ~absolute-path
               (let [result# (rewrite/add-expected-value! ~absolute-path
                                                          ~line
                                                          serialized#)]
                 (case (:status result#)
                   :inserted (do (when-not (in-test-context?)
                                   (println "✓ Inline snapshot created at"
                                            (location/location-string
                                             location#)))
                                 (if (in-test-context?)
                                   (t/is true (:message result#))
                                   true))
                   :updated (do (when-not (in-test-context?)
                                  (println "✓ Inline snapshot updated at"
                                           (location/location-string
                                            location#)))
                                (if (in-test-context?)
                                  (t/is true (:message result#))
                                  true))
                   (:not-found :error)
                   (throw (ex-info "Failed to update source file" result#))))
               (throw (ex-info (str "Cannot resolve file path: " ~file)
                               {:file ~file :line ~line}))))))
       ([value-expr expected]
        ;; Expected value provided - compare
        (let [file *file*
              line (:line (meta &form))
              absolute-path (location/resolve-file-path file)]
          `(snap!-impl
            ~value-expr
            ~expected
            {:file ~file :line ~line :absolute-path ~absolute-path})))))

#?(:cljs
     (defmacro snap!
       "Inline snapshot testing (limited ClojureScript support).

     Note: Automatic source editing is not available in ClojureScript.
     You must manually provide the expected value."
       ([value-expr]
        `(throw
          (ex-info
           "snap! automatic editing not supported in ClojureScript. Please provide expected value manually."
           {:value ~value-expr})))
       ([value-expr expected]
        `(let [serialized-value# (serialize/serialize-value ~value-expr)
               serialized-expected# (serialize/serialize-value ~expected)]
           (compare-inline-snapshots serialized-expected#
                                     serialized-value#
                                     nil)))))

(comment
  ;; Example usage in REPL
  ;; Create a new snapshot
  (snap :user-data {:id 123 :name "Alice" :role :admin})
  ;; => ✓ Snapshot created: :user-data => true. Match an existing snapshot
  (snap :user-data {:id 123 :name "Alice" :role :admin})
  ;; => ✓ Snapshot matches: :user-data => true. Mismatch
  (snap :user-data {:id 123 :name "Bob" :role :admin})
  ;; => ✗ Snapshot mismatch: :user-data
  ;; => (prints diff)
  ;; => false. Auto-update mode
  (config/merge-override! {:auto-update? true})
  (snap :user-data {:id 123 :name "Bob" :role :admin})
  ;; => ✓ Snapshot updated: :user-data => true
  ;; Inline snapshot (with expected value)
  #?(:clj (snap! (+ 1 2) 3))
  ;; => ✓ Inline snapshot matches => true
)
