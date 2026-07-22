package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.{Capability, ModuleGraph, ModuleNode, Planner}
import zio.test.*

/** Install and generate. */
object QuickStart extends DocSpecSuite:

  def doc = page("Quick start")(
    md"""
Add the plugin, generate the workflow, and commit it. zipx also checks the committed YAML in CI so a build change that
isn't reflected in the workflow fails the PR.
""",
    section("Install")(
      md"""
```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "<version>")
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
""",
      exampleValue {
        val g  = ModuleGraph(List(ModuleNode("lib", publishes = true, crossScalaVersions = List("3.8.4"))))
        val wf = Planner.plan(g, List(Capability.test, Capability.publish), DocsFixtures.config)
        (wf.jobs.keySet.toList.sorted, wf.jobs("test").steps.last.run)
      }.assert { case (ids, run) =>
        assertTrue(ids == List("publish", "test"), run.exists(_.contains("'test'")))
      },
    ),
    section("Bare settings (sbt 2.0)")(
      md"""
zipx reads build-level settings from the root project's scope, so write plain bare settings — no `ThisBuild /` prefix.
A bare `zipxTestTask := "testFull"` applies to every module; any module can override it in its own `.settings(...)`.
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

Staying on jar defaults needs no pin file — just upgrade `zipx-sbt` when pins move.
"""
    ),
  )
end QuickStart
