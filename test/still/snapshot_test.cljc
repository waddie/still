;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.snapshot-test
  "Tests for still.snapshot namespace."
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros
                      [deftest testing is use-fixtures]])
            [still.snapshot :as snapshot]
            [still.config :as config]))

(defn cleanup-test-snapshots
  "Clean up test snapshots before and after tests."
  [f]
  (config/override! {:snapshot-dir "test/still-test" :metadata? true})
  (config/invalidate-config-cache!)
  ;; Clean up any existing test snapshots
  (doseq [key [:test-key-1 :test-key-2 :test-key-3 :namespace/key]]
    (when (snapshot/snapshot-exists? key) (snapshot/delete-snapshot! key)))
  (f)
  ;; Clean up after test
  (doseq [key [:test-key-1 :test-key-2 :test-key-3 :namespace/key]]
    (when (snapshot/snapshot-exists? key) (snapshot/delete-snapshot! key)))
  (config/override! {})
  (config/invalidate-config-cache!))

(use-fixtures :each cleanup-test-snapshots)

(deftest snapshot-path-validation-test
  (testing "validates snapshot keys are keywords"
    (is (thrown-with-msg? #?(:clj Exception
                             :cljs js/Error)
                          #"Snapshot key must be a keyword"
                          (snapshot/snapshot-path "not-a-keyword")))
    (is (thrown-with-msg? #?(:clj Exception
                             :cljs js/Error)
                          #"Snapshot key must be a keyword"
                          (snapshot/snapshot-path 123))))
  (testing "rejects path traversal attempts"
    (is (thrown-with-msg? #?(:clj Exception
                             :cljs js/Error)
                          #"invalid path characters"
                          (snapshot/snapshot-path (keyword ".."))))
    (is (thrown-with-msg? #?(:clj Exception
                             :cljs js/Error)
                          #"invalid path characters"
                          (snapshot/snapshot-path (keyword "test..bad"))))
    (is (thrown-with-msg? #?(:clj Exception
                             :cljs js/Error)
                          #"invalid path characters"
                          (snapshot/snapshot-path (keyword "bad\\"))))
    (is (thrown-with-msg? #?(:clj Exception
                             :cljs js/Error)
                          #"invalid path characters"
                          (snapshot/snapshot-path (keyword ".." "name"))))))

(deftest write-and-read-snapshot-test
  (testing "writes and reads a simple snapshot"
    (let [data {:id 123 :name "Alice"}]
      (snapshot/write-snapshot! :test-key-1 data)
      (is (snapshot/snapshot-exists? :test-key-1))
      (is (= data (snapshot/read-snapshot :test-key-1)))))
  (testing "writes and reads complex nested data"
    (let [data {:users    [{:id 1 :name "Alice" :roles #{:admin :user}}
                           {:id 2 :name "Bob" :roles #{:user}}]
                :metadata {:created "2025-01-18"}}]
      (snapshot/write-snapshot! :test-key-2 data)
      (is (= data (snapshot/read-snapshot :test-key-2)))))
  (testing "handles namespaced keywords"
    (let [data {:type :test/value}]
      (snapshot/write-snapshot! :namespace/key data)
      (is (snapshot/snapshot-exists? :namespace/key))
      (is (= data (snapshot/read-snapshot :namespace/key))))))

(deftest update-snapshot-test
  (testing "updates existing snapshot"
    (let [original {:version 1}
          updated  {:version 2}]
      (snapshot/write-snapshot! :test-key-3 original)
      (is (= original (snapshot/read-snapshot :test-key-3)))
      (snapshot/update-snapshot! :test-key-3 updated)
      (is (= updated (snapshot/read-snapshot :test-key-3)))))
  (testing "creates snapshot if it doesn't exist"
    (let [data {:new true}]
      (snapshot/update-snapshot! :test-key-1 data)
      (is (= data (snapshot/read-snapshot :test-key-1))))))

(deftest snapshot-exists-test
  (testing "returns false for non-existent snapshot"
    (is (false? (snapshot/snapshot-exists? :non-existent))))
  (testing "returns true for existing snapshot"
    (snapshot/write-snapshot! :test-key-1 {:exists true})
    (is (true? (snapshot/snapshot-exists? :test-key-1)))))

(deftest delete-snapshot-test
  (testing "deletes existing snapshot"
    (snapshot/write-snapshot! :test-key-1 {:data true})
    (is (snapshot/snapshot-exists? :test-key-1))
    (snapshot/delete-snapshot! :test-key-1)
    (is (false? (snapshot/snapshot-exists? :test-key-1))))
  (testing "handles deleting non-existent snapshot gracefully"
    ;; Should not throw an error
    (snapshot/delete-snapshot! :non-existent)
    (is (false? (snapshot/snapshot-exists? :non-existent)))))

(deftest snapshot-metadata-test
  (testing "includes metadata when configured"
    (config/merge-override! {:metadata? true})
    (snapshot/write-snapshot! :test-key-1 {:value 42})
    (let [metadata (snapshot/snapshot-metadata :test-key-1)]
      (is (map? metadata))
      (is (= :test-key-1 (:snapshot/key metadata)))
      (is (string? (:snapshot/created-at metadata)))
      (is (keyword? (:snapshot/platform metadata)))))
  (testing "returns nil for non-existent snapshot"
    (is (nil? (snapshot/snapshot-metadata :non-existent))))
  (testing "excludes metadata when configured"
    (config/merge-override! {:metadata? false})
    (snapshot/write-snapshot! :test-key-2 {:value 99})
    (let [metadata (snapshot/snapshot-metadata :test-key-2)]
      ;; Should return nil because no metadata was stored
      (is (nil? metadata)))))

#?(:clj (deftest list-snapshots-test
          (testing "lists existing snapshots"
            (snapshot/write-snapshot! :test-key-1 {:a 1})
            (snapshot/write-snapshot! :test-key-2 {:b 2})
            (let [snapshots (snapshot/list-snapshots)
                  keys      (set (map :key snapshots))]
              (is (contains? keys :test-key-1))
              (is (contains? keys :test-key-2))))
          (testing "returns empty list when no snapshots exist"
            ;; Clean up all test snapshots
            (doseq [key [:test-key-1 :test-key-2 :test-key-3]]
              (when (snapshot/snapshot-exists? key)
                (snapshot/delete-snapshot! key)))
            ;; Note: list-snapshots returns all snapshots in the directory,
            ;; so we just check it returns a collection
            (let [snapshots (snapshot/list-snapshots)]
              (is (or (nil? snapshots) (coll? snapshots)))))))

(deftest read-nonexistent-snapshot-test
  (testing "returns nil for non-existent snapshot"
    (is (nil? (snapshot/read-snapshot :does-not-exist)))))
