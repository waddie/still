;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.rewrite-cljs
  "Source code rewriting for snap! inline snapshots in ClojureScript.

  Uses rewrite-clj (which supports ClojureScript) for AST manipulation
  and still.node-io for Node.js file I/O."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]
            [still.node-io :as node-io]
            [still.serialize :as serialize]))

(defn- parse-file
  [file-path]
  (-> (node-io/read-file file-path)
      p/parse-string-all
      z/of-node))

(defn- snap!-call?
  [loc]
  (and (z/list? loc)
       (when-let [first-child (z/down loc)]
         (let [sym (z/sexpr first-child)]
           (or (= sym 'snap!) (= sym 'still.core/snap!))))))

(defn- find-snap!-at-line
  [zloc target-line]
  (loop [loc zloc]
    (if (z/end? loc)
      nil
      (let [current-line (-> loc
                             z/node
                             meta
                             :row)]
        (if (and (= current-line target-line) (snap!-call? loc))
          loc
          (recur (z/next loc)))))))

(defn- count-children [loc] (when (z/list? loc) (count (z/child-sexprs loc))))

(defn- has-expected-value?
  [loc]
  (when (snap!-call? loc) (>= (count-children loc) 3)))

(defn- insert-expected-value
  [loc expected-value]
  (when (snap!-call? loc)
    (let [func-loc  (z/down loc)
          value-loc (z/right func-loc)
          expected-value-node (p/parse-string (serialize/format-value-for-source
                                               expected-value))]
      (-> value-loc
          (z/insert-right expected-value-node)
          z/up))))

(defn- replace-expected-value
  [loc expected-value]
  (when (snap!-call? loc)
    (let [func-loc            (z/down loc)
          value-loc           (z/right func-loc)
          expected-loc        (z/right value-loc)
          expected-value-node (p/parse-string (serialize/format-value-for-source
                                               expected-value))]
      (-> expected-loc
          (z/replace expected-value-node)
          z/up))))

(defn- write-zipper
  [zloc file-path]
  (node-io/write-file file-path (z/root-string zloc)))

(defn add-expected-value!
  "Add or update the expected value in a snap! call.

  Arguments:
  - file-path: Absolute path to the source file
  - line: Line number of the snap! call
  - expected-value: The serialised value to insert as expected

  Returns a map with :status (:inserted, :updated, :not-found, :error)."
  [file-path line expected-value]
  (try
    (let [zloc     (parse-file file-path)
          snap-loc (find-snap!-at-line zloc line)]
      (cond (nil? snap-loc) {:message (str "No snap! call found at " file-path
                                           ":" line)
                             :status  :not-found}
            (has-expected-value? snap-loc)
            (let [updated-loc (replace-expected-value snap-loc expected-value)]
              (write-zipper updated-loc file-path)
              {:message (str "Updated expected value at " file-path ":" line)
               :status  :updated})
            :else
            (let [updated-loc (insert-expected-value snap-loc expected-value)]
              (write-zipper updated-loc file-path)
              {:message (str "Inserted expected value at " file-path ":" line)
               :status  :inserted})))
    (catch js/Error e
      {:exception e
       :message   (.-message e)
       :status    :error})))
