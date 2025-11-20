;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.serialize-test
  "Tests for still.serialize namespace."
  (:require #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer-macros [deftest testing is]])
            [still.serialize :as serialize])
  #?(:clj (:import [java.util Date UUID]
                   [java.time Instant LocalDate])))

(deftest serialize-primitives-test
  (testing "serializes strings"
    (is (= "hello" (serialize/serialize-value "hello"))))
  (testing "serializes numbers"
    (is (= 42 (serialize/serialize-value 42)))
    (is (= 3.14 (serialize/serialize-value 3.14))))
  (testing "serializes keywords"
    (is (= :test (serialize/serialize-value :test)))
    (is (= :ns/key (serialize/serialize-value :ns/key))))
  (testing "serializes symbols" (is (= 'sym (serialize/serialize-value 'sym))))
  (testing "serializes booleans"
    (is (= true (serialize/serialize-value true)))
    (is (= false (serialize/serialize-value false))))
  (testing "serializes nil" (is (nil? (serialize/serialize-value nil)))))

(deftest serialize-collections-test
  (testing "serializes vectors"
    (is (= [1 2 3] (serialize/serialize-value [1 2 3])))
    (is (= ["a" "b"] (serialize/serialize-value ["a" "b"]))))
  (testing "serializes lists"
    (is (= '(1 2 3) (serialize/serialize-value '(1 2 3)))))
  (testing "serializes maps"
    (is (= {:a 1 :b 2} (serialize/serialize-value {:a 1 :b 2}))))
  (testing "serializes sets"
    (is (= #{1 2 3} (serialize/serialize-value #{1 2 3}))))
  (testing "serializes nested structures"
    (is (= {:users [{:id 1 :name "Alice"} {:id 2 :name "Bob"}]}
           (serialize/serialize-value {:users [{:id 1 :name "Alice"}
                                               {:id 2 :name "Bob"}]})))))

#?(:clj (deftest serialize-java-types-test
          (testing "serializes java.util.Date"
            (let [date   (Date. 1234567890000)
                  result (serialize/serialize-value date)]
              (is (map? result))
              (is (= :still.serialize/date (:type result)))
              (is (string? (:iso8601 result)))))
          (testing "serializes UUID"
            (let [uuid   (UUID/randomUUID)
                  result (serialize/serialize-value uuid)]
              (is (map? result))
              (is (= :still.serialize/uuid (:type result)))
              (is (string? (:uuid result)))
              (is (= (str uuid) (:uuid result)))))
          (testing "serializes java.time.Instant"
            (let [instant (Instant/now)
                  result  (serialize/serialize-value instant)]
              (is (map? result))
              (is (= :still.serialize/instant (:type result)))
              (is (string? (:iso8601 result)))))
          (testing "serializes java.time.LocalDate"
            (let [date   (LocalDate/of 2025 1 18)
                  result (serialize/serialize-value date)]
              (is (map? result))
              (is (= :still.serialize/local-date (:type result)))
              (is (= "2025-01-18" (:iso8601 result)))))))

(deftest stable-value-test
  (testing "primitives are stable"
    (is (true? (serialize/stable-value? 42)))
    (is (true? (serialize/stable-value? "hello")))
    (is (true? (serialize/stable-value? :keyword)))
    (is (true? (serialize/stable-value? true))))
  (testing "collections of stable values are stable"
    (is (true? (serialize/stable-value? [1 2 3])))
    (is (true? (serialize/stable-value? {:a 1 :b 2})))
    (is (true? (serialize/stable-value? #{:x :y :z}))))
  #?(:clj (testing "dates and UUIDs are not stable"
            (is (false? (serialize/stable-value? (Date.))))
            (is (false? (serialize/stable-value? (UUID/randomUUID))))
            (is (false? (serialize/stable-value? (Instant/now))))))
  (testing "collections containing unstable values are not stable"
    #?(:clj (do (is (false? (serialize/stable-value? [(Date.) 1 2])))
                (is (false? (serialize/stable-value? {:date (Date.)
                                                      :id 1})))))))

(deftest pretty-print-test
  (testing "pretty prints simple values"
    (is (string? (serialize/pretty-print {:a 1})))
    (is (string? (serialize/pretty-print [1 2 3]))))
  (testing "output contains newlines for readability"
    (let [output (serialize/pretty-print
                  {:key1 "value1" :key2 "value2" :key3 "value3"})]
      (is (string? output))
      ;; Should have newlines for multi-key maps
      (is (> (count (filter #(= % \newline) output)) 0)))))

#?(:clj (do (defrecord TestRecord [value])
            (defrecord StableRecord [data])
            (deftest custom-serializer-test
              (testing "can register custom serializer"
                (serialize/register-serializer!
                 TestRecord
                 (fn [r] {:type ::test-record :value (:value r)}))
                (let [record (->TestRecord 42)
                      result (serialize/serialize-value record)]
                  (is (= {:type ::test-record :value 42} result))))
              (testing "custom serialized values are considered stable"
                (serialize/register-serializer!
                 StableRecord
                 (fn [r] {:type ::stable :data (:data r)}))
                (let [record (->StableRecord "test")]
                  ;; After serialization, the result should be stable
                  (is (true? (serialize/stable-value? (serialize/serialize-value
                                                       record)))))))))
