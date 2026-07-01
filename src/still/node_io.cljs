;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.node-io
  "Node.js filesystem utilities for snap! source editing."
  (:require [clojure.string :as str]))

(defn node-env?
  "Returns true when running in a Node.js environment."
  []
  (exists? js/process))

(defn- fs [] (js/require "fs"))
(defn- path [] (js/require "path"))

(defn read-file
  "Read a file as a UTF-8 string."
  [file-path]
  (.readFileSync (fs) file-path "utf8"))

(defn write-file
  "Write a string to a file."
  [file-path content]
  (.writeFileSync (fs) file-path content "utf8"))

(defn write-file-atomic!
  "Write a string to a file via a temp file in the same directory, renamed
  into place so a crash cannot truncate the target."
  [file-path content]
  (let [p   (path)
        dir (.dirname p file-path)
        tmp (.join p
                   dir
                   (str "."
                        (.basename p file-path)
                        "."
                        (.slice (.toString (js/Math.random) 36) 2)
                        ".tmp"))]
    (try (.writeFileSync (fs) tmp content "utf8")
         (.renameSync (fs) tmp file-path)
         (catch :default e
           (when (.existsSync (fs) tmp) (.unlinkSync (fs) tmp))
           (throw e)))))

(defn file-exists?
  "Returns true if the file at path exists."
  [file-path]
  (.existsSync (fs) file-path))

(defn ensure-parent-dir!
  "Ensure the parent directory of the given path exists, creating it if needed."
  [file-path]
  (let [p   (path)
        dir (.dirname p file-path)]
    (.mkdirSync (fs) dir #js {:recursive true})))

(defn delete-file! "Delete a file." [file-path] (.unlinkSync (fs) file-path))

(defn list-edn-files
  "Return a sequence of .edn filenames in dir, or nil if dir doesn't exist."
  [dir]
  (when (file-exists? dir)
    (let [f (.readdirSync (fs) dir)]
      (->> (array-seq f)
           (filter #(str/ends-with? % ".edn"))))))

(defn resolve-file-path
  "Resolve a source filename to an absolute path.

  Searches src/, test/, dev/, and the current working directory.
  Returns the absolute path string, or nil if not found."
  [filename]
  (when filename
    (let [p (path)]
      (if (and (.isAbsolute p filename) (file-exists? filename))
        filename
        (let [cwd        (.cwd js/process)
              candidates (for [base  ["src" "test" "dev" "."]
                               :let  [c (.resolve p cwd base filename)]
                               :when (file-exists? c)]
                           c)]
          (first candidates))))))
