# Input Syntax

## Overview

dctest runs suites of tests, where each test can have any number of steps.
Here is a very simple suite that defines a single test with a single step:

```yaml
name: Example Suite
tests:
  example-1:
    name: Test echo in node1
    steps:
      - name: Step 1
        exec: node1
        run: echo "Hi from node1"
```

> NOTE: The input format is defined in [JSON Schema](https://github.com/Viasat/dctest/blob/main/schemas/input.yaml).

Tests must be defined with a unique id, which is how they are referenced by
other tests or on the CLI (see `--test-filter`). Our lone test above has id
`example-1`.  The suite, test, and step names are for human readability and
are shown in results output.

Tests' steps are evaluated in order, top to bottom. Each step must define a
command (`run`) and a location to run it (`exec`). The `exec` location is almost
always the Docker Compose service name for a container, which makes each step
analogous to a `docker compose exec <service> <command>`. In our example, the
first step demonstrates using `echo` in the `node1` service. If the container is
running and the command returns a zero exit code, this step will succeed. When
all steps succeed, the test passes!

Let's elaborate on our test a bit, using a few more dctest features:

```yaml
name: Example Suite
tests:
  example-1:
    name: Test echo in node1
    env:
      SALUTATION: boss
    steps:
      - name: Step 1
        exec: node1
        run: echo "Howdy, ${{ env.SALUTATION }}."

      - name: Step 2
        exec: node1
        run: |
          echo "YAML multiline strings can be useful ..."
          echo "... for more complicated commands."
        expect:
          - contains(step.stdout, "multiline")
```

The test now defines an `env` mapping, which defines environment variables to
be used while executing all step commands in this test. `env` is supported at
the suite, test, and step levels.

The first step now makes use of an interpolated [expression][1]
in its `run`, using the special syntax `${{ ... }}`. When dctest sees that
special syntax, it will evaluate the expression and interpolate in the result
before running. This expression simply looks up the variable `SALUTATION` from
the environment, and it wouldn't have made a big difference to let the shell
do its own substitution with the command `echo "Howdy ${SALUTATION}"`. Many
aspects of suites, tests, and steps are interpolatable and noted as such in
the reference below.

The second step defines a more elaborate command, taking advantage of YAML
multiline strings. This is a common pattern for clarity (and sometimes
necessary for valid YAML, if using interpolations and expressions). This step
also adds an additional success condition via `expect`, which does not need the
`${{ ... }}` syntax. This example condition checks that the command's standard
output contained the word "multiline".

There are more [runnable examples](https://github.com/Viasat/dctest/tree/main/examples)
that demonstrate other features. The following reference documentation covers
what is available in suites, tests, and steps. Any type clarifications can be
found in the glossary at the bottom.

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
| `steps` | list(step) | steps to be run | `[]` |

> NOTE: `env` values are interpolated first and available to all other keys.

## Step

| name | type | description | default |
| ---- | ---- | ----------- | ------- |
| `env` | map(str, istr) | set environment variables for command and expressions in the step, shadows suite and test `env` | `{}` |
| `exec` | istr | location to execute `run` command, either a Docker Compose service name or `:host` | **required** |
| `expect` | expr or list(expr) | additional success conditions, evaluated after `run` command, all of which must return a truthy value | `[]` |
| `if` | expr | execute the step, when result is truthy; otherwise, skip | `success()` |
| `index` | int | references the index of the container to execute `run` commmand | `1` |
| `name` | istr | a human-readable step name | step index |
| `repeat` | map(str, value) | presence indicates a step should be retried if the `run` or any `expect` condition fails | `null` |
| `repeat.interval` | str | time to wait between retries in Docker Compose healthcheck format, ex: `1m20s` | `1s` |
| `repeat.retries` | int | indicates number of retry attempts; retry indefinitely, if omitted | `null` |
| `run` | istr or list(istr) | command to be executed, must return zero exit code for step to succeed | **required** |
| `shell` | str | set shell for the step, either `sh` or `bash` | `sh` |

> NOTE: `if` is evaluated before `env`. When `if` is truthy, `env`
        values are interpolated and available to all other keys.

## Glossary

Basic types:

* `value` - a value of any type
* `str` - an uninterpolatable string
* `istr` - an interpolatable string (may contain `${{ ... }}`)
* `int` - an integer
* `expr` - a string with a valid [expression][1] (no wrapping `${{ ... }}`)
* `list(i)` - list with items of type `i`
* `map(k,v)` - mapping with keys of type `k` and values of type `v`

[1]: /reference/latest/expressions
