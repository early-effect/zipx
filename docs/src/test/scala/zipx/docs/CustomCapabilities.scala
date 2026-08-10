package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.workflow.{Expr, JobService, Step}
import zio.test.*

/** How to invent pipeline stages beyond the built-ins. */
object CustomCapabilities extends DocSpecSuite:

  def doc = page("Custom capabilities")(
    md"""
`zipxCapabilities` is append-able: any sbt task becomes a CI stage. Beyond the built-ins you mainly use
`Capability.once` / `Capability.steps` / `Capability.custom`, or the typed `zipxTasks` / `cmd` helpers from the plugin.
""",
    section("Once gates")(
      md"""
`Capability.once` emits a **single build-wide job** (not per module), e.g. format/lint that every test job waits on:

A capability's name becomes a `jobs.<job_id>` key, so it is a `CapabilityName` rather than a bare `String`: a literal is
checked where you write it, and naming the `val` once is what lets a dependent capability refer to it without repeating
the string.

```scala
val Fmt = CapabilityName("fmt")
zipxCapabilities += zipxTasks.once(Fmt, scalafmtCheckAll)
zipxCapabilities += Capability.test.copy(needsCapabilities = List(Fmt))
// or Layers: Capability.testLayers.copy(needsCapabilities = List(Fmt))
```
""",
      exampleValue {
        val fmt = CapabilityName("fmt")
        DocsRender.jobs("fmt", "test")(
          Capability.once(fmt, SbtCommand("scalafmtCheckAll")),
          Capability.test.copy(needsCapabilities = List(fmt)),
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("fmt:"),
          yaml.contains("scalafmtCheckAll"),
          yaml.contains("test:"),
          yaml.contains("- fmt"),
        )
      ),
    ),
    section("Action-only jobs")(
      md"""
When the job should run **GitHub Actions only** (no sbt), use `Capability.steps`. It is the same Once topology as
`Capability.once` (permissions, `needsCapabilities`, gate, condition), but skips JDK / sbt / cache setup and emits no
command step:

```scala
val Notify = CapabilityName("notify")
zipxCapabilities += Capability.steps(
  name = Notify,
  steps = _ => List(Step(name = Some("Ping"), run = Some("curl -X POST $$HOOK"))),
  needsCapabilities = List(Capability.PublishName),
  permissions = Map("contents" -> "read"),
)
```
""",
      exampleValue {
        val notify = CapabilityName("notify")
        DocsRender.jobs("notify")(
          Capability.steps(
            name = notify,
            steps = _ => List(Step(name = Some("Ping"), run = Some("echo hi"))),
            needsCapabilities = List(Capability.PublishName),
          ),
          Capability.publish,
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("notify:"),
          yaml.contains("Ping"),
          yaml.contains("- publish"),
          !yaml.contains("actions/setup-java"),
          !yaml.contains("sbt/setup-sbt"),
        )
      ),
    ),
    section("Custom stages (Graph by default)")(
      md"""
`Capability.custom` exposes all topology knobs and defaults to **Graph** so target fan-out matches multi-registry
examples. Same `name` as a built-in **replaces** it.

```scala
zipxCapabilities += Capability
  .custom(
    name = CapabilityName("docker"),
    command = cmd"$${Docker / publish}",
    participates = _.docker,
    phase = Phase.Publish,
    targets = _ => List(
      Target(TargetName("us"), env = Map("REGISTRY" -> EnvValue.plain("us.example"), "DEPLOY_ROLE" -> secret"US_ROLE")),
      Target(TargetName("eu"), env = Map("REGISTRY" -> EnvValue.plain("eu.example"), "DEPLOY_ROLE" -> secret"EU_ROLE")),
    ),
    permissions = Map("id-token" -> "write", "contents" -> "read"),
  )
  .copy(
    extraSteps = _ => List(
      Step
        .uses("aws-actions/configure-aws-credentials@v6")
        .named("Login")
        .withInput("role-to-assume", Expr.env("DEPLOY_ROLE"))
        .build
    )
  )
```
""",
      exampleValue {
        val docker = Capability
          .custom(
            name = Capability.DockerName,
            command = n => SbtCommand.module(n, SbtCommand("Docker/publish")),
            participates = _.docker,
            phase = Phase.Publish,
            targets = _ =>
              List(
                Target(
                  TargetName("us"),
                  env = Map("REGISTRY" -> EnvValue.plain("us.example"), "DEPLOY_ROLE" -> secret"US_ROLE"),
                ),
                Target(
                  TargetName("eu"),
                  env = Map("REGISTRY" -> EnvValue.plain("eu.example"), "DEPLOY_ROLE" -> secret"EU_ROLE"),
                ),
              ),
            permissions = Map("id-token" -> "write", "contents" -> "read"),
          )
          .copy(extraSteps =
            _ =>
              List(
                Step
                  .uses("aws-actions/configure-aws-credentials@v6")
                  .named("Login")
                  .withInput("role-to-assume", Expr.env("DEPLOY_ROLE"))
                  .build
              )
          )
        DocsRender.jobs("docker-service-us", "docker-service-eu")(docker)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("docker-service-us:"),
          yaml.contains("docker-service-eu:"),
          yaml.contains("DEPLOY_ROLE: ${{ secrets.US_ROLE }}"),
          yaml.contains("Login"),
        )
      ),
      md"""
Also override `runsOn = Some(List("self-hosted", "linux"))` and `permissions`, the same knobs built-ins use.
""",
    ),
    section("Sidecars and containers")(
      md"""
A capability that needs a database (or a Redis, or a Kafka) declares it as a **service**, which becomes GitHub's
`services:` on every job that capability produces:

```scala
zipxCapabilities += Capability.testGraph
  .withService("postgres", JobService("postgres:17", ports = List("5432:5432")))
```

The service id (`postgres`) is the hostname. Ports are `<host>:<container>`, so a step reaches the database at
`localhost:5432`. `withService` adds one and keeps the rest; `withServices` replaces the whole set. Both work on any
scope, so a `Capability.once` integration job and a per-module `Capability.testGraph` declare a sidecar the same way.

`inContainer` is the other half, `Job.container`: every step runs inside the image instead of on the runner.

```scala
zipxCapabilities += Capability.testGraph.inContainer("ghcr.io/acme/build-base:1")
```

Reach for it only when the **toolchain** is what has to differ. zipx already pins the JDK and sbt, and inside a container
the runner's own tooling is gone: `actions/setup-java` and `sbt/setup-sbt` install into the container, so an image
without `tar`, `curl` or `git` fails during setup rather than during your build. A sidecar plus the default runner covers
almost every case.
""",
      exampleValue {
        DocsRender.job("test-service")(
          Capability.testGraph
            .withService("postgres", JobService("postgres:17", ports = List("5432:5432")))
            .withService("redis", JobService("redis:7", ports = List("6379:6379")))
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("services:"),
          yaml.contains("image: postgres:17"),
          yaml.contains("""- "5432:5432""""),
          yaml.contains("image: redis:7"),
        )
      ),
      md"""
### There is no readiness signal

GitHub starts a service before the first step and then runs the steps. It does **not** wait for the service to be
*ready*, and the only lever it offers is a health check inside `options`:

```scala
JobService("postgres:17", ports = List("5432:5432"), options = Some("--health-cmd pg_isready --health-retries 5"))
```

Even with that, the contract is weak: the check is the image's own, the failure mode is a step that connects to a port
nothing is listening on yet, and nothing about it is expressible in your test code. If a suite needs a container to be
ready, it is usually better off **owning the container's lifecycle itself** with Testcontainers, which waits on a
strategy you choose and reports a startup failure as a test failure. zipx's own live remote-cache suite does exactly
that (see `BazelRemoteTestContainer` in `modules/it`, which waits on an HTTP `/status` 200 because a listening gRPC port
alone races), and it runs as a plain `Capability.once` over a separate sbt project:

```scala
lazy val it = project.in(file("modules/it")).dependsOn(core) // not aggregated: `sbt test` stays Docker-free

zipxCapabilities += Capability.once(
  name = CapabilityName("remote-cache-it"),
  command = SbtCommand("it/testFull"),
  phase = Phase.Verify,
  env = Map("ZIPX_IT_DOCKER" -> EnvValue.plain("1")),
)
```

The rule of thumb: a service the job just needs *present* (and can retry against) is a `withService`; a container a test
needs *ready*, or needs to inspect and restart, belongs to the test.

### What you cannot combine

`container` and `services` are refused on a capability that also sets `workflowCall`. A `uses:` job delegates its whole
runtime to the called workflow, so GitHub rejects both keys beside it, and there is nowhere for zipx to put them.
Generation fails naming the capability rather than dropping them, because a silently sidecar-less job fails later and
further from the cause. Declare them in the called workflow instead.

One more collision, decided rather than left to merge order: if a service id clashes with the remote-cache sidecar's
(`bazel-remote`, when `zipxCacheBackend` is the sidecar backend), **the cache sidecar wins**. A build cannot function
without it, since the sbt invocation is configured to reach it, whereas your own lost sidecar surfaces as a connection
error in the test that wanted it. Pick a different id.
""",
    ),
    section("Typed task keys (`zipxTasks`)")(
      md"""
An `SbtCommand` is what ultimately runs at the sbt shell: validated as text that cannot corrupt the generated file, but
not parsed as sbt syntax. For the common "one task" case, the plugin's `zipxTasks` constructors take a real `TaskKey` /
`InputKey` so renamed tasks fail at build load:

```scala
val promote = taskKey[Unit]("promote the image")
zipxCapabilities += zipxTasks.once(CapabilityName("fmt"), scalafmtCheckAll)
zipxCapabilities += zipxTasks.deploy(_.id == "service", promote, targets)
zipxCapabilities += zipxTasks.deployGraph(_.id == "service", promote, targets)
```

A key renders to `<module>/<label>`; config-scoped keys keep their axis (`Docker / publish` →
`<module>/Docker/publish`); a Once gate renders the bare label. `zipxTasks` mirrors `once` / `custom` / `deploy` /
`deployGraph`.
"""
    ),
    section("The `cmd` interpolator")(
      md"""
When you need shell *syntax* around a key (`+`, `++`, `;`), use the `cmd` interpolator: literals are verbatim; each
`$$` splice is a typed key or a `String` (anything else is a compile error):

```scala
command = cmd"+ $${testFull}"                        // -> +<module>/testFull
command = cmd"$${Docker / publish}"                  // -> <module>/Docker/publish
command = cmd"++$${scalaVersion.value}; $${publish}" // String + key
```

The interpolator produces the `command` function for `Capability.custom` / `.deploy` / `.once`. Key splices are
module-scoped.
"""
    ),
  )
end CustomCapabilities
