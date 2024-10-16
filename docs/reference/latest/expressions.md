# Expressions

## Basics

types and operators

## Contexts

### env

* `env.COMPOSE_PROJECT_NAME` -

### process

* `process.platform` -
* `process.pid` -
* `process.ppid` -
* `process.argv` -
* `process.versions` -
* `process.features` -
* `process.env` -

### step

* `step.stdout` -
* `step.stderr` -

## Functions and Methods

### Status

* `always()` -
* `success()` -
* `failure()` -

### Conversions

* `toJSON(value)` -
* `fromJSON(value)` -
* `value.toString()` - ex: `[].toString()` would return the string "[]"

### Collections

* `coll.count()` - ex: `[1, 2, 3].count()` would return 3

### String

* `contains(txt, s)` -
* `startsWith(txt, s)` -
* `endsWith(txt, s)` -

### Error

* `throw(msg)` -
