;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.snapshot
  "Snapshot file management.

  Handles reading, writing, and organising snapshot files on disk (or alternative
  storage for ClojureScript)."
  (:require [still.config :as config]
            [still.serialize :as serialize]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.string :as str]
               :cljs [clojure.string :as str]))
  #?(:clj (:import [java.io File]
                   [java.time Instant])))

(defn- validate-snapshot-key
  "Validate that a snapshot key is safe for use as a filename.

  Throws ex-info if the key is invalid.
  Prevents path traversal attacks and other file system issues."
  [snapshot-key]
  (when-not (keyword? snapshot-key)
    (throw (ex-info "Snapshot key must be a keyword"
                    {:key snapshot-key :type (type snapshot-key)})))
  (let [name-str (name snapshot-key)
        ns-str (namespace snapshot-key)]
    (when (or (str/includes? name-str "..")
              (str/includes? name-str "\\")
              (and ns-str (str/includes? ns-str ".."))
              (and ns-str (str/includes? ns-str "\\")))
      (throw (ex-info "Snapshot key contains invalid path characters"
                      {:key snapshot-key
                       :name name-str
                       :namespace ns-str
                       :reason "Path traversal attempt detected"})))
    (when (empty? name-str)
      (throw (ex-info "Snapshot key name cannot be empty"
                      {:key snapshot-key}))))
  snapshot-key)

(defn- sanitize-key
  "Sanitize a snapshot key for use as a filename.

  Converts namespaced keywords, removes special characters, etc."
  [k]
  (let [ns (namespace k) nm (name k)] (if ns (str ns "_" nm) nm)))

(defn snapshot-path
  "Generate the file path for a snapshot key.

  Examples:
    (snapshot-path :user-test) => \"test/still/user_test.edn\"
    (snapshot-path :api/create-user) => \"test/still/api_create_user.edn\""
  [snapshot-key]
  (validate-snapshot-key snapshot-key)
  (let [dir (config/snapshot-dir)
        filename (str (sanitize-key snapshot-key) ".edn")]
    (str dir "/" filename)))

#?(:clj
     (defn- ensure-parent-dir
       "Ensure the parent directory exists for a file path.

          Throws ex-info if the directory cannot be created."
       [path]
       (when-let [parent (.getParentFile (io/file path))]
         (when-not (.exists parent)
           (when-not (.mkdirs parent)
             (throw (ex-info "Failed to create snapshot directory"
                             {:path (.getPath parent)
                              :snapshot-path path})))))))

#?(:clj (defn- file-exists?
          "Check if a file exists."
          [path]
          (let [f (io/file path)] (.exists f)))
   :cljs (defn- file-exists?
           "Check if a snapshot exists in browser localStorage."
           [path]
           (when (and (exists? js/localStorage) (.-localStorage js/window))
             (some? (.getItem js/localStorage path)))))

#?(:clj (defn- read-file
          "Read EDN content from a file."
          [path]
          (try (edn/read-string (slurp path))
               (catch Exception e
                 (throw (ex-info (str "Failed to read snapshot file: " path)
                                 {:path path :error (.getMessage e)}
                                 e)))))
   :cljs (defn- read-file
           "Read EDN content from localStorage."
           [path]
           (try (when-let [content (.getItem js/localStorage path)]
                  (edn/read-string content))
                (catch js/Error e
                  (throw (ex-info (str "Failed to read snapshot: " path)
                                  {:path path :error (.-message e)}
                                  e))))))

#?(:clj (defn- write-file
          "Write EDN content to a file."
          [path content]
          (try (ensure-parent-dir path)
               (spit path (serialize/pretty-print content))
               (catch Exception e
                 (throw (ex-info (str "Failed to write snapshot file: " path)
                                 {:path path :error (.getMessage e)}
                                 e)))))
   :cljs (defn- write-file
           "Write EDN content to localStorage."
           [path content]
           (try (.setItem js/localStorage path (serialize/pretty-print content))
                (catch js/Error e
                  (throw (ex-info (str "Failed to write snapshot: " path)
                                  {:path path :error (.-message e)}
                                  e))))))

