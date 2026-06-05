(ns still.snap-cljs-test
  #?(:cljs (:require [cljs.test :refer-macros [deftest testing]]
                     [still.core :refer [snap]]
                     [still.core :refer-macros [snap!]])))

#?(:cljs (deftest snap!-auto-edit-test
           (testing "snap! inserts expected value when none is present"
             (snap! (+ 1 2) 3))))
