# Results File Schema

dctest can optionally write a summary of results as JSON data to a file (see
the `--results-file` CLI option). This JSON data contains the following
top-level keys, which are further described below:

* `summary` - overall outcome, runtime, and test outcome counts
* `tests` - all tests executed as part of dctest run
* `errors` - an array of errors encountered during execution

> NOTE: The results file is formally defined with [JSON Schema](https://github.com/Viasat/dctest/blob/main/schemas/results-file.yaml).

## Summary

`summary` is a JSON object containing the following keys:

| name | type | description |
| ---- | ---- | ----------- |
| `outcome` | string | the overall outcome of the dctest run, either `passed` or `failed` |
| `passed` | integer | count of successful tests |
| `failed` | integer | count of unsuccessful tests |
| `start` | integer | starting timestamp of the dctest run (milliseconds elapsed since the epoch) |
| `stop` | integer | ending timestamp of the dctest run (milliseconds elapsed since the epoch) |

## Tests

`tests` is a JSON array of objects that contain the following keys:

| name | type | description |
| ---- | ---- | ----------- |
| `id` | string | the test id (may not be unique if more than one suite was used) |
| `name` | string | the test name |
| `outcome` | string | the overall outcome of the test, either `passed` or `failed` |
| `error` | error | the primary/first error that occurred, if the test failed for any reason |
| `additionalErrors` | array(error) | additional errors, if necessary |
| `start` | integer | starting timestamp of the test (milliseconds elapsed since the epoch) |
| `stop` | integer | ending timestamp of the test (milliseconds elapsed since the epoch) |
| `steps` | array(step) | steps executed during this test |

Where steps contain the following keys:

| name | type | description |
| ---- | ---- | ----------- |
| `name` | string | the step name |
| `outcome` | string | the overall outcome of the test, one of `passed`, `failed`, or `skipped` |
| `error` | error | the primary/first error that occurred, if the step failed for any reason |
| `additionalErrors` | array(error) | additional errors, if necessary |
| `start` | integer | starting timestamp of the step (milliseconds elapsed since the epoch) |
| `stop` | integer | ending timestamp of the step (milliseconds elapsed since the epoch) |

> NOTE: A failed step's `error` message will not be duplicated in the test
  `error`. The `test` error message is specifically for errors evaluating any
  test keys, such as an exception thrown in a test `env` during interpolation.

## Errors

`errors` is a JSON array of objects that contain the following keys:

| name | type | description |
| ---- | ---- | ----------- |
| `message` | string | a description of the error that occurred |

> NOTE: `errors` may contain additional information that is not specified.
  Any additional keys should be considered experimental.
