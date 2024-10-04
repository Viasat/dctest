;; Copyright (c) 2024, Viasat, Inc
;; Licensed under EPL 2.0

(ns dctest.core
  (:require [cljs-bean.core :refer [->clj]]
            [clojure.edn :as edn]
            [clojure.string :as S]
            [clojure.walk :refer [postwalk]]
            [dctest.expressions :as expr]
            [dctest.outcome :refer [failure? pending? pending-> short-outcome
                                    fail! pass! skip!]]
            [dctest.util :as util :refer [obj->str js->map log indent indent-print-table-str]]
            [promesa.core :as P]
            [viasat.retry :as retry]
            [viasat.util :refer [fatal parse-opts write-file Eprintln]]
            [viasat.deps :refer [resolve-dep-order]]
            ["util" :refer [promisify]]
            ["stream" :as stream]
            ["child_process" :as cp]
            #_["dockerode$default" :as Docker]
            )
  (:require-macros dctest.outcome))

;; TODO: use require syntax when shadow-cljs works with "*$default"
(def Docker (js/require "dockerode"))

(def usage "
Usage:
  dctest [options] <project> <test-suite>...

Options:
  --test-filter TESTS           Comma separated list of test names to run
                                [default: *]
  --continue-on-error           Continue running tests, even if one fails
  --quiet                       Only print final totals
  --verbose-commands            Show live stdout/stderr from test commands
  --results-file RESULTS-FILE   Write JSON results to RESULTS-FILE
  --schema-file SCHEMA          Path to schema file [env: DCTEST_SCHEMA]
                                [default: ./schema.yaml]
")

(set! *warn-on-infer* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generic command execution

(defn outer-spawn [command {:keys [env shell stdout stderr] :as opts}]
  (P/create
    (fn [resolve reject]
      (let [[cmd args shell] (if (string? command)
                               [command nil shell]
                               [(first command) (rest command) false])
            cp-opts (merge {:stdio "pipe" :shell shell}
                           (dissoc opts :stdout :stderr))
            child (doto (cp/spawn cmd (clj->js args) (clj->js cp-opts))
                    (.on "close" (fn [code signal]
                                   (if (= 0 code)
                                     (resolve {:code code})
                                     (reject {:code code :signal signal})))))]
        (when stdout (doto (.-stdout child) (.pipe stdout)))
        (when stderr (doto (.-stderr child) (.pipe stderr)))))))

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
  [container command {:keys [env shell stdout stderr] :as opts}]
  (P/let [cmd (if (string? command)
                [shell "-c" command]
                command)
          stdout (or stdout (stream/PassThrough.))
          stderr (or stderr (stream/PassThrough.))
          env (mapv (fn [[k v]] (str k "=" v)) env)
          exec-opts (merge {:AttachStdout true :AttachStderr true :Env env}
                           (dissoc opts :env :stdout :stderr)
                           {:Cmd cmd})
          exec (.exec container (clj->js exec-opts))
          stream (.start exec)
          _ (-> (.-modem container)
                (.demuxStream stream stdout stderr))
          inspect-fn (.bind (promisify (.-inspect exec)) exec)
          data (P/loop []
                 (P/let [data (inspect-fn)]
                   (if (.-Running data)
                     (P/do
                       (P/delay WAIT-EXEC-SLEEP)
                       (P/recur))
                     (P/-> data ->clj))))]
    (when-not (zero? (:ExitCode data))
      (throw (ex-info (str "Error running command: " (pr-str command))
                      data)))
    data))

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

(defn interpolate-any [context v]
  (let [f #(expr/interpolate-text context %)]
    (cond
      (nil? v)    nil
      (string? v) (f v)
      (map? v)    (update-vals v f)
      :else       (mapv f v))))

(defn run-exec
  "Executes a step 'run' command on either the outer/host platform
  or the docker compose service. Step must already be interpolated.
  Returns 'error' for non-zero exit code or unexpected exceptions,
  along with any gathered stdout/stderr, code, and signal."
  [context step]
  (P/let [{:keys [docker env]} context
          {:keys [project verbose-commands]} (:opts context)
          {:keys [index shell]} step
          {command :run target :exec} step

          stdout (atom [])
          stderr (atom [])
          stdout-stream (doto (stream/PassThrough.)
                          (.on "data" #(let [s (.toString % "utf8")]
                                         (swap! stdout conj s)
                                         (when verbose-commands
                                           (println (indent s "       "))))))
          stderr-stream (doto (stream/PassThrough.)
                          (.on "data" #(let [s (.toString % "utf8")]
                                         (swap! stderr conj s)
                                         (when verbose-commands
                                           (Eprintln (indent s "       "))))))
          _ (when verbose-commands (println (indent (str command) "       ")))

          cmd-opts {:env env
                    :shell shell
                    :stdout stdout-stream
                    :stderr stderr-stream}
          run (if (contains? #{:host ":host"} target)
                #(outer-exec command cmd-opts)
                #(compose-exec docker project target index command cmd-opts))
          result (P/catch (run)
                   (fn [err] {:error {:message (.-message err)}}))

          {:keys [code signal ExitCode error]} result
          code (or code ExitCode)
          step (if error
                 (fail! step error)
                 step)]
    (merge step
           {:stdout (S/join "" @stdout)
            :stderr (S/join "" @stderr)}
           (when code {:code code})
           (when signal {:signal signal}))))

(defn run-expectations [context step]
  (if-let [error (first
                   (keep (fn [expr]
                           (when-not (expr/read-eval context expr)
                             {:message (str "Condition not met: " expr)
                              :debug (expr/explain-refs context expr)}))
                         (:expect step)))]
    (fail! step error)
    step))

(defn execute-step-retries [context step]
  (P/let [{:keys [interval retries]} (:repeat step)
          run-attempt (fn []
                        (P/let [step (run-exec context step)
                                context (assoc context :step step)
                                step (pending-> step
                                                (->> (run-expectations context)))]
                          step))]
    (retry/retry-times run-attempt
                       retries
                       {:delay-ms interval
                        :check-fn #(not (failure? %))})))

(defn skip-if-necessary [context m]
  (if (expr/read-eval context (:if m))
    m
    (skip! m)))

(defn execute-step [context step]
  (P/let [step (pending-> step
                          (->> (skip-if-necessary context))
                          (update :env #(interpolate-any context %)))
          context (update context :env merge
                          (when (pending? step) (:env step)))

          step (pending-> step
                          (update :name #(interpolate-any context %))
                          (update :exec #(interpolate-any context %))
                          (update :run #(interpolate-any context %))
                          (->> (execute-step-retries context))
                          pass!)]
    (select-keys step [:outcome :name :error])))

(defn execute-steps [context test]
  (P/loop [steps (:steps test)
           test (assoc test :steps [])
           context context]
    (if (empty? steps)
      test
      (P/let [[step & steps] steps
               context (if (failure? test)
                         (assoc-in context [:state :failed] true)
                         context)
              step (execute-step context step)
              test (update test :steps conj step)
              test (if (failure? step)
                     (fail! test)
                     test)]
        (P/recur steps test context)))))

(defn run-test [context suite test]
  (P/let [opts (:opts context)

          test (pending-> test
                          (update :env #(interpolate-any context %)))
          context (update context :env merge
                          (when (pending? test) (:env test)))

          test (pending-> test
                          (update :name #(interpolate-any context %))
                          (->> (execute-steps context))
                          pass!)
          _ (log opts "    " (short-outcome test) (:name test))]

    (select-keys test [:id :name :outcome :steps :error])))

(defn filter-tests [graph filter-str]
  (let [raw-list (if (= "*" filter-str)
                     (keys graph)
                     (S/split filter-str #","))]
    (keys (select-keys graph raw-list))))

(defn resolve-test-order [test-map filter-str]
  (let [dep-graph (into {} (for [[id test] test-map]
                             (let [depends (:depends test)]
                               [id (if (map? depends)
                                     (update-keys depends keyword)
                                     (if (string? depends)
                                       [depends]
                                       depends))])))
        start-list (filter-tests dep-graph filter-str)
        dep-graph (assoc dep-graph :START start-list)
        ordered-test-ids (->> (resolve-dep-order dep-graph :START)
                              (filter #(not= :START %)))]
    (for [id ordered-test-ids]
      (-> test-map
          (get id)
          (assoc :id id)))))

(defn run-tests [context suite]
  (P/loop [tests (:tests suite)
           suite (assoc suite :tests [])
           context context]
    (if (or (empty? tests)
            (and (failure? suite)
                 (not (get-in context [:strategy :continue-on-error]))))
      suite
      (P/let [[test & tests] tests
              test (run-test context suite test)
              suite (update suite :tests conj test)
              suite (if (failure? test)
                      (fail! suite)
                      suite)]
        (P/recur tests suite context)))))

(defn run-suite [context suite]
  (P/let [opts (:opts context)

          suite (pending-> suite
                           (update :env #(interpolate-any context %)))
          context (update context :env merge
                          (when (pending? suite) (:env suite)))

          suite (pending-> suite
                           (update :name #(interpolate-any context %))
                           (update :tests #(resolve-test-order % (:test-filter opts))))
          _ (log opts)
          _ (log opts "  " (:name suite))
          suite (pending-> suite
                           (->> (run-tests context))
                           pass!)]

    (select-keys suite [:outcome :name :tests])))

(defn run-suites [context suites]
  (P/loop [results []
           remaining suites]
    (if (or (empty? remaining)
            (and (some failure? results)
                 (not (get-in context [:strategy :continue-on-error]))))
      results
      (P/let [[suite & remaining] remaining
              suite (run-suite context suite)
              results (conj results suite)]
        (P/recur results remaining)))))

(defn summarize-errors [suites]
  (let [step-error (fn [path step]
                     (let [path (conj path (:name step))]
                       (when-let [error (:error step)]
                         (assoc error :path path))))
        test-errors (fn [path test]
                      (let [path (conj path (:name test))
                            errors (when-let [error (:error test)]
                                     [(assoc error :path path)])]
                        (into errors
                              (keep #(step-error path %) (:steps test)))))
        suite-errors (fn [suite]
                       (let [path [(:name suite)]
                             errors (when-let [error (:error suite)]
                                      [(assoc error :path path)])]
                         (into errors
                               (mapcat #(test-errors path %) (:tests suite)))))]
    (vec (mapcat suite-errors suites))))

(defn summarize [results]
  (let [outcome (if (some failure? results) :failed :passed)
        errors (summarize-errors results)

        tests (mapcat :tests results)
        test-totals (merge {:passed 0 :failed 0}
                           (frequencies (map :outcome tests)))]
    {:summary (merge {:outcome outcome}
                     test-totals)
     :tests tests
     :errors errors}))

(defn print-results [opts summary]
  (when (seq (:errors summary))
    (log opts)
    (log opts "   Errors:"))

  (doseq [[index error] (map-indexed vector (:errors summary))]
    (log opts)
    (log opts "  " (inc index) ")" (:message error))
    (log opts "     in:" (S/join " > " (:path error)))

    (let [columns ["reference" "value"]
          details (:debug error)]
      (when details
        (log opts
             (indent-print-table-str columns
                                     (map #(zipmap columns %) details)
                                     "      ")))))

  (let [summary (:summary summary)]
    (log opts)
    (log opts
         (:passed summary) "passing,"
         (:failed summary) "failed")))

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

(defn normalize [suite path]
  (let [->step (fn [index step]
                 (-> step
                     (assoc :outcome :pending)
                     (update :env update-keys name)
                     (update :expect #(if (string? %) [%] %))
                     (update :index #(or % 1))
                     (update :name #(or % (str "steps[" index "]")))
                     (update-in [:repeat :interval] parse-interval)))
        ->test (fn [test id]
                 (-> (merge {:name id} test)
                     (assoc :outcome :pending)
                     (update :env update-keys name)
                     (update :steps #(vec (map-indexed ->step %)))))
        ->suite (fn [suite path]
                  (-> (merge {:name path} suite)
                      (assoc :outcome :pending)
                      (update :env update-keys name)
                      (update :tests update-keys name)
                      (update :tests #(into {} (for [[id t] %]
                                                 [id (->test t id)])))))]
    (->suite suite path)))

(defn load-test-suite! [opts path]
  (P/let [schema (util/load-yaml (:schema-file opts))
          suite (P/-> (util/load-yaml path)
                      (util/check-schema schema true)
                      (normalize path))]
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
          {:keys [continue-on-error project test-suite test-filter
                  quiet verbose-commands]} opts
          _ (when (empty? test-suite)
              (Eprintln (str "WARNING: no test-suite was specified")))

          context {:docker (Docker.)
                   :env {"COMPOSE_PROJECT_NAME" project}
                   :process (js-process)
                   :opts {:project project
                          :quiet quiet
                          :test-filter test-filter
                          :verbose-commands verbose-commands}
                   :strategy {:continue-on-error continue-on-error}}

          suites (P/all
                   (for [path test-suite]
                     (load-test-suite! opts path)))

          results (run-suites context suites)
          summary (summarize results)]

    (write-results-file opts summary)
    (print-results opts summary)

    (when (failure? summary) (fatal 1))))
