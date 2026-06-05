;; Copyright (c) 2026 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.rewrite-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [still.rewrite :as rw]))

(def ^:private tmp-file
  (str (System/getProperty "java.io.tmpdir") "/still_rewrite_test.cljc"))

(defn- reset-fixture
  [f]
  (io/delete-file tmp-file true)
  ;; Reset the module-level offset atom between tests via a round-trip
  ;; through a file that has no snap! at the adjusted line, forcing
  ;; stale-offset reset.
  (f)
  (io/delete-file tmp-file true))

(use-fixtures :each reset-fixture)

(deftest test-insert-single
  (testing "inserts expected value into a bare snap! call"
    (spit tmp-file "(snap! (+ 1 2))\n")
    (let [result (rw/add-expected-value! tmp-file 1 3)]
      (is (= :inserted (:status result)))
      (is (= "(snap! (+ 1 2) 3)\n" (slurp tmp-file))))))

(deftest test-update-existing
  (testing "replaces an existing expected value"
    (spit tmp-file "(snap! (+ 1 2) 99)\n")
    (let [result (rw/add-expected-value! tmp-file 1 3)]
      (is (= :updated (:status result)))
      (is (= "(snap! (+ 1 2) 3)\n" (slurp tmp-file))))))

(deftest test-not-found
  (testing "returns :not-found when no snap! is at that line"
    (spit tmp-file "(+ 1 2)\n")
    (let [result (rw/add-expected-value! tmp-file 1 3)]
      (is (= :not-found (:status result))))))

(deftest test-multi-snap-single-line-values
  (testing "two back-to-back snap! calls, single-line values — no line shift"
    (spit tmp-file "(snap! (first-call))\n(snap! (second-call))\n")
    (let [r1 (rw/add-expected-value! tmp-file 1 42)
          r2 (rw/add-expected-value! tmp-file 2 "hello")]
      (is (= :inserted (:status r1)))
      (is (= :inserted (:status r2)))
      (is (= "(snap! (first-call) 42)\n(snap! (second-call) \"hello\")\n"
             (slurp tmp-file))))))

(deftest test-multi-snap-multiline-first-value
  (testing
    "first snap! inserts a multi-line value, shifting subsequent line numbers"
    ;; The second snap! is compiled with line 2. After the first edit
    ;; inserts a multi-line value, the second call's actual position shifts
    ;; downward. The offset tracking must adjust the lookup so :not-found
    ;; is never returned.
    (spit tmp-file "(snap! (first-call))\n(snap! (second-call))\n")
    (let [multi-line-val {:address {:city   "Springfield"
                                    :street "123 Main St"}
                          :email   "alice@example.com"
                          :name    "Alice"}
          r1 (rw/add-expected-value! tmp-file 1 multi-line-val)
          r2 (rw/add-expected-value! tmp-file 2 "result2")]
      (is (= :inserted (:status r1)) "first snap! should be inserted")
      (is (= :inserted (:status r2))
          "second snap! must be found despite line shift")
      (is (str/includes? (slurp tmp-file) "(snap! (second-call) \"result2\")")
          "second snap! expected value written correctly"))))

(deftest test-stale-offset-reset
  (testing
    "offset resets when file is re-evaluated with fresh compile-time lines"
    ;; Simulate first run: both snap! calls resolved and file modified.
    ;; Simulate second run: recompiled with the post-edit line numbers.
    (spit tmp-file "(snap! (first-call))\n(snap! (second-call))\n")
    (let [multi {:a 1
                 :b 2
                 :c 3
                 :d 4
                 :e 5
                 :f 6
                 :g 7
                 :h 8}]
      ;; First evaluation pass
      (rw/add-expected-value! tmp-file 1 multi)
      (rw/add-expected-value! tmp-file 2 "x")
      ;; File now has expected values; line numbers have shifted. Second
      ;; evaluation pass: mock re-compilation with current file's line
      ;; numbers.
      (let [content    (slurp tmp-file)
            lines      (str/split-lines content)
            ;; Find lines with snap! to get the actual current line numbers
            snap-lines (keep-indexed (fn [i l]
                                       (when (str/includes? l "snap!") (inc i)))
                                     lines)]
        (is (= 2 (count snap-lines)) "still two snap! calls in file")
        ;; These line numbers reflect the post-edit file (as a
        ;; re-compilation would see)
        (let [[l1 l2] snap-lines
              r1      (rw/add-expected-value! tmp-file l1 multi)
              r2      (rw/add-expected-value! tmp-file l2 "x")]
          (is (= :updated (:status r1)) "re-run updates first snap!")
          (is (= :updated (:status r2)) "re-run updates second snap!"))))))
