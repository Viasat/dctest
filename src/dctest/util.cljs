;; Copyright (c) 2024, Viasat, Inc
;; Licensed under EPL 2.0

(ns dctest.util
  (:require
    [cljs-bean.core :refer [->clj ->js]]
    [clojure.string :as S]
    [clojure.pprint :refer [pprint]]
    [promesa.core :as P]
    ["js-yaml" :as yaml]
    ["fs" :as fs]
    ["neodoc" :as neodoc]
    ["util" :refer [promisify]]
    #_["ajv$default" :as Ajv]
    ))

;; TODO: use require syntax when shadow-cljs works with "*$default"
(def Ajv (js/require "ajv"))

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
;; String functions

(defn trim [s] (S/replace s #"\s*$" ""))

(defn indent [s pre]
  (-> s
      (S/replace #"[\n]*$" "")
      (S/replace #"(^|[\n])" (str "$1" pre))))

(defn indent-pprint-str [o pre]
    (indent (trim (with-out-str (pprint o))) pre))

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

(defn ajv-error-to-str [error]
  (let [path (:instancePath error)
        params (dissoc (:params error) :type :pattern :missingProperty)]
    (str "  " (if (not (empty? path)) path "/")
         " " (:message error)
         (if (not (empty? params)) (str " " params) ""))))

(defn check-schema [data schema verbose]
  (let [ajv (Ajv. #js {:allErrors true :coerceTypes true :useDefaults true})
        validator (.compile ajv (->js schema))
        valid (validator (->js data))]
    (if valid
      data
      (let [errors (-> validator .-errors ->clj)
            msg (if verbose
                  (indent-pprint-str errors "  ")
                  (S/join "\n" (map ajv-error-to-str errors)))]
        (fatal 1 (str "\nError during schema validation:\n"
                      (when verbose
                        "\nUser config:\n" (indent-pprint-str data "  "))
                      "\nValidation errors:\n" msg))))))
