package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Verify-phase knobs shared by Aggregate, Layer, and Graph. */
object Verify extends DocSpecSuite:

  def doc = page("Verify")(
    md"""
Verify is the test/build phase. Aggregate defaults to a root Once job; Layer and Graph use per-module (or per-wave)
commands. Two settings configure the command for **all** modes.
""",
    section("Test task and optional clean")(
      md"""
```scala
zipxTestTask    := "testFull"            // Aggregate root; Graph/Layer per-module task
zipxVerifyClean := VerifyClean.CleanFull // None (default) | Clean | CleanFull
```
""",
      exampleValue {
        val cleanCfg = config.copy(verifyClean = VerifyClean.CleanFull)
        val agg      = Planner.plan(libGraph, List(Capability.test), cleanCfg)
        val graph    = Planner.plan(libGraph, List(Capability.testGraph), cleanCfg)
        (
          agg.jobs("test").steps.last.run,
          graph.jobs("test-schema").steps.last.run,
        )
      }.assert { case (aggRun, graphRun) =>
        assertTrue(
          aggRun.exists(_.contains("cleanFull; test")),
          graphRun.exists(_.contains("cleanFull; schema/test")),
        )
      },
    ),
    section("Affected-only PRs (Graph only)")(
      md"""
`zipxAffectedOnPR` (default `true`) emits an `affected` setup job only when a **Graph** Verify capability is present.
Aggregate and Layer always run their full stage command.
""",
      exampleValue {
        val on = config.copy(affected = AffectedMode.AffectedOnPR)
        (
          Planner.plan(libGraph, List(Capability.test), on).jobs.contains("affected"),
          Planner.plan(libGraph, List(Capability.testGraph), on).jobs.contains("affected"),
        )
      }.assert { case (aggHas, graphHas) =>
        assertTrue(!aggHas, graphHas)
      },
      md"""
Changed files → owning module (longest base-dir prefix) → reverse-dependency closure. A `.sbt` change or anything under
`project/` forces a full build. On push/tag everything builds unless `zipxAffectedOnPush` is enabled.
""",
    ),
    section("Skip Verify after merge / on tags")(
      md"""
By default (`zipxSkipMergedPrPush := true`), a push to `main` that lands a merged PR does **not** re-run Verify.
Direct pushes still Verify. **Tag pushes never run Verify** (release tags only need Publish / Deploy).
""",
      exampleValue {
        val wf = Planner.plan(libGraph, List(Capability.test), config.copy(skipMergedPrPush = true))
        (
          wf.jobs.contains("verify-gate"),
          wf.jobs("test").`if`.exists(_.contains("!startsWith(github.ref, 'refs/tags/')")),
        )
      }.assert { case (gate, skipTags) =>
        assertTrue(gate, skipTags)
      },
    ),
  )
end Verify
