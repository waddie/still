;; Copyright (c) 2026 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.rewrite-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [still.rewrite :as rw]))

(def ^:private tmp-file (str (fs/temp-dir) "/still_rewrite_test.cljc"))

(defn- reset-fixture
  [f]
  ;; Locating a snap! call is now stateless (by value-expression
  ;; signature), so there is no module-level offset to reset between tests;
  ;; just clean the temp file before and after.
  (fs/delete-if-exists tmp-file)
  (f)
  (fs/delete-if-exists tmp-file))

(use-fixtures :each reset-fixture)

(defn- sig
  "Canonical value-expression signature, computed exactly as the snap! macro
  does, so tests exercise the same matching key production code produces."
  [form]
  (binding [*print-length* nil
            *print-level*  nil]
    (pr-str form)))

(defn- expected-by-value
  "Map of each snap! call's value-expression string to its (post-edit)
  expected-value string, for asserting each call kept its own value."
  [file]
  (into {} (map (juxt :value :expected)) (rw/find-all-snap!-calls file)))

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

(deftest test-nil-sig-uses-line-lookup
  (testing "3-arg call (nil signature) locates purely by line number"
    (spit tmp-file "(snap! (alpha))\n(snap! (beta))\n")
    (let [result (rw/add-expected-value! tmp-file 2 "beta-val")]
      (is (= :inserted (:status result)))
      (is (= "(snap! (alpha))\n(snap! (beta) \"beta-val\")\n"
             (slurp tmp-file))))))

(deftest test-multi-snap-single-line-values
  (testing "two back-to-back snap! calls, single-line values — no line shift"
    (spit tmp-file "(snap! (first-call))\n(snap! (second-call))\n")
    (let [r1 (rw/add-expected-value! tmp-file 1 42 (sig '(first-call)))
          r2 (rw/add-expected-value! tmp-file 2 "hello" (sig '(second-call)))]
      (is (= :inserted (:status r1)))
      (is (= :inserted (:status r2)))
      (is (= "(snap! (first-call) 42)\n(snap! (second-call) \"hello\")\n"
             (slurp tmp-file))))))

(deftest test-multiline-insert-above-later-target
  (testing "a multi-line insert above a not-yet-edited call below it"
    ;; The lower call is compiled with line 2. Inserting a multi-line value
    ;; into the call above it shifts the lower call's physical position
    ;; down. Signature matching must still find it at its original
    ;; compile-time line.
    (spit tmp-file "(snap! (top))\n(snap! (bottom))\n")
    (rw/add-expected-value! tmp-file 1 "a\nb\nc" (sig '(top)))
    (let [r        (rw/add-expected-value! tmp-file
                                           2
                                           "bottom-val"
                                           (sig '(bottom)))
          by-value (expected-by-value tmp-file)]
      (is (= :inserted (:status r)) "lower call found despite line shift")
      (is (str/includes? (get by-value "(bottom)") "bottom-val"))
      (is (str/includes? (get by-value "(top)") "a")))))

(deftest test-out-of-order-insert
  (testing "three snap! calls edited out of file order keep their own values"
    ;; The exact drift shape from
    ;; progress-docs/snap-out-of-order-corruption/repro-direct.clj: edits
    ;; in order 3, 1, 2, with a multi-line value in the middle edit. Under
    ;; the old scalar-offset model this silently swapped neighbours'
    ;; values.
    (spit tmp-file
          (str "(snap! (first-call))\n"
               "(snap! (second-call))\n"
               "(snap! (third-call))\n"))
    (rw/add-expected-value! tmp-file
                            3
                            "third-line-1\nthird-line-2"
                            (sig '(third-call)))
    (rw/add-expected-value! tmp-file
                            1
                            "first-line-1\nfirst-line-2\nfirst-line-3"
                            (sig '(first-call)))
    (rw/add-expected-value! tmp-file 2 "second-line-1" (sig '(second-call)))
    (let [by-value (expected-by-value tmp-file)]
      (is (some? (get by-value "(first-call)")) "first-call is not left bare")
      (is (str/includes? (get by-value "(first-call)") "first-line-1")
          "first-call keeps its own value")
      (is (str/includes? (get by-value "(second-call)") "second-line-1")
          "second-call keeps its own value, not a neighbour's")
      (is (str/includes? (get by-value "(third-call)") "third-line-1")
          "third-call's value is not overwritten"))))

(deftest test-identical-value-exprs
  (testing "identical value expressions are disambiguated, edited out of order"
    ;; Both calls share the value expression (f). The bare-preference rule
    ;; (an already-edited sibling is no longer bare) keeps them distinct
    ;; even
    ;; when edited bottom-up.
    (spit tmp-file "(snap! (f))\n(snap! (f))\n")
    (rw/add-expected-value! tmp-file 2 "B" (sig '(f)))
    (rw/add-expected-value! tmp-file 1 "A" (sig '(f)))
    (is (= "(snap! (f) \"A\")\n(snap! (f) \"B\")\n" (slurp tmp-file)))))

(deftest test-multi-snap-multiline-first-value
  (testing
    "first snap! inserts a multi-line value, shifting subsequent line numbers"
    ;; The second snap! is compiled with line 2. After the first edit
    ;; inserts a multi-line value, the second call's actual position shifts
    ;; downward. Signature matching must still locate it so :not-found is
    ;; never returned.
    (spit tmp-file "(snap! (first-call))\n(snap! (second-call))\n")
    (let [multi-line-val {:address {:city   "Springfield"
                                    :street "123 Main St"}
                          :email   "alice@example.com"
                          :name    "Alice"}
          r1 (rw/add-expected-value! tmp-file
                                     1
                                     multi-line-val
                                     (sig '(first-call)))
          r2 (rw/add-expected-value! tmp-file 2 "result2" (sig '(second-call)))]
      (is (= :inserted (:status r1)) "first snap! should be inserted")
      (is (= :inserted (:status r2))
          "second snap! must be found despite line shift")
      (is (str/includes? (slurp tmp-file) "(snap! (second-call) \"result2\")")
          "second snap! expected value written correctly"))))

(deftest test-rerun-updates-existing-values
  (testing "a second evaluation pass updates both calls by value, not by offset"
    ;; First pass inserts values (shifting line numbers). Second pass mocks
    ;; a recompilation that sees the post-edit line numbers. Because calls
    ;; are located by value-expression signature, re-runs need no offset
    ;; reset.
    (spit tmp-file "(snap! (first-call))\n(snap! (second-call))\n")
    (let [multi {:a 1
                 :b 2
                 :c 3
                 :d 4
                 :e 5
                 :f 6
                 :g 7
                 :h 8}]
      (rw/add-expected-value! tmp-file 1 multi (sig '(first-call)))
      (rw/add-expected-value! tmp-file 2 "x" (sig '(second-call)))
      (let [content    (slurp tmp-file)
            lines      (str/split-lines content)
            snap-lines (keep-indexed (fn [i l]
                                       (when (str/includes? l "snap!") (inc i)))
                                     lines)]
        (is (= 2 (count snap-lines)) "still two snap! calls in file")
        (let [[l1 l2] snap-lines
              r1      (rw/add-expected-value! tmp-file
                                              l1
                                              multi
                                              (sig '(first-call)))
              r2      (rw/add-expected-value! tmp-file
                                              l2
                                              "x"
                                              (sig '(second-call)))]
          (is (= :updated (:status r1)) "re-run updates first snap!")
          (is (= :updated (:status r2)) "re-run updates second snap!"))))))
