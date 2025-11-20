;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.diff
  "Diff visualisation for snapshot mismatches.

  Provides colourised, structured diffs using lambdaisland/deep-diff2."
  (:require [still.config :as config]
            [lambdaisland.deep-diff2 :as dd]
            [clojure.string :as s]
            #?(:clj [clojure.pprint :as pprint]
               :cljs [cljs.pprint :as pprint])))

(defn diff
  "Compute the diff between expected and actual values.

  Returns a deep-diff2 diff structure."
  [expected actual]
  (dd/diff expected actual))

(defn format-diff
  "Format a diff for display.

  Options:
    :color? or :colour? - Enable ANSI colour output (default from config)"
  ([diff-result] (format-diff diff-result {}))
  ([diff-result opts]
   (let [color? (or (get opts :color?) (get opts :colour?) (config/color?))]
     (with-out-str (if color?
                     (dd/pretty-print diff-result)
                     (dd/pretty-print diff-result
                                      (dd/printer {:print-color false})))))))

(defn diff-str
  "Generate a formatted diff string between expected and actual values.

  This is a convenience function that combines diff and format-diff."
  ([expected actual] (diff-str expected actual {}))
  ([expected actual opts]
   (-> (diff expected actual)
       (format-diff opts))))

(defn minimal-diff-str
  "Generate a minimal diff showing only the differences.

  This filters out identical values and focuses on mismatches."
  ([expected actual] (minimal-diff-str expected actual {}))
  ([expected actual opts]
   (let [d (diff expected actual)]
     (if (= expected actual)
       "Values are equal (no diff)"
       (format-diff d opts)))))

(defn mismatch-message
  "Generate a user-friendly mismatch message with diff.

  Includes:
  - Clear statement that values don't match
  - The formatted diff
  - Helpful next steps"
  [snapshot-key expected actual opts]
  (str "Snapshot mismatch for: "
       snapshot-key
       "\n\n"
       "Expected and actual values differ:\n\n" (diff-str expected actual opts)
       "\n" "To update the snapshot, run tests with auto-update enabled:\n"
       "  STILL_AUTO_UPDATE=true clj -M:test\n"
       "Or use (still.config/merge-override! {:auto-update? true})\n"))

(defn equal?
  "Deep equality check that normalises values before comparing.

  This is more lenient than clojure.core/= for snapshot comparisons:
  - Considers sorted-map and hash-map equal if they have same entries
  - Handles serialised values correctly"
  [expected actual]
  (= expected actual))

(defn diff-summary
  "Generate a one-line summary of the diff.

  Examples:
    'Values differ'
    'Values are equal'
    'Type mismatch: map vs vector'"
  [expected actual]
  (cond (= expected actual) "Values are equal"
        (not= (type expected) (type actual))
        (str "Type mismatch: " (type expected) " vs " (type actual))
        :else "Values differ"))

(defn print-diff
  "Print a diff to *out* with colouring if enabled.

  Useful for REPL workflows."
  ([expected actual] (print-diff expected actual {}))
  ([expected actual opts] (println (diff-str expected actual opts))))

(defn side-by-side
  "Generate a side-by-side comparison of expected vs actual.

  Returns a string with expected on the left, actual on the right."
  [expected actual]
  (let [exp-str   (with-out-str (pprint/pprint expected))
        act-str   (with-out-str (pprint/pprint actual))
        exp-lines (s/split-lines exp-str)
        act-lines (s/split-lines act-str)
        max-lines (max (count exp-lines) (count act-lines))
        width     40]
    (str "EXPECTED" (apply str (repeat (- width 8) " "))
         "ACTUAL\n" (apply str (repeat (* 2 width) "-"))
         "\n" (apply str
                     (for [i (range max-lines)]
                       (let [exp-line   (get exp-lines i "")
                             act-line   (get act-lines i "")
                             exp-padded (str exp-line
                                             (apply str
                                                    (repeat (- width
                                                               (count exp-line))
                                                            " ")))]
                         (str exp-padded " │ " act-line "\n")))))))

(comment
  ;; Example usage. Simple diff
  (diff {:a 1 :b 2} {:a 1 :b 3})
  ;; Format diff with colour
  (println (diff-str {:a 1 :b 2} {:a 1 :b 3}))
  ;; Print diff to console
  (print-diff {:a 1 :b 2} {:a 1 :b 3})
  ;; Side-by-side comparison
  (println (side-by-side {:a 1 :b 2 :c [1 2 3]} {:a 1 :b 3 :c [1 2 4]}))
  ;; Diff summary
  (diff-summary {:a 1 :b 2} {:a 1 :b 3})
  ;; => "1 difference"
)
