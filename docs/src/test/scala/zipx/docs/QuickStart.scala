package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zio.test.*

/** Install and generate. */
object QuickStart extends DocSpecSuite:

  def doc = page("Quick start")(
    md"""
Describe CI in the build, then generate it. Add the plugin, write `ci.yml` with `zipxWorkflowGenerate`, and let
`zipxWorkflowCheck` fail the PR when the committed workflow no longer matches the graph. Works for a single Aggregate
library as well as a monorepo; no hand-rolled module matrices required.
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
git add .github/workflows/ci.yml && git commit -m "ci: generate with zipx"
```

If you enable `zipxDependabotSync`, also commit `.github/workflows/zipx-action-pins-sync.yml` when it appears.

Inspect what zipx sees: `sbt zipxGraph` and `sbt zipxPublishOrder`.
"""
    ),
    section("Defaults")(
      md"""
Defaults are **Aggregate**: one root `test` job (`sbt 'test'`) and one publish job (plus docker when any module enables
`DockerPlugin`). For a typical library you write zero module lists, `needs` edges, or project-id strings.

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
    zipxJavaVersion  := "25",
  )
```
""",
      exampleValue {
        val g = ModuleGraph(List(ModuleNode("lib", publishes = true, crossScalaVersions = List("3.8.4"))))
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
zipx reads build-level settings from the root project's scope, so write plain bare settings — no `ThisBuild /` prefix.
A bare `zipxTestTask := "testFull"` applies to every module; any module can override it in its own `.settings(...)`.

```scala
zipxJavaVersion := "25"
zipxTestTask    := "testFull"
zipxWorkflowDispatch := true
```
"""
    ),
    section("Self-checking")(
      md"""
`zipxWorkflowGenerate` writes `.github/workflows/ci.yml`. `zipxWorkflowCheck` regenerates and diffs against the
committed file. Run the check in CI so drift fails the PR. Generation is deterministic (stable ordering, no timestamps).
"""
    ),
    section("Action pins (optional)")(
      md"""
Workflows pin GitHub Actions to commit SHAs. To track upstream action releases without waiting on a zipx upgrade:

1. Commit `.github/zipx/action-pins.yml` (see **Action pins** for the format)
2. Add Dependabot for `package-ecosystem: github-actions`
3. On Dependabot PRs run `sbt zipxActionsPull`, or set `zipxDependabotSync := true` for hands-off sync

Staying on jar defaults needs no pin file — just upgrade `sbt-zipx` when pins move.
"""
    ),
  )
end QuickStart
