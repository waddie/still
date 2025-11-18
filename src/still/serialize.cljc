;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.serialize
  "Custom serialisation for snapshot values.

  Provides a protocol-based system for custom type serialisation, with built-in
  handlers for common edge cases like timestamps, UUIDs, and unstable values."
  (:require [still.config :as config]
            #?(:clj [clojure.pprint :as pprint]
               :cljs [cljs.pprint :as pprint]))
  #?(:clj (:import [java.util Date UUID]
                   [java.time Instant LocalDate LocalDateTime ZonedDateTime
                    OffsetDateTime])))

(defprotocol Serializable
  "Protocol for custom snapshot serialisation."
  (serialize [value]
   "Serialise a value for snapshot storage. Returns EDN-serialisable data."))

;; Default serialisation for most types
(extend-protocol Serializable
 #?(:clj Object
    :cljs default)
 (serialize [v] v)
 nil
 (serialize [_] nil))

#?(:clj (do
          ;; Java Date serialisation
          (extend-protocol Serializable
           Date
             (serialize [d]
               ;; Serialise as ISO-8601 string for stability
               {:type ::date :iso8601 (str (.toInstant d))}))
          ;; UUID serialisation
          (extend-protocol Serializable
           UUID
             (serialize [u] {:type ::uuid :uuid (str u)}))
          ;; Java Time API
          (extend-protocol Serializable
           Instant
             (serialize [i] {:type ::instant :iso8601 (str i)})
           LocalDate
             (serialize [d] {:type ::local-date :iso8601 (str d)})
           LocalDateTime
             (serialize [dt] {:type ::local-datetime :iso8601 (str dt)})
           ZonedDateTime
             (serialize [zdt] {:type ::zoned-datetime :iso8601 (str zdt)})
           OffsetDateTime
             (serialize [odt] {:type ::offset-datetime :iso8601 (str odt)}))))

#?(:cljs (do
           ;; JavaScript Date serialisation
           (extend-protocol Serializable
            js/Date
              (serialize [d] {:type ::date :iso8601 (.toISOString d)}))))

(defmacro register-serializer!
  "Register a custom serialiser for a type.

  The serialiser-fn should take a value and return EDN-serialisable data.

  Example:
    (register-serializer! MyRecord
      (fn [r] {:type ::my-record :data (:data r)}))"
  [the-type serializer-fn]
  `(extend-type ~the-type
    Serializable
      (serialize [v#] (~serializer-fn v#))))

(defn serialize-with-custom
  "Serialise a value using custom serialisers if available."
  [value]
  (let [custom-serializers (config/serializers)
        value-type #?(:clj (type value)
                      :cljs (type value))]
    (if-let [serializer (get custom-serializers value-type)]
      (serializer value)
      (serialize value))))

(defn- walk-serialize
  "Walk a data structure and apply serialisation to all values."
  [form]
  (cond
    ;; Check for records first (before map? check, since records are
    ;; map-like)
    #?(:clj (instance? clojure.lang.IRecord form)
       :cljs (and (map? form) (satisfies? IRecord form)))
    (serialize-with-custom form)
    ;; Then check regular collections
    (map? form)
    (into {} (map (fn [[k v]] [(walk-serialize k) (walk-serialize v)]) form))
    (sequential? form) (mapv walk-serialize form)
    (set? form) (into #{} (map walk-serialize form))
    :else (serialize-with-custom form)))

(defn serialize-value
  "Serialise a value for snapshot storage.

  Walks the entire data structure and applies custom serialisation where applicable.
  Returns EDN-serialisable data."
  [value]
  (walk-serialize value))

(defn pretty-print
  "Pretty-print a value as EDN string for snapshot files."
  [value]
  (with-out-str (pprint/pprint value)))

(defn normalize-for-comparison
  "Normalize values for comparison.

  Some values (like sorted-map vs hash-map) should compare equal even though
  their types differ. This function normalizes them."
  [value]
  (cond (map? value)
        (into {} (map (fn [[k v]] [k (normalize-for-comparison v)]) value))
        (set? value) (set (map normalize-for-comparison value))
        (sequential? value) (mapv normalize-for-comparison value)
        :else value))

(defn stable-value?
  "Check if a value is stable (deterministic) for snapshot testing.

  Returns true if the value contains no timestamps, UUIDs, or other unstable data."
  [value]
  (cond #?(:clj (instance? Date value)
           :cljs (instance? js/Date value))
        false
        #?(:clj (instance? UUID value)
           :cljs false)
        false
        #?(:clj (instance? Instant value)
           :cljs false)
        false
        (map? value)
        (every? (fn [[k v]] (and (stable-value? k) (stable-value? v))) value)
        (sequential? value) (every? stable-value? value)
        (set? value) (every? stable-value? value)
        :else true))

(comment
  ;; Example usage. Serialise a value with timestamps
  (serialize-value {:id 123
                    :created-at #?(:clj (Date.)
                                   :cljs (js/Date.))
                    :name "Test"})
  ;; Check if a value is stable
  (stable-value? {:id 123 :name "Test"})
  ;; => true
  (stable-value? {:id 123
                  :created-at #?(:clj (Date.)
                                 :cljs (js/Date.))})
  ;; => false. Register custom serialiser
  #?(:clj (do (defrecord Person [name age])
              (register-serializer!
               Person
               (fn [p] {:type ::person :name (:name p) :age (:age p)}))
              (serialize-value (->Person "Alice" 30)))))
