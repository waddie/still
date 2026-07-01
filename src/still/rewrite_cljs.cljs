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

(defn- value-signature
  "Canonical string of a snap! call's value expression, or nil if it can't be
  read. Mirrors still.rewrite/value-signature."
  [loc]
  (when-let [value-loc (some-> loc
                               z/down
                               z/right)]
    (try (binding [*print-length* nil
                   *print-level*  nil]
           (pr-str (z/sexpr value-loc)))
         (catch :default _ nil))))

(defn- find-snap!-by-value
  "Locate the snap! call to edit by value-expression signature, using `line`
  only as a tie-breaker. Mirrors still.rewrite/find-snap!-by-value."
  [zloc line value-sig]
  (if (nil? value-sig)
    (find-snap!-at-line zloc line)
    (let [calls   (loop [loc zloc
                         acc []]
                    (if (z/end? loc)
                      acc
                      (recur (z/next loc)
                             (if (snap!-call? loc)
                               (conj acc
                                     {:has-expected? (has-expected-value? loc)
                                      :loc           loc
                                      :row           (-> loc
                                                         z/node
                                                         meta
                                                         :row)
                                      :sig           (value-signature loc)})
                               acc))))
          matches (filter #(= value-sig (:sig %)) calls)]
      (case (count matches)
        0 (find-snap!-at-line zloc line)
        1 (:loc (first matches))
        (let [bare (remove :has-expected? matches)
              pool (if (seq bare) bare matches)]
          (:loc (apply min-key #(js/Math.abs (- (:row %) line)) pool)))))))

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
  - line: Line number of the snap! call (compile-time, pre-edit)
  - expected-value: The serialised value to insert as expected
  - value-sig (optional): canonical string of the value expression, used to
    locate the call regardless of line drift. When omitted or nil, the call
    is located by line number alone.

  Returns a map with :status (:inserted, :updated, :not-found, :error)."
  ([file-path line expected-value]
   (add-expected-value! file-path line expected-value nil))
  ([file-path line expected-value value-sig]
   (try (let [zloc     (parse-file file-path)
              snap-loc (find-snap!-by-value zloc line value-sig)]
          (cond
            (nil? snap-loc) {:message (str "No snap! call found at " file-path
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
           :status    :error}))))
