# Input Syntax

The following reference covers what is available in suites, tests, and steps.
Any type clarifications can be found in the glossary at the bottom.

> NOTE: The input format is validated with [JSON Schema](https://github.com/Viasat/dctest/blob/main/schemas/input.yaml).

## Suite

| name | type | description | default |
| ---- | ---- | ----------- | ------- |
| `env` | map(str, istr) | set environment variables for all tests and steps in the suite | `{}` |
| `name` | istr | a human-readable suite name | filename |
| `tests` | map(str, test) | set of tests contained by the suite | `{}` |

> NOTE: `env` values are interpolated first and available to all other keys.

## Test

| name | type | description | default |
| ---- | ---- | ----------- | ------- |
| `env` | map(str, istr) | set environment variables for all steps in the test, shadows suite `env` | `{}` |
| `depends` | str or list(str) | test(s) by id that should be run before this test | `[]` |
| `name` | istr | a human-readable test name | test id |
| `outputs` | map(str, istr) | accessible to future tests via `tests` context; interpolated after steps | `{}` |
| `steps` | list(step) | steps to be run | `[]` |

> NOTE: `env` values are interpolated first and available to all other keys.
> NOTE: `outputs` will always be interpolated, even if the test has failed.

## Step

| name | type | description | default |
| ---- | ---- | ----------- | ------- |
| `env` | map(str, istr) | set environment variables for command and expressions in the step, shadows suite and test `env` | `{}` |
| `exec` | istr | location to execute `run` command, either a Docker Compose service name or `:host` | **required** |
| `expect` | expr or list(expr) | additional success conditions, evaluated after `run` command, all of which must return a truthy value | `[]` |
| `id` | str | identifier for the step, referenced in `steps` context | |
| `if` | expr | execute the step, when result is truthy; otherwise, skip | `success()` |
| `index` | int | references the index of the container to execute `run` commmand | `1` |
| `name` | istr | a human-readable step name | step index |
| `outputs` | map(str, istr) | accessible to future steps via `steps` context; interpolated after `run` | `{}` |
| `repeat` | map(str, any) | presence indicates a step should be retried if the `run` or any `expect` condition fails | `null` |
| `repeat.interval` | str | time to wait between retries in Docker Compose healthcheck format, ex: `1m20s` | `1s` |
| `repeat.retries` | int | indicates number of retry attempts; retry indefinitely, if omitted | `null` |
| `run` | istr or list(istr) | command to be executed, must return zero exit code for step to succeed | **required** |
| `shell` | str | set shell for the step, either `sh` or `bash` | `sh` |

> NOTE: `if` is evaluated before `env`. When `if` is truthy, `env`
        values are interpolated and available to all other keys.
> NOTE: Unless step is skipped entirely using `if`, `outputs` will
        be always interpolated, even if the step has failed.

## Glossary

Basic types:

* `any` - a value of any type
* `str` - an uninterpolatable string
* `istr` - an interpolatable string (may contain `${{ ... }}`)
* `int` - an integer
* `expr` - a string with a valid [expression][1] (no wrapping `${{ ... }}`)
* `list(i)` - list with items of type `i`
* `map(k,v)` - mapping with keys of type `k` and values of type `v`

[1]: /reference/latest/expressions
