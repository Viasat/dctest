(ns dctest.expressions-test
  (:require [cljs.test :refer-macros [are deftest is testing]]
            [dctest.expressions :as expr]))

(deftest test-basic-interpolation

  ;; Interpolate expressions inside ${{ ... }}
  (are [expected text] (= expected (expr/interpolate-text {} text))
       "this is true."   "this is ${{true}}."
       "this is false."  "this is ${{false}}."
       "before true"     "before ${{ true }}"
       "true after"      "${{ true }} after"
       "true then false" "${{ true }} then ${{ false }}")

  ;; Don't disrupt ${}-like shell commands/expressions
  (are [text] (= text (expr/interpolate-text {} text))
       "this is ${true}."
       "this is {true}."
       "this is ${true}}."
       "this is $ {true}."
       "this is $ {true}}.")

  ;; Always expect ${{ to indicate a valid expression
  (are [text errors] (expr/flatten-errors (expr/read-ast text "InterpolatedText"))
       "this is ${{true}."               [{:message "Invalid Expression at position 8"}]
       "this is ${{true}} and ${{true}." [{:message "Invalid Expression at position 22"}]
       "this is ${{ 1 + }}."             [{:message "Invalid Expression at position 8"}])

  ;; Throw at eval/run-time, if undetected at load/parse-time
  (are [text] (thrown-with-msg? js/Error #"Unchecked errors"
                                (expr/interpolate-text {} text))
       "this is ${{true}."
       "this is ${{ 1 + }}."
       "this is ${{true}} and ${{true}."))

(deftest test-explain-interpolation
  (let [context {:env {"FOO" "123"}
                 :step {:stdout "123"
                        :stderr "456"}}]
    (are [expr result] (= result (expr/explain-refs context expr))
         "1 + 1"
         {}

         "step.stdout == '987'"
         {"step.stdout" "123"}

         "step.stdout == env.FOO"
         {"step.stdout" "123" "env.FOO" "123"}

         "env"
         {"env" {"FOO" "123"}})))

(deftest test-literals-interpolation
  ;; roundtrip
  (are [expr] (= expr (expr/interpolate-text {} (str "${{" expr "}}")))
       "true"
       "false"
       "null"
       "0"
       "1"
       "-1"
       "[]"
       "[1]"
       "[1,2,3]"
       "{}"
       "{\"key\":\"value\"}")

  ;; non-roundtrip
  (are [expected expr] (= expected (expr/interpolate-text {} (str "${{" expr "}}")))
       "1"             "1.0"
       "1"             "1.0000"
       "-1"            "-1.0"
       "abc"           "\"abc\""
       "ab\"c"         "\"ab\\\"c\""
       "abc"           "'abc'"
       "ab'c"          "'ab''c'"
       "[]"            "[ ]"
       "[1]"           "[ 1]"
       "[1]"           "[1 ]"
       "[1]"           "[  1  ]"
       "[1,2]"         "[ 1,2  ]"
       "[1,2]"         "[1 , 2   ]"
       "{}"            "{ }"
       "{\"a\":\"b\"}" "{\"a\":\"b\"}"
       "{\"a\":\"b\"}" "{    \"a\":\"b\"    }")

  ;; Maps can print either way
  (is (contains? #{"{\"a\":\"b\",\"k\":\"v\"}"
                   "{\"k\":\"v\",\"a\":\"b\"}"}
                 (expr/interpolate-text {} "${{ {    \"a\":  \"b\" , \"k\"   :\"v\"  } }}")))

  ;; composite
  (is (= "[1,\"a\",{\"k\":\"v\"}]"
         (expr/interpolate-text {} "${{ [ 1, \"a\", {\"k\":\"v\"} ]  }}"))))

(deftest test-operator-expressions
  (are [expected expr] (= expected (expr/read-eval {} expr))
       ;; equality
       true  "true == true"
       false "true != true"
       false "3 == 4"
       true  "3 != 4"

       ;; and
       true  "true  && true"
       false "false && true"
       false "true  && false"
       false "false && false"
       true  "3     && true"
       3     "true  && 3"
       true  "\"t\" && true"
       "t"   "true  && \"t\""

       ;; or
       true  "true  || true"
       true  "false || true"
       true  "true  || false"
       false "false || false"
       3     "3     || true"
       true  "true  || 3"
       "t"   "\"t\" || true"
       true  "true  || \"t\""

       ;; math
       2  "1 + 1"
       0  "1 - 1"
       2  "2 * 1"
       2  "4 / 2"

       ;; multiple
       true  "true && true && true"
       false "true && true && false"
       true  "true && true || false"
       true  "true || true && false"

       ;; parens
       true  "(true)"
       4     "(4)"
       true  "((true))"
       false "(true && false)"
       true  "true && (true && true)"
       true  "true && (true || false)"
       false "false || (true && false)"

       true  "(true && true) && true"
       true  "true && (true) && true"
       )

  ;; composite
  (is (= [2]
         (expr/read-eval {} "[ 1 + 1 ]")))
  (is (= [2,{"a" 4}]
         (expr/read-eval {} "[ (1 + 1), { \"a\": (2 + 2) } ] "))))


(deftest test-functions
  ;; Test status
  (are [expected state] (= expected (expr/read-eval {:state state} "always()"))
       true {:failed false}
       true {:failed true})

  (are [expected state] (= expected (expr/read-eval {:state state} "success()"))
       true  {:failed false}
       false {:failed true})

  (are [expected state] (= expected (expr/read-eval {:state state} "failure()"))
       false {:failed false}
       true {:failed true})

  ;; Conversion functions
  (are [expected expr] (= expected (expr/read-eval {} expr))
       "{}"    "toJSON({})"
       {}      "fromJSON(\"{}\")"

       ;; roundtrip
       1           "fromJSON(toJSON(1))"
       {"a" 5}     "fromJSON(toJSON({\"a\": 5}))"
       "{\"a\":5}" "toJSON(fromJSON(toJSON({\"a\": 5})))"
       )

   ;; String functions
  (are [expected expr] (= expected (expr/read-eval {} expr))
       true  "contains('Hello World', 'World')"
       false "contains('Hello Worl', 'World')"

       true  "startsWith('Hello World', 'Hello')"
       false "startsWith('Hello World', 'World')"

       false "endsWith('Hello World', 'Hello')"
       true  "endsWith('Hello World', 'World')"
       )

  ;; Check function names/args
  (are [text] (thrown-with-msg? js/Error #"Unchecked errors"
                                (expr/read-eval {:state {:failed false}} text))
       "always(1)"
       "foo()")

  (are [text errors] (= errors (expr/flatten-errors (expr/read-ast text "Expression")))
       "always(1)" [{:message "ArityError: incorrect number of arguments to always"}]
       "foo()"     [{:message "ReferenceError: foo is not supported"}]))

(deftest test-member-expressions
  ;; Supported access patterns
  (are [expected text env] (= expected (expr/read-eval {:env env} text))
       {"foo" 3} "env"                  {"foo" 3}
       3         "env.foo"              {"foo" 3}
       3         "env['foo']"           {"foo" 3}
       3         "env[\"foo\"]"         {"foo" 3}
       nil       "env.bar"              {"foo" 3}
       3         "env . foo"            {"foo" 3}
       9         "env[env.foo].baz"     {"foo" "bar", "bar" {"baz" 9}}
       9         "env [ env.foo ] .baz" {"foo" "bar", "bar" {"baz" 9}})

  ;; Supported contexts
  (let [contexts {:env {"FOO" 1}
                  :process {"pid" 123}
                  :step {:code 0 :stdout "hi" :stderr ""}}]
    (are [expected text] (= expected (expr/read-eval contexts text))
         1    "env.FOO"
         123  "process.pid"
         "hi" "step.stdout"))

  ;; Supported methods
  (are [expected text] (= expected (expr/read-eval {} text))
       ;; toString
       "hi"      "'hi'.toString()"
       "[1,2,3]" "[1, 2, 3].toString()"

       ;; count
       3 "[1, 2, 3].count()")

  ;; Nested properties and methods with function calls
  (are [expected text] (= expected (expr/read-eval {:env {"foo" 3}} text))
       "3"  "env.foo.toString()"
       3    "fromJSON(toJSON(env)).foo"
       "3"  "env.foo.toString()"
       "3"  "env['foo'].toString()"
       3    "fromJSON(env.toString()).foo"
       "3"  "fromJSON(env.toString()).foo.toString()")

  ;; Coerce keywords to strings inside context
  (is (= 123
         (expr/read-eval {:env {:a {:b 123}}} "env.a.b")))

  ;; Reference errors
  (let [invalid "${{ baz }}"]
    (is (thrown-with-msg? js/Error #"Unchecked errors"
                          (expr/interpolate-text {} invalid)))
    (is (= [{:message "ReferenceError: baz is not supported"}]
           (expr/flatten-errors (expr/read-ast invalid "InterpolatedText")))))

  ;; Check method names/args
  (are [text] (thrown-with-msg? js/Error #"Unchecked errors"
                                (expr/read-eval {:env {}} text))
       "env.toString(1)"
       "env.foo()")

  (are [text errors] (= errors (expr/flatten-errors (expr/read-ast text "Expression")))
       "env.toString(1)" [{:message "ArityError: incorrect number of arguments to toString"}]
       "env.foo()"       [{:message "ReferenceError: foo is not supported"}]))
