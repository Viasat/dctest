(ns runtests
  (:require [cljs.test :refer-macros [run-all-tests]]
            [dctest.expressions-test]
            ))

(defn -main []
  (run-all-tests #"dctest\..*"))
