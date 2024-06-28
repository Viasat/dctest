;; Copyright (c) 2024, Viasat, Inc
;; Licensed under EPL 2.0

(ns dctest.util
  (:require
    [cljs-bean.core :refer [->clj ->js]]
    [clojure.string :as S]
    [clojure.pprint :refer [pprint print-table]]
    [dctest.expressions :as expr]
    [promesa.core :as P]
    [viasat.util :refer [Eprintln fatal read-file]]
    ["js-yaml" :as yaml]
    #_["ajv$default" :as Ajv]
    ))

;; TODO: use require syntax when shadow-cljs works with "*$default"
(def Ajv (js/require "ajv"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General functions

(defn obj->str [obj]
  (js/JSON.stringify (clj->js obj)
                     (fn [k v] (if (instance? js/Error v)
                                 (ex-message v)
                                 v))))
(defn js->map [obj]
  (into {} (map js->clj (js/Object.entries obj))))

(defn log [opts & args]
  (when-not (:quiet opts)
    (apply println args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String functions

(defn trim [s] (S/replace s #"\s*$" ""))

(defn indent [s pre]
  (-> s
      (S/replace #"[\n]*$" "")
      (S/replace #"([\n]|^)" (str "$1" pre))))

(defn indent-pprint-str [o pre]
    (indent (trim (with-out-str (pprint o))) pre))

(defn indent-print-table-str
  ([rows pre]
   (indent (trim (with-out-str (print-table rows))) pre))
  ([ks rows pre]
   (indent (trim (with-out-str (print-table ks rows))) pre)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File functions

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

;; Validation functions return a boolean and define custom error messages as an
;; array via setting a a property on the function itself.
;; https://ajv.js.org/keywords.html#define-keyword-with-validate-function
(defn validate-expression
  [schema data]
  (let [errors (expr/flatten-errors (expr/read-ast data schema))]
    (if (empty? errors)
      true
      (do
        (set! (.-errors validate-expression) (->js errors))
        false))))

;; { type: <type>, expressionType: <schemaType> }
(def expression-keyword
  {:keyword "expression"
   :type "string"
   :schemaType "string"
   :validate validate-expression})

(defn check-schema [data schema verbose]
  (let [ajv (doto (Ajv. #js {:allErrors true :coerceTypes true :strict true :useDefaults true})
              (.addKeyword (->js expression-keyword)))
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
