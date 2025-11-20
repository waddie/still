;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.update
  "Snapshot management utilities for bulk updates, pruning, and review.

  Provides CLI-friendly operations for managing snapshots."
  (:require [still.config :as config]
            [still.snapshot :as snapshot]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.string :as str])))

(defn list-all-snapshots
  "List all snapshots in the snapshot directory.

  Returns a sequence of snapshot info maps with :key, :path, and :name."
  []
  (snapshot/list-snapshots))

(defn snapshot-summary
  "Generate a summary of all snapshots.

  Returns a map with:
  - :total-count - Total number of snapshots
  - :snapshots - List of snapshot info maps
  - :snapshot-dir - Directory where snapshots are stored"
  []
  (let [snapshots (list-all-snapshots)]
    {:total-count (count snapshots)
     :snapshots snapshots
     :snapshot-dir (config/snapshot-dir)}))

(defn print-summary
  "Print a human-readable summary of all snapshots."
  []
  (let [{:keys [total-count snapshots snapshot-dir]} (snapshot-summary)]
    (println "Snapshot Summary")
    (println "================")
    (println "Directory:" snapshot-dir)
    (println "Total snapshots:" total-count)
    (println)
    (doseq [{:keys [key path]} snapshots]
      (println " -" key)
      (println "   " path))
    (println)))

(defn delete-all-snapshots!
  "Delete all snapshots in the snapshot directory.

  WARNING: This is destructive and cannot be undone!

  Returns a map with:
  - :deleted-count - Number of snapshots deleted
  - :deleted - List of deleted snapshot keys"
  []
  (let [snapshots (list-all-snapshots)
        deleted   (doall (for [{:keys [key]} snapshots]
                           (do (snapshot/delete-snapshot! key) key)))]
    {:deleted-count (count deleted) :deleted deleted}))

(defn prune-orphaned-snapshots!
  "Delete snapshots that are no longer referenced in code.

  This is a placeholder - actual implementation would require:
  1. Scanning all test files for snap/snap! calls
  2. Collecting all referenced snapshot keys
  3. Deleting snapshots not in that set

  For now, returns a not-implemented status."
  []
  {:status :not-implemented
   :message
   "Pruning orphaned snapshots requires scanning source files for references"})

#?(:clj
     (defn find-snap-calls-in-file
       "Find all snap/snap! calls in a file.

     Returns a sequence of maps with :type (:snap or :snap!) and :key/:line."
       [file-path]
       (try (let [content      (slurp file-path)
                  ;; Simple regex-based detection (could be improved with
                  ;; proper parsing)
                  snap-pattern #"\(snap\s+:([a-zA-Z0-9_-]+)"
                  snap-matches (re-seq snap-pattern content)]
              (for [[_ key-str] snap-matches]
                {:type :snap :key (keyword key-str)}))
            (catch Exception e
              (println "Warning: Failed to parse" file-path ":" (.getMessage e))
              [])))
   :cljs (defn find-snap-calls-in-file
           "Not implemented in ClojureScript."
           [_file-path]
           []))

#?(:clj
     (defn scan-test-directory
       "Scan all test files for snap calls.

     Returns a set of snapshot keys that are referenced in tests."
       []
       (let [test-dir (io/file "test")]
         (when (.exists test-dir)
           (->> (file-seq test-dir)
                (filter #(.isFile %))
                (filter #(or (str/ends-with? (.getName %) ".clj")
                             (str/ends-with? (.getName %) ".cljs")
                             (str/ends-with? (.getName %) ".cljc")))
                (mapcat #(find-snap-calls-in-file (.getPath %)))
                (map :key)
                (into #{})))))
   :cljs (defn scan-test-directory "Not implemented in ClojureScript." [] #{}))

(defn enable-auto-update!
  "Enable automatic snapshot updates for the current session.

  This sets :auto-update? to true in the runtime configuration.
  All subsequent snapshot mismatches will automatically update the snapshot."
  []
  (config/merge-override! {:auto-update? true})
  (println "✓ Auto-update enabled for this session")
  (println "  All snapshot mismatches will be automatically updated"))

(defn disable-auto-update!
  "Disable automatic snapshot updates."
  []
  (config/merge-override! {:auto-update? false})
  (println "✓ Auto-update disabled"))

(defn review-mode
  "Review what would change if tests were run in auto-update mode.

  This is a dry-run that shows which snapshots would be updated without
  actually modifying them.

  Returns a map with:
  - :status - :not-implemented
  - :message - Explanation

  Actual implementation would require:
  1. Running all tests in a dry-run mode
  2. Collecting which snapshots would be created/updated
  3. Presenting a summary without actually writing files"
  []
  {:status :not-implemented
   :message "Review mode requires integration with test runner"})

(defn cli-update
  "CLI-friendly update command.

  Enables auto-update mode for the current session and prints instructions."
  []
  (enable-auto-update!)
  (println)
  (println "Now run your tests:")
  (println "  clj -M:test")
  (println)
  (println "All mismatched snapshots will be automatically updated."))

(defn cli-prune
  "CLI-friendly prune command to clean up orphaned snapshots.

  Scans for snapshot files that are no longer referenced in code and
  offers to delete them."
  []
  (println "Scanning for orphaned snapshots...")
  (let [result (prune-orphaned-snapshots!)]
    (case (:status result)
      :not-implemented (println "⚠ " (:message result))
      (println "✓ Pruning complete"))))

(comment
  ;; Example usage. List all snapshots
  (print-summary)
  ;; Enable auto-update for session
  (enable-auto-update!)
  ;; Run tests, then disable
  (disable-auto-update!)
  ;; Delete all snapshots (dangerous!)
  (delete-all-snapshots!)
  ;; CLI-style update
  (cli-update))
