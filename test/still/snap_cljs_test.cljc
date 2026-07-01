(ns still.snap-cljs-test
  ;; still.core self-requires its macros, so a plain :refer works for snap!
  #?(:cljs (:require [cljs.test :refer-macros [deftest testing]]
                     [still.core :refer [snap!]])))

#?(:cljs (deftest snap!-auto-edit-test
           (testing "snap! inserts expected value when none is present"
             (snap! (+ 1 2) 3))))
