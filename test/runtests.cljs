(ns runtests
  (:require [cljs.test :refer-macros [run-all-tests]]
            ;; Add test namespaces here
            ))

(run-all-tests #"dctest\..*")
