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
            [still.diff :as diff]
            [still.serialize :as serialize]
            [still.snapshot :as snapshot]
            #?@(:clj [[clojure.test :as t] [still.location :as location]
                      [still.rewrite :as rewrite]]
                :cljs [[cljs.test :as t :include-macros true]
                       [still.node-io]
                       [still.rewrite-cljs]])))

(defn in-test-context?
  "Check if we're currently inside a deftest context.

  Returns true if called within a clojure.test/deftest block, false otherwise."
  []
  ;; *testing-vars* is a dynamic var from clojure.test/cljs.test
  #?(:clj
       #_{:clj-kondo/ignore [:unresolved-var]}
       (seq t/*testing-vars*)
     :cljs (when (exists? t/get-current-env)
             (seq (:testing-vars (t/get-current-env))))))

(defn in-repl?
  "Check if we're currently in an interactive REPL session.

  Returns true if running in any REPL (nREPL, socket REPL, clojure.main),
  but false inside a test context.

  REPL detection works by checking if *repl* is bound, which is true for all
  standard Clojure REPLs but false when running as a compiled JAR or in
  test runners."
  []
  (and #?(:clj (try (when-let [repl-var (resolve '*repl*)]
                      (and (bound? repl-var) @repl-var))
                    (catch Exception _ false))
          :cljs false)
       (not (in-test-context?))))

#?(:clj
     (defn try-get-nrepl-file
       "Attempt to get the file path from nREPL middleware.

     When nREPL 1.5.1+ is used with a client that sends the :file parameter,
     it may be available in the nREPL message map even if *file* is nil."
       []
       (try
         ;; Try to resolve and dereference the nREPL *msg* var
         (when-let [msg-var (resolve 'nrepl.middleware.session/*msg*)]
           (when (bound? msg-var)
             (let [msg (deref msg-var)]
               (get msg :file))))
         (catch Exception _ nil))))

(defn ^:private handle-snapshot-mismatch
  "Handle a snapshot mismatch.

  Behaviour depends on configuration and context:
  - If auto-update is enabled, update the snapshot and return true
  - If in test context, use clojure.test/is to fail the test
  - If in REPL context, print a message and return false
  - Otherwise (not test, not REPL), throw AssertionError"
  [snapshot-key expected actual]
  (let [auto-update? (config/auto-update?)
        in-test?     (in-test-context?)
        in-repl?     (in-repl?)]
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
      in-repl? (do (println "✗ Snapshot mismatch:" snapshot-key)
                   (println (diff/mismatch-message snapshot-key
                                                   expected
                                                   actual
                                                   {:color? (config/color?)}))
                   false)
      ;; Not test, not REPL: throw AssertionError
      :else (throw (let [msg (diff/mismatch-message snapshot-key
                                                    expected
                                                    actual
                                                    {:color? (config/color?)})]
                     #?(:clj (AssertionError. msg)
                        :cljs (js/Error. msg)))))))

(defn ^:private handle-snapshot-match
  "Handle a snapshot match.

  Behaviour depends on context:
  - In test context: uses clojure.test/is to record the pass
  - In REPL context: prints a success message and returns true
  - Otherwise: returns true silently (assertion passed)"
  [snapshot-key expected actual]
  (let [in-test? (in-test-context?)
        in-repl? (in-repl?)]
    (cond in-test? (t/is (= expected actual)
                         (str "Snapshot matches: " snapshot-key))
          in-repl? (do (println "✓ Snapshot matches:" snapshot-key) true)
          :else true)))

(defn ^:private handle-new-snapshot
  "Handle creation of a new snapshot.

  Creates the snapshot file and provides appropriate feedback.

  Behaviour depends on context:
  - In test context: uses clojure.test/is to record the pass
  - In REPL context: prints a success message and returns true
  - Otherwise: returns true silently"
  [snapshot-key value]
  (snapshot/write-snapshot! snapshot-key value)
  (let [in-test? (in-test-context?)
        in-repl? (in-repl?)]
    (cond in-test? (t/is true (str "Snapshot created: " snapshot-key))
          in-repl? (do (println "✓ Snapshot created:" snapshot-key) true)
          :else true)))

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

  Outside deftest and REPL (assertion context):
  - Throws AssertionError on mismatch
  - Returns true on match
  - No output unless there's an error

  Arguments:
  - snapshot-key: Keyword identifying this snapshot (e.g., :user-creation)
  - value: The value to snapshot

  Enable/Disable:
  - Controlled by *assert* outside of deftest contexts
  - Inside deftest: Always enabled (ignores *assert* setting)
  - Outside deftest: When *assert* is false, snap returns true (pass-through)

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
    ;; => true

    ;; Disable snapshots
    (set! *assert* false)"
  [snapshot-key value]
  (if (and (not *assert*) (not (in-test-context?)))
    ;; If assertions are disabled AND not in test context, return true
    ;; (pass-through)
    true
    ;; Assertions enabled OR in test context - do normal processing
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

(defn ^:private compare-inline-snapshots
  "Common comparison logic for inline snapshots.

  Returns true if snapshots match, throws/fails otherwise.
  Handles test, REPL, and assertion contexts appropriately."
  [serialized-expected serialized-value location-info]
  (let [in-test? (in-test-context?)
        in-repl? (in-repl?)
        matches? (diff/equal? serialized-expected serialized-value)]
    (if matches?
      ;; Snapshots match
      (cond in-test? (t/is (= serialized-expected serialized-value)
                           "Inline snapshot matches")
            in-repl? (do (println "✓ Inline snapshot matches") true)
            :else true)
      ;; Snapshots don't match
      (let [location-str #?(:clj (when location-info
                                   (str " at "
                                        (location/location-string
                                         location-info)))
                            :cljs (when location-info
                                    (str " at " (:file location-info)
                                         ":" (:line location-info))))
            mismatch-msg (str "Inline snapshot mismatch" location-str
                              ":\n" (diff/diff-str serialized-expected
                                                   serialized-value))]
        (cond in-test? (t/is (= serialized-expected serialized-value)
                             mismatch-msg)
              in-repl?
              (do (println (str "✗ Inline snapshot mismatch" location-str))
                  (println (diff/diff-str serialized-expected serialized-value))
                  false)
              :else (throw #?(:clj (AssertionError. mismatch-msg)
                              :cljs (js/Error. mismatch-msg))))))))

(defn snap!-impl
  "Implementation of snap! comparison logic."
  [value expected location]
  (let [serialized-value    (serialize/serialize-value value)
        serialized-expected (serialize/serialize-value expected)]
    (compare-inline-snapshots serialized-expected serialized-value location)))

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
     - In test context: uses clojure.test/cljs.test is
     - In REPL context: returns boolean and prints messages
     - Outside test/REPL: throws AssertionError/js/Error on mismatch

     Enable/Disable:
     - Controlled by *assert* outside of deftest contexts
     - Inside deftest: Always enabled (ignores *assert* setting)
     - Outside deftest: When *assert* is false, snap! returns true (pass-through)

     Platform support:
     - JVM/Babashka: Full support including source editing
     - Node.js ClojureScript: Full support including source editing
     - Browser ClojureScript: Requires expected value (no filesystem access)

     Examples:
       ;; First run: source file will be edited
       (snap! (compute-result))
       ;; After edit, the line becomes:
       (snap! (compute-result) {:result 42})

       ;; Future runs: compares against inline value
       (snap! (compute-result) {:result 42})"
       ([value-expr]
        (let [compile-time-file (if (and *file* (not= *file* "NO_SOURCE_PATH"))
                                  *file*
                                  (when (:ns &env)
                                    (location/file-from-ns (:name (:ns &env)))))
              line (:line (meta &form))]
          (if (:ns &env)
            ;; ClojureScript target
            `(if (or *assert* (in-test-context?))
               (let [value#      ~value-expr
                     serialized# (serialize/serialize-value value#)]
                 (if (~'still.node-io/node-env?)
                   (let [absolute-path# (~'still.node-io/resolve-file-path
                                         ~compile-time-file)]
                     (if absolute-path#
                       (let [result# (~'still.rewrite-cljs/add-expected-value!
                                      absolute-path#
                                      ~line
                                      serialized#)]
                         (case (:status result#)
                           :inserted
                           (do (when-not (in-test-context?)
                                 (println "✓ Inline snapshot created at"
                                          (str ~compile-time-file ":" ~line)))
                               (if (in-test-context?)
                                 (~'cljs.test/is true (:message result#))
                                 true))
                           :updated
                           (do (when-not (in-test-context?)
                                 (println "✓ Inline snapshot updated at"
                                          (str ~compile-time-file ":" ~line)))
                               (if (in-test-context?)
                                 (~'cljs.test/is true (:message result#))
                                 true))
                           (throw (ex-info "Failed to update source file"
                                           result#))))
                       (throw (ex-info "snap! could not resolve source file"
                                       {:file ~compile-time-file
                                        :line ~line}))))
                   (throw
                    (ex-info
                     (str
                      "snap! automatic editing not supported in this environment.\n\n"
                      "Automatic source editing requires Node.js.\n\n"
                      "Solutions:\n"
                      "  1. Run tests under Node.js (e.g. bb test-cljs)\n"
                      "  2. Provide expected value manually: (snap! expr expected)")
                     {:value value#}))))
               true)
            ;; JVM/Babashka target
            `(if (or *assert* (in-test-context?))
               (let [runtime-file#  (or ~compile-time-file (try-get-nrepl-file))
                     absolute-path# (location/resolve-file-path runtime-file#)
                     value#         ~value-expr
                     serialized#    (serialize/serialize-value value#)
                     location#      {:absolute-path absolute-path#
                                     :file          runtime-file#
                                     :line          ~line}]
                 (if absolute-path#
                   (let [result# (rewrite/add-expected-value! absolute-path#
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
                       (throw (ex-info "Failed to update source file"
                                       result#))))
                   (throw
                    (ex-info
                     (str
                      "Cannot use snap! without expected value in REPL context.\n\n"
                      "When evaluating code in the REPL (not loading from file), "
                      "snap! cannot determine the source file location.\n\n"
                      "This may happen if:\n"
                      "  - Your editor doesn't send the :file parameter during eval\n"
                      "  - You're using nREPL < 1.5.1 (upgrade recommended)\n"
                      "  - You're using an older REPL client\n\n" "Solutions:\n"
                      "  1. Load the file instead of evaluating (C-c C-k in CIDER)\n"
                      "  2. Use (snap :key value) for REPL-based testing\n"
                      "  3. Provide expected value manually: (snap! expr expected)\n"
                      "  4. Upgrade to nREPL 1.5.1+ and ensure your editor sends :file")
                     {:context :repl
                      :file    runtime-file#
                      :line    ~line}))))
               true))))
       ([value-expr expected]
        (let [compile-time-file (if (and *file* (not= *file* "NO_SOURCE_PATH"))
                                  *file*
                                  (when (:ns &env)
                                    (location/file-from-ns (:name (:ns &env)))))
              line (:line (meta &form))]
          (if (:ns &env)
            ;; ClojureScript target
            `(if (or *assert* (in-test-context?))
               (snap!-impl ~value-expr
                           ~expected
                           {:file ~compile-time-file
                            :line ~line})
               true)
            ;; JVM/Babashka target
            `(if (or *assert* (in-test-context?))
               (let [runtime-file#  (or ~compile-time-file (try-get-nrepl-file))
                     absolute-path# (location/resolve-file-path runtime-file#)]
                 (snap!-impl ~value-expr
                             ~expected
                             {:absolute-path absolute-path#
                              :file          runtime-file#
                              :line          ~line}))
               true))))))

(comment
  ;; Example usage in REPL
  ;; Create a new snapshot
  (snap :user-data
        {:id   123
         :name "Alice"
         :role :admin})
  ;; => ✓ Snapshot created: :user-data => true. Match an existing snapshot
  (snap :user-data
        {:id   123
         :name "Alice"
         :role :admin})
  ;; => ✓ Snapshot matches: :user-data => true. Mismatch
  (snap :user-data
        {:id   123
         :name "Bob"
         :role :admin})
  ;; => ✗ Snapshot mismatch: :user-data
  ;; => (prints diff)
  ;; => false. Auto-update mode
  (config/merge-override! {:auto-update? true})
  (snap :user-data
        {:id   123
         :name "Bob"
         :role :admin})
  ;; => ✓ Snapshot updated: :user-data => true
  ;; Inline snapshot (with expected value)
  #?(:clj (snap! (+ 1 2) 3))
  ;; => ✓ Inline snapshot matches => true
)
