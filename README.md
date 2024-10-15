# dctest

Integration Testing in Docker Compose

[![npm](https://img.shields.io/npm/v/@lonocloud/dctest.svg)](https://www.npmjs.com/package/@lonocloud/dctest)
[![docker](https://img.shields.io/docker/v/lonocloud/dctest.svg)](https://hub.docker.com/r/lonocloud/dctest)

## Quickstart

The [intro examples][1] demonstrate the basic syntax and features of dctest.
They can be run with a fresh checkout, installed dependencies, and the sample
[Docker Compose][2] file:

```bash
git checkout git@github.com:Viasat/dctest.git
npm install
docker-compose -p demo -f examples/docker-compose.yaml up -d
```

With our Docker Compose project running, we can run our tests against it,
supplying the project name ("demo") and the tests we want to run:

```bash
./dctest demo examples/00-intro.yaml
```

See `dctest --help` for more CLI options, such as `--continue-on-error`.

## Install and Run

Via NPM as a dev dependency:

```bash
npm install --save-dev @lonocloud/dctest@0.3.1
./node_modules/.bin/dctest $USER /path/to/suite.yaml
```

Via Docker Hub:

```bash
docker pull lonocloud/dctest:0.3.1
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /path/to/suite.yaml:/tests/suite.yaml \
  lonocloud/dctest:0.3.1 $USER /tests/suite.yaml
```

## Copyright & License

This software is copyright Viasat, Inc and is released under the terms
of the Eclipse Public License version 2.0 (EPL.20). A copy of the
license is located at in the LICENSE file at the top of the
repository.

[1]: https://github.com/Viasat/dctest/blob/main/examples/00-intro.yaml
[2]: https://github.com/Viasat/dctest/blob/main/examples/docker-compose.yaml
