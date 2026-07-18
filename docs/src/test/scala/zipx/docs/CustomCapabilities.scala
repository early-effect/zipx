package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.docs.DocsFixtures.*
import zipx.workflow.Step
import zio.test.*

import scala.collection.immutable.ListMap

/** How to invent pipeline stages beyond the built-ins. */
object CustomCapabilities extends DocSpecSuite:

  def doc = page("Custom capabilities")(
    md"""
`zipxCapabilities` is append-able — any sbt task becomes a CI stage. Beyond the built-ins you mainly use
`Capability.once` and `Capability.custom`.
""",
    section("Once gates")(
      md"""
`Capability.once` emits a **single build-wide job** (not per module), e.g. format/lint that every test job waits on:
""",
      exampleValue {
        val fmt  = Capability.once("fmt", "scalafmtCheckAll")
        val test = Capability.test.copy(needsCapabilities = List("fmt"))
        val wf   = Planner.plan(libGraph, List(fmt, test), config)
        (wf.jobs.contains("fmt"), wf.jobs("test").needs)
      }.assert { case (hasFmt, needs) =>
        assertTrue(hasFmt, needs.contains("fmt"))
      },
      md"""
```scala
zipxCapabilities += Capability.once("fmt", "scalafmtCheckAll")
zipxCapabilities += Capability.test.copy(needsCapabilities = List("fmt"))
// or: Capability.testGraph.copy(needsCapabilities = List("fmt"))
```
""",
    ),
    section("Custom stages (Graph by default)")(
      md"""
`Capability.custom` exposes all topology knobs and defaults to **Graph** so target fan-out matches multi-registry
examples. Same `name` as a built-in **replaces** it.
""",
      exampleValue {
        val docker = Capability
          .custom(
            name = "docker",
            command = n => s"${n.id}/Docker/publish",
            participates = _.docker,
            phase = Phase.Publish,
            targets = _ =>
              List(
                Target("us", env = Map("REGISTRY" -> EnvValue.plain("us.example"), "DEPLOY_ROLE" -> secret"US_ROLE")),
                Target("eu", env = Map("REGISTRY" -> EnvValue.plain("eu.example"), "DEPLOY_ROLE" -> secret"EU_ROLE")),
              ),
            permissions = Map("id-token" -> "write", "contents" -> "read"),
          )
          .copy(extraSteps =
            _ =>
              List(
                Step(
                  name = Some("Login"),
                  uses = Some("aws-actions/configure-aws-credentials@v6"),
                  `with` = ListMap("role-to-assume" -> "${{ env.DEPLOY_ROLE }}"),
                )
              )
          )
        val wf = Planner.plan(libGraph, List(docker), config)
        (
          wf.jobs.keys.filter(_.startsWith("docker-")).toList.sorted,
          wf.jobs("docker-service-us").env.get("DEPLOY_ROLE"),
          wf.jobs("docker-service-us").steps.exists(_.name.contains("Login")),
        )
      }.assert { case (ids, role, hasLogin) =>
        assertTrue(
          ids == List("docker-service-eu", "docker-service-us"),
          role.contains("${{ secrets.US_ROLE }}"),
          hasLogin,
        )
      },
      md"""
Also override `runsOn = Some(List("self-hosted", "linux"))` and `permissions` — the same knobs built-ins use.
""",
    ),
    section("Typed task keys (`zipxTasks`)")(
      md"""
String commands are what ultimately run at the sbt shell. For the common "one task" case, the plugin's `zipxTasks`
constructors take a real `TaskKey` / `InputKey` so renamed tasks fail at build load:

```scala
val promote = taskKey[Unit]("promote the image")
zipxCapabilities += zipxTasks.once("fmt", scalafmtCheckAll)
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
