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
        given PlanConfig = config.copy(verifyClean = VerifyClean.CleanFull)
        DocsRender.jobs("test")(Capability.test) + "\n---\n" +
          DocsRender.job("test-schema")(Capability.testGraph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("cleanFull; test"),
          yaml.contains("cleanFull; schema/test"),
        )
      ),
    ),
    section("Affected-only PRs (Graph only)")(
      md"""
`zipxAffectedOnPR` (default `true`) emits an `affected` setup job only when a **Graph** Verify capability is present.
Aggregate and Layer always invoke their full stage command (they do not skip GitHub jobs). That is not the same as
"always recompile and retest everything": sbt 2's incremental `test` and cross-run task cache (restored by zipx at the
epoch, or via remote cache) still skip unaffected work, even on a cold JVM. See **Execution modes** ("Two kinds of
affected").

```scala
zipxAffectedOnPR := true   // default with Graph Verify
```
""",
      exampleValue {
        given PlanConfig = config.copy(affected = AffectedMode.AffectedOnPR)
        DocsRender.body(Capability.test) + "\n---\n" + DocsRender.body(Capability.testGraph)
      }.assert(yaml =>
        assertTrue(
          !yaml.split("---")(0).contains("affected:"),
          yaml.split("---")(1).contains("affected:"),
        )
      ),
      md"""
Changed files → owning module (longest base-dir prefix) → reverse-dependency closure. A `.sbt` change or anything under
`project/` forces a full build. On push/tag everything builds unless `zipxAffectedOnPush` is enabled.
""",
    ),
    section("Skip Verify after merge / on tags")(
      md"""
By default (`zipxSkipMergedPrPush := true`), a push to `main` that lands a merged PR does **not** re-run Verify.
Direct pushes still Verify. **Tag pushes never run Verify** (release tags only need Publish / Deploy).

```scala
zipxSkipMergedPrPush := true  // default
```
""",
      exampleValue {
        given PlanConfig = config.copy(skipMergedPrPush = true)
        DocsRender.jobs("verify-gate", "test")(Capability.test)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("verify-gate:"),
          yaml.contains("!startsWith(github.ref, 'refs/tags/')"),
        )
      ),
    ),
  )
end Verify
