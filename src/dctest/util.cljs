;; Copyright (c) 2024, Viasat, Inc
;; Licensed under EPL 2.0

(ns dctest.util
  (:require
    [cljs-bean.core :refer [->clj]]
    [clojure.string :as S]
    [clojure.pprint :refer [pprint]]
    [promesa.core :as P]
    ["js-yaml" :as yaml]
    ["fs" :as fs]
    ["neodoc" :as neodoc]
    ["util" :refer [promisify]]
    ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Argument processing

(defn clean-opts [arg-map]
  (reduce (fn [o [a v]]
            (let [k (keyword (S/replace a #"^[-<]*([^>]*)[>]*$" "$1"))]
              (assoc o k (or (get o k) v))))
          {} arg-map))

(defn parse-opts [usage argv]
  (-> usage
      (neodoc/run (clj->js {:optionsFirst true
                            :smartOptions true
                            :argv argv}))
      js->clj
      clean-opts))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General functions

(defn obj->str [obj]
  (js/JSON.stringify (clj->js obj)))

(def Eprn     #(binding [*print-fn* *print-err-fn*] (apply prn %&)))
(def Eprintln #(binding [*print-fn* *print-err-fn*] (apply println %&)))
(def Epprint  #(binding [*print-fn* *print-err-fn*] (pprint %)))

(defn fatal [code & args]
  (when (seq args)
    (apply Eprintln args))
  (js/process.exit code))

(defn log [opts & args]
  (when-not (:quiet opts)
    (apply println args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File functions

(def read-file (promisify (.-readFile fs)))
(def write-file (promisify (.-writeFile fs)))

(defn load-yaml [path]
  (P/let [buffer (read-file path)]
    (try
      (->clj (.load yaml buffer))
      (catch yaml/YAMLException err
        (Eprintln "Failure to load file:" path)
        (fatal 2 (.-message err))))))

