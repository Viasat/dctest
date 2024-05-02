# dctest

Integration Testing in Docker Compose

## Usage

```
npm install
docker-compose -f examples/docker-compose.yaml up -d
./dctest examples examples/00-intro.yaml
./dctest --continue-on-error examples examples/00-intro.yaml
docker-compose -f examples/docker-compose.yaml down
```

## Copyright & License

This software is copyright Viasat, Inc and is released under the terms
of the Eclipse Public License version 2.0 (EPL.20). A copy of the
license is located at in the LICENSE file at the top of the
repository.
