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

(deftest test-snap-disabled
  (testing "snap passes when disabled"
    (config/override! {:enabled? false})
    ;; Even with wrong value, should pass when disabled
    (is (true? (still/snap :disabled-test {:value 999})))
    (config/override! {})))

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
                           {:string "hello"
                            :number 42
                            :vector [1 2 3]
                            :map {:nested true}
                            :keyword :test
                            :boolean true
                            :nil nil})))
    ;; Verify it matches on second run
    (is (true? (still/snap :test-serialization
                           {:string "hello"
                            :number 42
                            :vector [1 2 3]
                            :map {:nested true}
                            :keyword :test
                            :boolean true
                            :nil nil})))
    (snapshot/delete-snapshot! :test-serialization)))

(comment
  ;; Manual testing in REPL
  ;; Test snap in REPL (should print messages)
  (still/snap :repl-test {:value 123})
  ;; Test snap! in REPL
  #?(:clj (still/snap! (+ 1 2)))
  ;; Check if we're in test context
  (still/in-test-context?)
  ;; => false (in REPL)
  ;; Inside a deftest, it returns true
  (deftest check-context (is (true? (still/in-test-context?)))))