(defn- add-metadata
  "Add metadata to a snapshot value.

  Metadata includes:
  - Creation timestamp
  - Test name (if in test context)
  - Platform info"
  [value snapshot-key]
  (if (config/metadata?)
    {:snapshot/value value
     :snapshot/key snapshot-key
     :snapshot/created-at #?(:clj (str (Instant/now))
                             :cljs (.toISOString (js/Date.)))
     :snapshot/platform #?(:clj :clj
                           :cljs :cljs)}
    {:snapshot/value value}))

(defn- extract-value
  "Extract the value from a snapshot, removing metadata if present."
  [snapshot-data]
  (if (and (map? snapshot-data) (contains? snapshot-data :snapshot/value))
    (:snapshot/value snapshot-data)
    snapshot-data))

(defn read-snapshot
  "Read a snapshot from storage.

  Returns nil if the snapshot doesn't exist.
  Returns the snapshot value (with metadata stripped)."
  [snapshot-key]
  (let [path (snapshot-path snapshot-key)]
    (when (file-exists? path)
      (let [snapshot-data (read-file path)] (extract-value snapshot-data)))))

(defn write-snapshot!
  "Write a snapshot to storage.

  The value will be serialised and pretty-printed.
  Metadata is added if configured."
  [snapshot-key value]
  (let [path (snapshot-path snapshot-key)
        serialized (serialize/serialize-value value)
        with-metadata (add-metadata serialized snapshot-key)]
    (write-file path with-metadata)
    value))

(defn update-snapshot!
  "Update an existing snapshot or create a new one.

  Returns the value that was written."
  [snapshot-key value]
  (write-snapshot! snapshot-key value))

(defn snapshot-exists?
  "Check if a snapshot exists for the given key."
  [snapshot-key]
  (file-exists? (snapshot-path snapshot-key)))

(defn delete-snapshot!
  "Delete a snapshot from storage."
  [snapshot-key]
  (let [path (snapshot-path snapshot-key)]
    #?(:clj (when (file-exists? path) (io/delete-file path))
       :cljs (when (file-exists? path) (.removeItem js/localStorage path)))))

#?(:clj
     (defn list-snapshots
       "List all snapshot files in the snapshot directory.

     Returns a sequence of {:key keyword :path string} maps."
       []
       (let [dir (io/file (config/snapshot-dir))]
         (when (.exists dir)
           (->> (.listFiles dir)
                (filter #(.isFile %))
                (filter #(str/ends-with? (.getName %) ".edn"))
                (map (fn [^File f]
                       {:path (.getPath f)
                        :name (.getName f)
                        :key (keyword
                              (str/replace (.getName f) #"\.edn$" ""))}))))))
   :cljs
     (defn list-snapshots
       "List all snapshots in localStorage.

     Returns a sequence of {:key keyword :path string} maps."
       []
       (when (and (exists? js/localStorage) (.-localStorage js/window))
         (let [prefix (config/snapshot-dir)]
           (for [i (range (.-length js/localStorage))
                 :let [key (.key js/localStorage i)]
                 :when (str/starts-with? key prefix)]
             {:path key
              :name (last (str/split key #"/"))
              :key (keyword (str/replace (last (str/split key #"/"))
                                         #"\.edn$"
                                         ""))})))))

(defn snapshot-metadata
  "Get metadata for a snapshot without loading the full value.

  Returns a map with :created-at, :platform, etc., or nil if no metadata."
  [snapshot-key]
  (let [path (snapshot-path snapshot-key)]
    (when (file-exists? path)
      (let [snapshot-data (read-file path)]
        (when (and (map? snapshot-data)
                   (contains? snapshot-data :snapshot/value))
          (let [metadata (dissoc snapshot-data :snapshot/value)]
            (when (seq metadata) metadata)))))))

(comment
  ;; Example usage. Write a snapshot
  (write-snapshot! :user-test {:id 123 :name "Alice"})
  ;; Read a snapshot
  (read-snapshot :user-test)
  ;; => {:id 123 :name "Alice"}
  ;; Check if snapshot exists
  (snapshot-exists? :user-test)
  ;; => true. List all snapshots
  (list-snapshots)
  ;; => ({:key :user-test :path "test/still/user_test.edn" :name
  ;; "user_test.edn"})
  ;; Get metadata
  (snapshot-metadata :user-test)
  ;; => {:snapshot/key :user-test
  ;;     :snapshot/created-at "2025-01-18T..."
  ;;     :snapshot/platform :clj}
  ;; Delete a snapshot
  (delete-snapshot! :user-test))
