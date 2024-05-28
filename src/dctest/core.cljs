;; Copyright (c) 2024, Viasat, Inc
;; Licensed under EPL 2.0

(ns dctest.core
  (:require [cljs-bean.core :refer [->clj]]
            [clojure.edn :as edn]
            [clojure.string :as S]
            [dctest.util :as util :refer [obj->str log]]
            [promesa.core :as P]
            [viasat.retry :as retry]
            [viasat.util :refer [fatal parse-opts write-file]]
            ["stream" :as stream]
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

(defn execute-step* [context step]
  (P/let [{:keys [docker opts]} context
          {:keys [project]} opts
          {service :exec index :index command :run} step
          {:keys [interval retries]} (:repeat step)
          env (merge (:env context) (:env step))
          index (or index 1)

          run-expect! (fn [result]
                        (when-not (zero? (:ExitCode result))
                          (throw (ex-info (str "Non-zero exit code for command: " command)
                                          {}))))
          run-exec (fn []
                     (P/catch
                       (P/let [container (dc-service docker project service index)
                               _ (when-not container
                                   (throw (ex-info (str "No container found for service '" service "' (index=" index ")")
                                                   {})))
                               env (mapv (fn [[k v]] (str k "=" v)) env)
                               result (P/then (docker-exec container command {:Env env})
                                              ->clj)]
                         (run-expect! result)
                         {:result result})
                       (fn [err]
                         {:error (.-message err)})))
          exec (retry/retry-times run-exec
                                  retries
                                  {:delay-ms interval
                                   :check-fn :result})]

    (when-let [msg (:error exec)]
      (throw (ex-info msg {})))

    context))

(defn execute-step [context step]
  (P/let [{step-name :name} step

          {:keys [context outcome error]}
          , (if (get-in context [:state :failed])
              {:context context :outcome :skip}
              (-> (P/let [context (execute-step* context step)]
                    {:context context :outcome :pass})
                  (P/catch
                    (fn [err]
                      {:context context :outcome :fail :error (.-message err)}))))
          results (merge {:outcome outcome}
                         (when step-name {:name step-name})
                         (when error {:error error}))
          context (update-in context [:state :failed] #(or % (failure? results)))]
    {:context context
     :results results}))

(defn execute-steps [context steps]
  (P/let [results (P/loop [steps steps
                           context context
                           results []]
                    (if (empty? steps)
                      results
                      (P/let [[step & steps] steps

                              {context :context
                               step-results :results} (execute-step context step)

                              results (conj results step-results)]
                        (P/recur steps context results))))]
    {:context context
     :results results}))

(defn run-test [context suite test]
  (P/let [test-name (:name test)
          context (-> context
                      (assoc-in [:state :failed] false)
                      (update :env merge (:env test)))
          steps (execute-steps context (:steps test))
          results (:results steps)
          outcome (if (some failure? results) :fail :pass)
          error (->> (filter failure? results)
                     first
                     :error)]
    (merge {:outcome outcome
            :steps results}
           (when test-name {:name test-name})
           (when error {:error error}))))

(defn run-suite [context suite]
  (log (:opts context) "  " (:name suite))
  (P/let [context (update context :env merge (:env suite))
          results (P/loop [tests (vals (:tests suite))
                           context context
                           results []]
                    (if (or (empty? tests)
                            (and (some failure? results)
                                 (not (get-in context [:strategy :continue-on-error]))))
                      results
                      (P/let [[test & tests] tests
                              result (run-test context suite test)
                              results (conj results result)]
                        (log (:opts context) "    " (short-outcome result) (:name result))
                        (P/recur tests context results))))
          outcome (if (some failure? results) :fail :pass)]

    (log (:opts context)) ; breath between suites

    {:outcome outcome
     :name (:name suite)
     :tests results}))

(defn summarize [results]
  ;; Note: Suite can fail in setup/teardown, when all tests pass
  (let [overall (if (some failure? results) :fail :pass)
        test-totals (frequencies (map :outcome (mapcat :tests results)))]
    (merge {:pass 0 :fail 0}
           test-totals
           {:outcome overall :results results})))

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
       (:fail summary) "failed"))

(defn write-results-file [opts summary]
  (when-let [path (:results-file opts)]
    (write-file path (obj->str summary))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Load Suite

;; see schema.yaml
(def INTERVAL-RE #"^\s*((\d+)m)?((\d+)s)?\s*$") ; 1m20s

(defn parse-interval [time-str]
  (let [[_ _ minutes _ seconds] (re-matches INTERVAL-RE time-str)
        minutes (edn/read-string (or minutes "0"))
        seconds (edn/read-string (or seconds "0"))]
    (-> (* minutes 60)
        (+ seconds)
        (* 1000))))

(defn normalize [suite]
  (let [->step (fn [step]
                 (-> step
                     (update :env update-keys name)
                     (update-in [:repeat :interval] parse-interval)))
        ->test (fn [test]
                 (-> test
                     (update :env update-keys name)
                     (update :steps #(mapv ->step %))))]
    (-> suite
        (update :env update-keys name)
        (update :tests update-keys name)
        (update :tests update-vals ->test))))

(defn load-test-suite! [opts path]
  (P/let [schema (util/load-yaml "schema.yaml")
          suite (P/-> (util/load-yaml path)
                      (util/check-schema schema true)
                      normalize)]
    suite))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main

(defn -main [& argv]
  (P/let [opts (parse-opts usage (or argv #js []))
          {:keys [continue-on-error quiet project test-suite]} opts

          suites (P/all
                   (for [path test-suite]
                     (load-test-suite! opts path)))

          results (P/loop [suites suites
                           context {:docker (Docker.)
                                    :env {}
                                    :opts {:project project
                                           :quiet quiet}
                                    :strategy {:continue-on-error continue-on-error}}
                           results []]
                    (if (or (empty? suites)
                            (and (some failure? results)
                                 (not (get-in context [:strategy :continue-on-error]))))
                      results
                      (P/let [[suite & suites] suites
                              suite-results (run-suite context suite)
                              results (conj results suite-results)]
                        (P/recur suites context results))))
          summary (summarize results)]

    (write-results-file opts summary)
    (print-results opts summary)

    (when (failure? summary) (fatal 1))))
