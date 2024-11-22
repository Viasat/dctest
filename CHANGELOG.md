# Changelog

## v0.4.0
* CLI option `--environ-file`

## v0.3.1
* Fix: exit code regression for failed runs

## v0.3.0
* Fix: repeat step commands, if expect expressions fail
* Fix: catch errors thrown in expressions, and fail associated step
* Specify results file format via JSON schema
* Add initial JSON conversion and string functions to expressions
* Show suite/test/step location of failures/errors in results summary
* Support interpolation in suite/test/step names
* Record start/stop timestamps
* Remove `--verbose-results` CLI option

## v0.2.0
* CLI options `--test-filter`
* Support `if` expressions on steps
* Support `expect` assertion expressions on steps
* Support `shell` on steps
* Support `depends` ordering of tests
* Check user-defined expressions are syntactically valid at dctest startup
* Support `step.stdout`/`step.stderr` and `==`/`!=` in expressions
* Default names for suites and tests, if unset

## v0.1.1
* Fix: better nbb classpath resolution when installed via npm

## v0.1.0
* CLI options `--continue-on-error`, `--schema-file`, `--verbose-commands`, 
  and `--verbose-results`
* Support `env` maps on suites, tests, and steps
* Support `run` command (array or string) in containers or on host using `:host`
* Support `repeat` to enable step retries
* Capture signals interrupts from `run` commands
* Basic expression/interpolation support, including `env` context and step
  status functions
* Add `process` context (pid, etc) and add `COMPOSE_PROJECT_NAME` to `env`
