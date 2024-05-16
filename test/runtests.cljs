(ns runtests
  (:require [cljs.test :refer-macros [run-all-tests]]
            [dctest.expressions-test]
            ))

(run-all-tests #"dctest\..*")
