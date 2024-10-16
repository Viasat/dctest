# Results File Schema

## Overview

dctest can optionally write a summary of results as JSON data to a file (see
the `--results-file` CLI option). This JSON data contains the followin top-level
keys, which are further described below:

* `summary` - overall outcome, runtime, and test outcome counts
* `tests` - all tests executed as part of dctest run
* `errors` - a list of errors encountered during execution

## Summary

* `summary.outcome` -
* `summary.passed` -
* `summary.failed` -
* `summary.start` -
* `summary.stop` -

## Tests

* `tests[].id` -
* `tests[].name` -
* `tests[].outcome` -
* `tests[].error` -
* `tests[].start` -
* `tests[].stop` -
* `tests[].steps` -

For steps:

* `tests[].steps[].name` -
* `tests[].steps[].outcome` -
* `tests[].steps[].error` -
* `tests[].steps[].start` -
* `tests[].steps[].stop` -


## Errors

> NOTE: `errors` may contain additional information that is not specified.
  Any additional keys should be considered experimental.

* `errors[].message` -
