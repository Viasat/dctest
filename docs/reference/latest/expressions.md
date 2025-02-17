# Expressions

Expressions can be used by themselves, such as in step `if` or `expect` keys,
or interpolated into strings using the `${{ ... }}` syntax. This reference lists
everything that is possible within an expression.

## Types

Expressions support the following types:

* null
* numbers
* booleans
* strings (single and double quote)
* arrays
* objects

Items in arrays can be accessed by index with `arr[index]` syntax. Object
values can be accessed with `obj['key']` syntax or `obj.key` syntax.

## Operators

The following operators are supported:

* equality (`==` and `!=`)
* logical and (`&&`) and or (`||`)
* basic math (`+`, `-`, `*`, `/`)

Evaluation precedence can be controlled with parenthetical groupings (`(...)`).

## Contexts

### env

The `env` context allows expressions to reference variables set in the `env` by
the surrounding suite, test, or step. For instance, the following example sets
the variable `PERSON` in the suite `env`, which can be referenced and
interpolated into the `name` and `run` of a step:

```yaml
name: Example Suite
env:
  PERSON: Jill
tests:
  example-env-expressions:
    steps:
      - name: ${{ env.PERSON }} Compliment
        exec: node1
        run: echo "${{ env.PERSON }} is awesome!"
```

The following list of variables is automatically set in the `env` by dctest for
all suites automatically:

* `env.COMPOSE_PROJECT_NAME` - the Docker Compose project name provided to
  dctest via CLI

### process

dctest runs on Node.js, and provides access to the following subset of
[process](https://nodejs.org/api/process.html) attributes:

| name | type | description |
| ---- | ---- | ----------- |
| `process.argv` | array | CLI arguments at launch |
| `process.env` | object | user environment (outside of dctest) |
| `process.features` | object | capabilities such as IPv6 support |
| `process.pid` | integer | PID of the process |
| `process.platform` | string | OS platform for which the Node.js binary was compiled |
| `process.ppid` | integer | PID of the parent of the current process |
| `process.versions` | object | version strings of Node.js and its dependencies |

### step

The `step` context references the step itself and contains the following:

| name | type | description |
| ---- | ---- | ----------- |
| `step.stdout` | string | standard output of the command, only available after command is run |
| `step.stderr` | string | standard error of the command, only available after command is run |

### steps

The `steps` context is a mapping of preceeding steps, indexed by `id`, in the
current test. `steps` contains all the information from the `step` plus the
following:

| name | type | description |
| ---- | ---- | ----------- |
| `steps.<step-id>.outputs` | object | resolved `outputs` mappings |

### tests

The `tests` context is a mapping of preceeding tests, indexed by `id`, which
includes the following information:

| name | type | description |
| ---- | ---- | ----------- |
| `tests.<test-id>.outputs` | object | resolved `outputs` mappings |

## Functions and Methods

### Status

Status checks can be useful in step `if` expressions to optionally run or skip
steps, based on the success status of the test:

| name | type | description |
| ---- | ---- | ----------- |
| `always()` | boolean | always returns `true` |
| `success()` | boolean | returns true if no previous step has failed |
| `failure()` | boolean | returns true if a previous step has failed |

### Conversions

Functions and methods to convert from one type or format to another:

| name | type | description |
| ---- | ---- | ----------- |
| `fromJSON(s)` | value | parses string `s` as JSON, returns corresponding value |
| `toJSON(v)` | string | serializes any value `v` as JSON |
| `v.toString()` | string | returns a string representing the value `v` |

### Collections

Functions and methods related to collections of elements:

| name | type | description |
| ---- | ---- | ----------- |
| `c.count()` | integer | return the number of elements in a collection |

### String

Functions and methods related to strings:

| name | type | description |
| ---- | ---- | ----------- |
| `contains(txt, s)` | boolean | returns `true` if string `txt` contains substring `s` |
| `startsWith(txt, s)` | boolean | returns `true` if string `txt` starts with substring `s` |
| `endsWith(txt, s)` | boolean | returns `true` if string `txt` ends with substring `s` |

### Error

Functions and methods related to errors handling:

| name | type | description |
| ---- | ---- | ----------- |
| `throw(msg)` | error | raises an error with message `msg` to fail the suite, test, or step |
