# Writing Tests

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
the environment, similar to the shell substitution `echo "Howdy ${SALUTATION}"`,
but interpolation happens _before_ the command is executed in the location. Many
aspects of suites, tests, and steps are interpolatable and noted as such in
the reference below.

The second step defines a more elaborate command, taking advantage of YAML
multiline strings. This is a common pattern for clarity (and sometimes
necessary for valid YAML, if using interpolations and expressions). This step
also adds an additional success condition via `expect`, which does not need the
`${{ ... }}` syntax. This example condition checks that the command's standard
output contained the word "multiline".

For more information, see...

* the [runnable examples](https://github.com/Viasat/dctest/tree/main/examples) on GitHub
* all available suite, test, and step keys in the [input reference](/reference/latest/input)
* everything that's possible in the [expressions reference][1]

[1]: /reference/latest/expressions
