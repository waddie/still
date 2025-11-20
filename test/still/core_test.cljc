;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.core-test
  "Tests for still.core snapshot testing functionality."
  (:require [still.core :as still]
            [still.config :as config]
            [still.snapshot :as snapshot]
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer-macros [deftest testing is]])))

(deftest test-in-test-context
  (testing "in-test-context? returns truthy inside deftest"
    (is (still/in-test-context?))))

(deftest test-in-repl
  (testing "in-repl? returns false inside deftest"
    ;; Even if *1 is bound (in REPL), in-repl? returns false inside tests
    ;; because test context takes precedence
    (is (not (still/in-repl?)))))

(deftest test-snap-basic
  (testing "snap creates and matches snapshots"
    ;; Clean up any existing snapshot
    (when (snapshot/snapshot-exists? :test-snap-basic)
      (snapshot/delete-snapshot! :test-snap-basic))
    ;; First call creates snapshot
    (is (true? (still/snap :test-snap-basic {:id 123 :name "Test"})))
    ;; Second call matches
    (is (true? (still/snap :test-snap-basic {:id 123 :name "Test"})))
    ;; Clean up
    (snapshot/delete-snapshot! :test-snap-basic)))

(deftest test-snap-with-config-override
  (testing "snap respects config overrides"
    ;; Set custom snapshot directory
    (config/override! {:snapshot-dir "test/custom-snapshots"})
    (when (snapshot/snapshot-exists? :test-override)
      (snapshot/delete-snapshot! :test-override))
    (is (true? (still/snap :test-override {:data "test"})))
    ;; Clean up
    (snapshot/delete-snapshot! :test-override)
    (config/override! {})))

(deftest test-snap-with-assert-disabled
  (testing "snap passes when *assert* is false"
    (binding [*assert* false]
      ;; Even with non-existent snapshot, should pass when *assert* is
      ;; false
      (is (true? (still/snap :disabled-test {:value 999}))))))

#?(:clj (deftest test-snap!-with-expected
          (testing "snap! compares against expected value"
            ;; With matching values
            (is (true? (still/snap! (+ 1 2) 3)))
            ;; With map value
            (is (true? (still/snap! {:a 1 :b 2} {:a 1 :b 2}))))))

(deftest test-serialization
  (testing "values are properly serialized"
    (when (snapshot/snapshot-exists? :test-serialization)
      (snapshot/delete-snapshot! :test-serialization))
    ;; Test with various data types
    (is (true? (still/snap :test-serialization
                           {:string  "hello"
                            :number  42
                            :vector  [1 2 3]
                            :map     {:nested true}
                            :keyword :test
                            :boolean true
                            :nil     nil})))
    ;; Verify it matches on second run
    (is (true? (still/snap :test-serialization
                           {:string  "hello"
                            :number  42
                            :vector  [1 2 3]
                            :map     {:nested true}
                            :keyword :test
                            :boolean true
                            :nil     nil})))
    (snapshot/delete-snapshot! :test-serialization)))

(comment
  ;; Manual testing in REPL for three-context behavior === REPL Context
  ;; ===
  ;; Test snap in REPL (should print friendly messages and return boolean)
  (still/snap :repl-test {:value 123})
  ;; => ✓ Snapshot created: :repl-test => true
  (still/snap :repl-test {:value 123})
  ;; => ✓ Snapshot matches: :repl-test => true
  (still/snap :repl-test {:value 456})
  ;; => ✗ Snapshot mismatch: :repl-test
  ;; => (prints diff)
  ;; => false. Test snap! in REPL
  #?(:clj (still/snap! (+ 1 2) 3))
  ;; => ✓ Inline snapshot matches => true === Test Context ===. Inside
  ;; deftest, uses clojure.test/is for integration with test runners
  (deftest example-test (is (still/snap :test-example {:data "test"})))
  ;; === Assertion Context (not test, not REPL) === When code is loaded
  ;; outside of REPL/test (e.g., during namespace loading), snap/snap!
  ;; throw AssertionError on mismatch instead of printing. This prevents
  ;; noise during test runs while maintaining assertion semantics ===
  ;; Disable with *assert* === Set *assert* to false to disable all
  ;; snapshots (compiles out snap!
  ;; macro)
  (set! *assert* false)
  (still/snap :any-key {:any "value"})
  ;; => true (always passes, no checking)
  (set! *assert* true)
  ;; Check context detection
  (still/in-test-context?)
  ;; => false (in REPL)
  (still/in-repl?)
  ;; => true (in REPL)
  ;; Inside a deftest, context detection changes
  (deftest check-context
    (is (true? (still/in-test-context?)))
    (is (false? (still/in-repl?)))))
