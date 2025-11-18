;; Copyright (c) 2025 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.config-test
  "Tests for still.config namespace."
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros
                      [deftest testing is use-fixtures]])
            [still.config :as config]))

(defn reset-config-fixture
  "Reset configuration between tests."
  [f]
  (config/override! {})
  (config/invalidate-config-cache!)
  (f)
  (config/override! {})
  (config/invalidate-config-cache!))

(use-fixtures :each reset-config-fixture)

(deftest get-config-test
  (testing "returns default configuration"
    (let [cfg (config/get-config)]
      (is (map? cfg))
      (is (= "test/still" (:snapshot-dir cfg)))
      (is (= true (:enabled? cfg)))
      (is (= false (:auto-update? cfg)))
      (is (= true (:metadata? cfg)))
      (is (= 3 (:diff-context-lines cfg)))
      (is (= true (:color? cfg))))))

(deftest get-value-test
  (testing "gets specific config values"
    (is (= "test/still" (config/get-value :snapshot-dir)))
    (is (= true (config/get-value :enabled?)))
    (is (= false (config/get-value :auto-update?))))
  (testing "returns nil for non-existent keys"
    (is (nil? (config/get-value :non-existent-key)))))

(deftest override-test
  (testing "overrides entire configuration"
    (config/override! {:snapshot-dir "custom/dir" :auto-update? true})
    (is (= "custom/dir" (config/get-value :snapshot-dir)))
    (is (= true (config/get-value :auto-update?)))
    ;; Other values should use defaults since override replaced everything
    (is (= true (config/get-value :enabled?))))
  (testing "invalidates cache on override"
    (config/override! {:snapshot-dir "first"})
    (is (= "first" (config/get-value :snapshot-dir)))
    (config/override! {:snapshot-dir "second"})
    (is (= "second" (config/get-value :snapshot-dir)))))

(deftest merge-override-test
  (testing "merges into existing configuration"
    (config/merge-override! {:auto-update? true})
    (is (= true (config/get-value :auto-update?)))
    ;; Other values should remain at defaults
    (is (= "test/still" (config/get-value :snapshot-dir)))
    (is (= true (config/get-value :enabled?))))
  (testing "multiple merges accumulate"
    (config/merge-override! {:auto-update? true})
    (config/merge-override! {:snapshot-dir "custom"})
    (is (= true (config/get-value :auto-update?)))
    (is (= "custom" (config/get-value :snapshot-dir))))
  (testing "invalidates cache on merge"
    (config/merge-override! {:snapshot-dir "first"})
    (is (= "first" (config/get-value :snapshot-dir)))
    (config/merge-override! {:snapshot-dir "second"})
    (is (= "second" (config/get-value :snapshot-dir)))))

(deftest british-spelling-test
  (testing "accepts British spelling for colour"
    (config/override! {:colour? false})
    ;; Should be normalized to American spelling
    (is (= false (config/get-value :color?))))
  (testing "accepts British spelling for serialisers"
    (config/override! {:serialisers {:custom :handler}})
    ;; Should be normalized to American spelling
    (is (= {:custom :handler} (config/get-value :serializers)))))

(deftest config-caching-test
  (testing "configuration is cached"
    (config/override! {:snapshot-dir "cached"})
    (let [cfg1 (config/get-config)
          cfg2 (config/get-config)]
      ;; Should return the same cached instance
      (is (identical? cfg1 cfg2))))
  (testing "cache can be invalidated"
    (config/override! {:snapshot-dir "first"})
    (let [cfg1 (config/get-config)]
      (config/invalidate-config-cache!)
      (config/override! {:snapshot-dir "second"})
      (let [cfg2 (config/get-config)]
        ;; Should be different instances after invalidation
        (is (not (identical? cfg1 cfg2)))
        (is (= "first" (:snapshot-dir cfg1)))
        (is (= "second" (:snapshot-dir cfg2)))))))

(deftest helper-functions-test
  (testing "enabled?"
    (is (= true (config/enabled?)))
    (config/merge-override! {:enabled? false})
    (is (= false (config/enabled?))))
  (testing "snapshot-dir"
    (is (= "test/still" (config/snapshot-dir)))
    (config/merge-override! {:snapshot-dir "custom"})
    (is (= "custom" (config/snapshot-dir))))
  (testing "auto-update?"
    (is (= false (config/auto-update?)))
    (config/merge-override! {:auto-update? true})
    (is (= true (config/auto-update?))))
  (testing "metadata?"
    (is (= true (config/metadata?)))
    (config/merge-override! {:metadata? false})
    (is (= false (config/metadata?))))
  (testing "serializers"
    (is (= {} (config/serializers)))
    (config/merge-override! {:serializers {:custom :handler}})
    (is (= {:custom :handler} (config/serializers))))
  (testing "diff-context-lines"
    (is (= 3 (config/diff-context-lines)))
    (config/merge-override! {:diff-context-lines 5})
    (is (= 5 (config/diff-context-lines))))
  (testing "color?"
    (is (= true (config/color?)))
    (config/merge-override! {:color? false})
    (is (= false (config/color?)))))
