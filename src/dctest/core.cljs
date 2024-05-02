;; Copyright (c) 2024, Viasat, Inc
;; Licensed under EPL 2.0

(ns dctest.core
  (:require [clojure.string :as S]
            [clojure.pprint :refer [pprint]]
            [promesa.core :as P]
            [cljs-bean.core :refer [->clj]]
            ["fs" :as fs]
            ["util" :refer [promisify]]
            ["stream" :as stream]
            ["neodoc" :as neodoc]
            ["js-yaml" :as yaml]
            #_["dockerode$default" :as Docker]
            ))

;; TODO: use require syntax when shadow-cljs works with "*$default"
(def Docker (js/require "dockerode"))

(def usage "
Usage:
  dctest [options] <project> <test-suite>...

Options:
  --continue-on-error           Continue running tests, even if one fails
  --quiet                       Only print final totals
  --results-file RESULTS-FILE   Write JSON results to RESULTS-FILE
")

(set! *warn-on-infer* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Argument processing

(defn clean-opts [arg-map]
  (reduce (fn [o [a v]]
            (let [k (keyword (S/replace a #"^[-<]*([^>]*)[>]*$" "$1"))]
              (assoc o k (or (get o k) v))))
          {} arg-map))

(defn parse-opts [argv]
  (-> usage
      (neodoc/run (clj->js {:optionsFirst true
                            :smartOptions true
                            :argv argv}))
      js->clj
      clean-opts))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General utility functions

(defn obj->str [obj]
  (js/JSON.stringify (clj->js obj)))

(def slurp-buf (promisify (.-readFile fs)))
(def write-file (promisify (.-writeFile fs)))

(def Eprintln #(binding [*print-fn* *print-err-fn*] (apply println %&)))

(defn fatal [code & args]
  (when (seq args)
    (apply Eprintln args))
  (js/process.exit code))

(defn log [opts & args]
  (when-not (:quiet opts)
    (apply println args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Docker/Compose

(def WAIT-EXEC-SLEEP 200)

(defn wait-exec
  "[Async] Wait for docker exec to complete and when complete resolve
  to result of inspecting successful exec or reject with exec error."
  [exec]
  (P/create
    (fn [resolve reject]
      (let [check-fn (atom nil)
            exec-cb (fn [err data]
                      (if err (reject err)
                        (if (.-Running data)
                          (js/setTimeout @check-fn WAIT-EXEC-SLEEP)
                          (resolve data))))]
        (reset! check-fn (fn []
                           (.inspect exec exec-cb)))
        (@check-fn)))))

(defn docker-exec
  "[Async] Exec a command in a container and wait for it to complete
  (using wait-exec). Resolves to exec data with additional :Stdout and
  and :Stderr keys."
  [container command options]
  (P/let [cmd (if (string? command)
                ["sh" "-c" command]
                command)
          opts (merge {:AttachStdout true :AttachStderr true}
                      options
                      {:Cmd cmd})
          exec (.exec container (clj->js opts))
          stream (.start exec)
          stdout (atom [])
          stderr (atom [])
          stdout-stream (doto (stream/PassThrough.)
                          (.on "data" #(swap! stdout conj %)))
          stderr-stream (doto (stream/PassThrough.)
                          (.on "data" #(swap! stderr conj %)))
          _ (-> (.-modem container)
                (.demuxStream stream stdout-stream stderr-stream))
          data (wait-exec exec)
          stdout (S/join "" (map #(.toString % "utf8") @stdout))
          stderr (S/join "" (map #(.toString % "utf8") @stderr))
          result (assoc (->clj data) :Stdout stdout :Stderr stderr)]
    result))

(defn dc-service
  "[Async] Return the container for a docker compose service. If not
  found, returns nil."
  [docker project service index]
  (P/let [container-filter {:label [(str "com.docker.compose.project=" project)
                                    (str "com.docker.compose.service=" service)
                                    (str "com.docker.compose.container-number=" (str index))]}
          containers (.listContainers docker
                                      (clj->js {:all true
                                                :filters (obj->str container-filter)}))
          container (some->> (first containers)
                             .-Id
                             (.getContainer docker))]
    container))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Runner

(defn failure? [{:keys [outcome]}]
  (= :fail outcome))

(defn short-outcome [{:keys [outcome]}]
  (get {:pass "✓" :fail "F" :skip "S"} outcome "?"))

(defn load-test-suite! [opts path]
  (P/let [buffer (slurp-buf path)
          parsed (try
                   (->clj (.load yaml buffer))
                   (catch yaml/YAMLException err
                     (Eprintln "Failure to load test suite:" path)
                     (fatal 2 (.-message err))))
          suite (update parsed :name #(or % path))]
    suite))

;; TODO: Support more than exec :-)
(defn execute-step* [context step]
  (P/let [{:keys [docker opts]} context
          {:keys [project]} opts
          {service :exec index :index command :run} step
          index (or index 1)

          container (dc-service docker project service index)
          _ (when-not container
              (throw (ex-info (str "No container found for service '" service "' (index=" index ")")
                              {})))

          exec (P/then (docker-exec container command {})
                         ->clj)]

    (when-not (zero? (:ExitCode exec))
      (throw (ex-info (str "Non-zero exit code for command: " command)
                      {})))

    context))

(defn execute-step [context step]
  (P/let [{step-name :name} step

          {:keys [context outcome error]}
          , (if (get-in context [:state :skipping])
              {:context context :outcome :skip}
              (-> (P/let [context (execute-step* context step)]
                    {:context context :outcome :pass})
                  (P/catch
                    (fn [err]
                      {:context context :outcome :fail :error (.-message err)}))))]
    {:context context
     :results (merge {:outcome outcome}
                     (when step-name {:name step-name})
                     (when error {:error error}))}))

(defn execute-steps [context steps]
  (P/let [results (P/loop [steps steps
                           context context
                           results []]
                    (if (empty? steps)
                      results
                      (P/let [[step & steps] steps

                              {context :context
                               step-results :results} (execute-step context step)
                              context (update-in context [:state :skipping]
                                                 #(or %
                                                      (and (failure? step-results)
                                                           (not (get-in context [:strategy :continue-on-error])))))

                              results (conj results step-results)]
                        (P/recur steps context results))))]
    {:context context
     :results results}))

(defn run-test [context suite test]
  (P/let [skip-test? (get-in context [:state :skipping])
          test-name (:name test)

          context (assoc-in context [:state :failed] false)

          setup (execute-steps context (get-in suite [:setup :test]))
          steps (execute-steps (:context setup) (:steps test))
          teardown (execute-steps (:context setup) (get-in suite [:teardown :test]))

          results (concat (:results setup)
                          (:results steps)
                          (:results teardown))
          error (->> (filter failure? results)
                     first
                     :error)

          outcome (cond
                    skip-test? :skip
                    (some failure? results) :fail
                    :else :pass)]
    (merge {:outcome outcome
            :setup (:results setup)
            :steps (:results steps)
            :teardown (:results teardown)}
           (when test-name {:name test-name})
           (when error {:error error}))))

(defn run-suite [context suite]
  (log (:opts context) "  " (:name suite))
  (P/let [skip-suite? (get-in context [:state :skipping])

          context (assoc-in context [:state :failed] false)

          setup (execute-steps (assoc-in context [:strategy :continue-on-error] true)
                                 (get-in suite [:setup :suite]))
          test-results (P/loop [tests (:tests suite)
                                context (assoc context :state (get-in setup [:context :state]))
                                results []]
                         (if (empty? tests)
                           results
                           (P/let [[test & tests] tests
                                   result (run-test context suite test)
                                   context (update-in context [:state :skipping]
                                                      #(or %
                                                           (and (failure? result)
                                                                (not (get-in context [:strategy :continue-on-error])))))
                                   results (conj results result)]
                             (log (:opts context) "    " (short-outcome result) (:name result))
                             (P/recur tests context results))))
          teardown (execute-steps (-> context
                                        (assoc-in [:state :skipping] skip-suite?)
                                        (assoc-in [:strategy :continue-on-error] true))
                                    (get-in suite [:teardown :suite]))

          outcome (cond
                    skip-suite? :skip
                    (some failure?
                          (concat (:results setup)
                                  test-results
                                  (:results teardown))) :fail
                    :else :pass)]

    (log (:opts context)) ; breath between suites

    {:outcome outcome
     :name (:name suite)
     :setup (:results setup)
     :tests test-results
     :teardown (:results teardown)}))

(defn summarize [results]
  ;; Note: Suite can fail in setup/teardown, when all tests pass
  (let [overall (if (some failure? results) :fail :pass)
        test-totals (frequencies (map :outcome (mapcat :tests results)))]
    (merge {:pass 0 :fail 0 :skip 0}
           test-totals
           {:outcome overall :results results})))

;; TODO: improve output (and show failures in suite setup/teardown)
(defn print-results [opts summary]
  (doseq [suite (:results summary)]
    (doseq [[index {test-name :name
                    error-msg :error}]
            , (->> (:tests suite)
                   (filter failure?)
                   (map-indexed vector))]
      (log opts "  " (inc index) ")" test-name)
      (log opts "     " error-msg)
      (log opts "")))
  (log opts
       (:pass summary) "passing,"
       (:fail summary) "failed,"
       (:skip summary) "skipped"))

(defn write-results-file [opts summary]
  (when-let [path (:results-file opts)]
    (write-file path (obj->str summary))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main

(defn -main [& argv]
  (P/let [opts (parse-opts (or argv #js []))
          {:keys [continue-on-error quiet project test-suite]} opts

          suites (P/all
                   (for [path test-suite]
                     (load-test-suite! opts path)))

          results (P/loop [suites suites
                           context {:docker (Docker.)
                                    :opts {:project project
                                           :quiet quiet}
                                    :state {:failed false
                                            :skipping false}
                                    :strategy {:continue-on-error continue-on-error}}
                           results []]
                    (if (empty? suites)
                      results
                      (P/let [[suite & suites] suites
                              suite-results (run-suite context suite)
                              context (update-in context [:state :skipping]
                                                 #(or %
                                                      (and (failure? suite-results)
                                                           (not (get-in context [:strategy :continue-on-error])))))
                              results (conj results suite-results)]
                        (P/recur suites context results))))
          summary (summarize results)]

    (write-results-file opts summary)
    (print-results opts summary)

    (when (failure? summary) (fatal 1))))
