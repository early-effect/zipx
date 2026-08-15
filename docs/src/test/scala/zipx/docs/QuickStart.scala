package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zio.test.*

/** Install and generate. */
object QuickStart extends DocSpecSuite:

  def doc = page("Quick start")(
    md"""
Three steps. You do not write GitHub Actions YAML.

1. Add the plugin.
2. Run `sbt zipxWorkflowGenerate` and commit the files it writes.
3. Open a pull request. GitHub runs the workflow.

Defaults: one test job on every PR and push, and a publish job when you push a version tag (`v*`). That is enough for
most libraries. A monorepo uses the same loop; you still do not list modules in YAML.

```mermaid
flowchart LR
  Plugin[addSbtPlugin sbt-zipx] --> Gen[zipxWorkflowGenerate]
  Gen --> Commit[commit ci.yml + actions]
  Commit --> PR[open PR]
  PR --> Check[zipxWorkflowCheck]
  Check --> Green([honest CI])
  class Plugin,Gen,Commit,PR,Check,Green happy
```
""",
    section("Install")(
      md"""
```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "<version>")
```

Generate and commit:

```
sbt zipxWorkflowGenerate
git add .github/workflows/ci.yml .github/actions/
git commit -m "ci: generate with zipx"
```

If you enable `zipxDependabotSync` later, also commit `.github/workflows/zipx-action-pins-sync.yml` when it appears.
You do not need that on day one.

`sbt zipxGraph` and `sbt zipxPublishOrder` print what zipx saw, if you want to inspect.
"""
    ),
    section("Defaults")(
      md"""
Defaults are **Aggregate**: one root test job and one publish job (plus docker when any module enables
`DockerPlugin`). You write no module lists and no job-order YAML. For a typical library that is the whole CI.

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "<version>")

// build.sbt
lazy val lib = project.settings(/* publish settings */)

lazy val root = (project in file("."))
  .aggregate(lib)
  .settings(
    // nothing required for Aggregate test + publish
    // optional paved Central path:
    zipxCapabilities += ZipxCentral.release,
    zipxJavaVersion  := JdkVersion("25"),
  )
```
""",
      exampleValue {
        val g = GraphFixture(List(ModuleNode(ModuleId("lib"), publishes = true, crossScalaVersions = List("3.8.4"))))
        DocsRender.jobs("test", "publish")(Capability.test, Capability.publish)(using g)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("test:"),
          yaml.contains("publish:"),
          yaml.contains("run: sbt 'test'"),
          yaml.contains("refs/tags/v"),
        )
      ),
    ),
    section("Bare settings (sbt 2.0)")(
      md"""
zipx reads these settings from the **root** project, so write them without a `ThisBuild /` prefix. That is an sbt 2
habit, not a zipx quirk.

A bare `zipxTestTask := zipxTasks.of(testFull)` is the plugin default. On sbt 2, plain `sbt test` can skip suites; CI
uses `testFull` so every suite actually runs. Any module can override the task in its own `.settings(...)`.

```scala
zipxJavaVersion := JavaVersion("25")
zipxTestTask    := zipxTasks.of(testFull)  // plugin default
zipxWorkflowDispatch := true
```
"""
    ),
    section("Self-checking")(
      md"""
`zipxWorkflowGenerate` writes `.github/workflows/ci.yml` and `.github/actions/zipx-*/action.yml`. Commit them.
`zipxWorkflowCheck` regenerates and diffs against those committed files. Run the check in CI (zipx already puts it in
the workflow) so a forgotten regenerate fails the PR. Generation is deterministic: same build, same YAML.
"""
    ),
    section("Action pins (optional, skip at first)")(
      md"""
zipx already pins GitHub Actions to exact commits in the generated workflow. Jar defaults are fine. Come back to
**Action pins** when you want to bump those Actions without waiting for a zipx release.

CDN / checksum pins are **Pin feeds**. Library and plugin versions are the catalog; bump locally with `zipxDepUpdate`
and open a PR. See **Dependency updates**.
"""
    ),
  )
end QuickStart
