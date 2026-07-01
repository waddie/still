;; Copyright (c) 2026 Tom Waddington. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; MIT License (https://opensource.org/licenses/MIT) which can be found
;; in the LICENSE file at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.

(ns still.diff-test
  "Tests for still.diff namespace."
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros
                      [deftest testing is use-fixtures]])
            [clojure.string :as str]
            [still.config :as config]
            [still.diff :as diff]))

(defn- reset-config-fixture
  [f]
  (config/override! {})
  (config/invalidate-config-cache!)
  (f)
  (config/override! {})
  (config/invalidate-config-cache!))

(use-fixtures :each reset-config-fixture)

(def ^:private ansi-escape "\u001b[")

(deftest format-diff-color-override-test
  (testing "explicit :color? false wins over config colour on"
    (config/override! {:color? true})
    (let [out (diff/diff-str {:a 1} {:a 2} {:color? false})]
      (is (not (str/includes? out ansi-escape)))))
  (testing "explicit :colour? false wins over config colour on"
    (config/override! {:colour? true})
    (let [out (diff/diff-str {:a 1} {:a 2} {:colour? false})]
      (is (not (str/includes? out ansi-escape)))))
  (testing "explicit :color? true wins over config colour off"
    (config/override! {:color? false})
    (let [out (diff/diff-str {:a 1} {:a 2} {:color? true})]
      (is (str/includes? out ansi-escape))))
  (testing "falls back to config when opts say nothing"
    (config/override! {:color? false})
    (let [out (diff/diff-str {:a 1} {:a 2})]
      (is (not (str/includes? out ansi-escape))))))
