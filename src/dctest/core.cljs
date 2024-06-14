;; Copyright (c) 2024, Viasat, Inc
;; Licensed under EPL 2.0

(ns dctest.core
  (:require [cljs-bean.core :refer [->clj]]
            [clojure.edn :as edn]
            [clojure.string :as S]
            [clojure.walk :refer [postwalk]]
            [dctest.expressions :as expr]
            [dctest.util :as util :refer [obj->str js->map log indent]]
            [promesa.core :as P]
            [viasat.retry :as retry]
            [viasat.util :refer [fatal parse-opts write-file Eprintln]]
            ["util" :refer [promisify]]
            ["stream" :as stream]
            ["child_process" :as cp]
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
  --verbose-commands            Show live stdout/stderr from test commands
  --verbose-results             Verbose results file (with errors, output, etc)
  --results-file RESULTS-FILE   Write JSON results to RESULTS-FILE
  --schema-file SCHEMA          Path to schema file [env: DCTEST_SCHEMA]
                                [default: ./schema.yaml]
")

(set! *warn-on-infer* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generic command execution

(defn outer-spawn [command {:keys [env shell on-stdout on-stderr] :as opts}]
  (P/create
    (fn [resolve reject]
      (let [[cmd args shell] (if (string? command)
                               [command nil shell]
                               [(first command) (rest command) false])

            stdout (atom [])
            stderr (atom [])
            stdout-stream (doto (stream/PassThrough.)
                            (.on "data" #(let [s (.toString % "utf8")]
                                           (swap! stdout conj s)
                                           (when on-stdout (on-stdout s)))))
            stderr-stream (doto (stream/PassThrough.)
                            (.on "data" #(let [s (.toString % "utf8")]
                                           (swap! stderr conj s)
                                           (when on-stderr (on-stderr s)))))

            cp-opts {:env env :stdio "pipe" :shell shell}
            child (doto (cp/spawn cmd (clj->js args) (clj->js cp-opts))
                    (.on "close" (fn [code signal]
                                   (let [result {:code code
                                                 :stdout (S/join "" @stdout)
                                                 :stderr (S/join "" @stderr)}]
                                     (if (= 0 code)
                                       (resolve result)
                                       (reject (assoc result :signal signal)))))))]
        (doto (.-stdout child) (.pipe stdout-stream))
        (doto (.-stderr child) (.pipe stderr-stream))))))

(defn outer-exec [command opts]
  (P/catch
    (outer-spawn command opts)
    (fn [err]
      (throw (ex-info (str "Error running command: " (pr-str command))
                      err)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Docker/Compose

(def WAIT-EXEC-SLEEP 200)

(defn docker-exec
  "[Async] Exec a command in a container and wait for it to complete
  (using wait-exec). Resolves to exec data with additional :Stdout and
  and :Stderr keys."
  [container command {:keys [env shell on-stdout on-stderr] :as opts}]
  (P/let [cmd (if (string? command)
                [shell "-c" command]
                command)

          stdout (atom [])
          stderr (atom [])
          stdout-stream (doto (stream/PassThrough.)
                          (.on "data" #(let [s (.toString % "utf8")]
                                         (swap! stdout conj s)
                                         (when on-stdout (on-stdout s)))))
          stderr-stream (doto (stream/PassThrough.)
                          (.on "data" #(let [s (.toString % "utf8")]
                                         (swap! stderr conj s)
                                         (when on-stderr (on-stderr s)))))

          env (mapv (fn [[k v]] (str k "=" v)) env)
          exec-opts {:AttachStdout true :AttachStderr true :Cmd cmd :Env env}
          exec (.exec container (clj->js exec-opts))
          stream (.start exec)
          _ (-> (.-modem container)
                (.demuxStream stream stdout-stream stderr-stream))
          inspect-fn (.bind (promisify (.-inspect exec)) exec)
          data (P/loop []
                 (P/let [data (inspect-fn)]
                   (if (.-Running data)
                     (P/do
                       (P/delay WAIT-EXEC-SLEEP)
                       (P/recur))
                     (P/-> data ->clj))))
          result {:code (:ExitCode data)
                  :stdout (S/join "" @stdout)
                  :stderr (S/join "" @stderr)}]
    (when-not (zero? (:code result))
      (throw (ex-info (str "Error running command: " (pr-str command))
                      result)))
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

(defn compose-exec
  [docker project service index command opts]
  (P/let [container (dc-service docker project service index)]
    (when-not container
      (throw (ex-info (str "No container found for service "
                           "'" service "' (index=" index ")")
                      {})))
    (docker-exec container command opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Runner

(defn failure? [{:keys [outcome]}]
  (= :fail outcome))

(defn short-outcome [{:keys [outcome]}]
  (get {:pass "✓" :fail "F" :skip "S"} outcome "?"))

(defn execute-step* [context step]
  (P/let [{:keys [docker opts]} context
          {:keys [project verbose-commands]} opts
          {:keys [index shell]
           , target :exec command :run} step
          {:keys [interval retries]} (:repeat step)
          index (or index 1)

          ;; Interpolate step env before other keys
          step-env (update-vals (:env step) #(expr/interpolate-text context %))
          context (update context :env merge step-env)

          interpolate #(expr/interpolate-text context %)
          target (interpolate target)
          command (if (string? command)
                    (interpolate command)
                    (mapv interpolate command))

          _ (when verbose-commands (println (indent (str command) "       ")))
          log (when verbose-commands #(Eprintln (indent % "       ")))
          cmd-opts {:env (:env context)
                    :shell shell
                    :on-stdout log
                    :on-stderr log}
          run-exec (if (contains? #{:host ":host"} target)
                     #(outer-exec command cmd-opts)
                     #(compose-exec docker project target index command cmd-opts))
          result (retry/retry-times #(P/catch
                                       (P/let [res (run-exec)]
                                         {:result res})
                                       (fn [err]
                                         {:error err}))
                                    retries
                                    {:delay-ms interval
                                     :check-fn :result})]
    result))

(defn execute-step [context step]
  (P/let [{step-name :name} step

          result (if (get-in context [:state :failed])
                   {:outcome :skip}
                   (P/let [result (execute-step* context step)]
                     (assoc result :outcome (if (:error result) :fail :pass))))
          results (merge result
                         (when step-name {:name step-name}))
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
          test-env (update-vals (:env test) #(expr/interpolate-text context %))
          context (-> context
                      (assoc-in [:state :failed] false)
                      (update :env merge test-env))
          steps (execute-steps context (:steps test))
          results (:results steps)
          outcome (if (some failure? results) :fail :pass)
          error (first (filter failure? results))]
    (merge {:outcome outcome
            :steps results}
           (when test-name {:name test-name})
           (when error {:error (:error error)}))))

(defn run-suite [context suite]
  (log (:opts context) "  " (:name suite))
  (P/let [suite-env (update-vals (:env suite) #(expr/interpolate-text context %))
          context (update context :env merge suite-env)
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

(def VERBOSE-SUMMARY-KEYS [:result :stdout :stderr])

(defn summarize [results verbose-results]
  ;; Note: Suite can fail in setup/teardown, when all tests pass
  (let [overall (if (some failure? results) :fail :pass)
        test-totals (frequencies (map :outcome (mapcat :tests results)))
        results-data (if verbose-results
                       results
                       (postwalk #(if (map? %)
                                    (apply dissoc % VERBOSE-SUMMARY-KEYS)
                                    %)
                                 results))]
    (merge {:pass 0 :fail 0}
           test-totals
           {:outcome overall :results results-data})))

(defn print-results [opts summary]
  (doseq [suite (:results summary)]
    (doseq [[index {test-name :name
                    error :error}]
            , (->> (:tests suite)
                   (filter failure?)
                   (map-indexed vector))]
      (log opts "  " (inc index) ")" test-name)
      (log opts "     " (.-message error))
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
  (P/let [schema (util/load-yaml (:schema-file opts))
          suite (P/-> (util/load-yaml path)
                      (util/check-schema schema true)
                      normalize)]
    suite))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main

(defn js-process []
  (let [process (select-keys (js->map js/process)
                             ["platform" "pid" "ppid" "argv"
                              "versions" "features"])
        env (into {} (js->map js/process.env))]
    (merge process
           {"env" env})))

(defn -main [& argv]
  (P/let [opts (parse-opts usage (or argv #js []))
          {:keys [continue-on-error project test-suite
                  quiet verbose-commands verbose-results]} opts
          _ (when (empty? test-suite)
              (Eprintln (str "WARNING: no test-suite was specified")))

          suites (P/all
                   (for [path test-suite]
                     (load-test-suite! opts path)))

          results (P/loop [suites suites
                           context {:docker (Docker.)
                                    :env {"COMPOSE_PROJECT_NAME" project}
                                    :process (js-process)
                                    :opts {:project project
                                           :quiet quiet
                                           :verbose-commands verbose-commands}
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
          summary (summarize results verbose-results)]

    (write-results-file opts summary)
    (print-results opts summary)

    (when (failure? summary) (fatal 1))))
