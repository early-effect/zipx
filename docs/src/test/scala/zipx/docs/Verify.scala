package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.workflow.Step
import zio.test.*

/** Verify-phase knobs shared by Aggregate, Layer, and Graph. */
object Verify extends DocSpecSuite:

  def doc = page("Verify")(
    md"""
Verify is the test/build phase. Aggregate defaults to a root Once job; Layer and Graph use per-module (or per-wave)
commands. Two settings configure the command for **all** modes. Path-based job skipping lives on the **Affected**
page (Graph only, fail-open handoff, concurrency).
""",
    section("Test task and optional clean")(
      md"""
```scala
zipxTestTask    := "testFull"            // Aggregate root; Graph/Layer per-module task
zipxVerifyClean := VerifyClean.CleanFull // None (default) | Clean | CleanFull
```

For a **one-off** LocalDir / action-cache bust without making clean permanent, leave `zipxVerifyClean` at
`None` (default) and add the GitHub PR label **`clean`**. Verify then runs `cleanFull; <task>` only on that PR.
Override the label name with `zipxVerifyCleanLabel`, or set it to `None` to disable.

```scala
zipxVerifyCleanLabel := Some("clean")  // default
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
      exampleValue {
        given PlanConfig = config.copy(verifyCleanLabel = Some("clean"))
        DocsRender.jobs("test")(Capability.test)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("ZIPX_VERIFY_CLEAN_FULL"),
          yaml.contains("labels.*.name"),
          yaml.contains("cleanFull; test"),
        )
      ),
    ),
    section("Affected-only PRs (Graph only)")(
      md"""
`zipxAffectedOnPR` (default `true`) emits an `affected` setup job only when a **Graph** Verify capability is present.
Aggregate and Layer always invoke their full stage command (they do not skip GitHub jobs). That is not the same as
"always recompile and retest everything": sbt 2's incremental `test` and cross-run task cache (restored by zipx at the
epoch, or via remote cache) still skip unaffected work, even on a cold JVM. See **Execution modes** ("Two kinds of
affected") and the **Affected** page for the fail-open handoff and who is gated today.

```scala
zipxAffectedOnPR := true   // default with Graph Verify
```

```mermaid
flowchart TD
  A1[Aggregate or Layer · always start job] --> A2([sbt cache skips work · inside the JVM])
  A2 -.->|or Graph| G1[Graph Verify · affected setup]
  G1 --> G2([skip whole module jobs · reverse-dep untouched])
  class A1,A2 happy
  class G1,G2 warn
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
`project/` forces a full build. On push/tag everything builds unless `zipxAffectedOnPush` is enabled. If the diff
**cannot run**, zipx emits `["all"]` (fail open) so a bad base ref never reports a green, untested PR.
""",
    ),
    section("Skip Verify after merge / on tags")(
      md"""
By default (`zipxSkipMergedPrPush := true`), a push to `main` that lands a merged PR does **not** re-run Verify.
Direct pushes still Verify. **Tag pushes never run Verify** (release tags only need Publish / Deploy).

With **LocalDir**, that skip would otherwise leave `main` without an `actions/cache` save (PR caches are
branch-scoped; later PRs only warm from the default branch). So by default zipx also emits a minimal
`cache-rehydrate` job that runs **only** when verify-gate skips Verify: same checkout / JDK / LocalDir cache
path, then `compile` (override with `zipxCacheRehydrateTask`). No full test, no `verifyClean`. Set
`zipxCacheRehydrateOnMerge := false` to opt out; remote backends never emit it.

To also warm **non-sbt** blobs that live under `target/` (e.g. Playwright browsers), opt into rehydrate-only
hooks. They are **not** copied from Verify capability `extraSteps` / `env`; assign the same function/map when you
want parity:

```scala
zipxSkipMergedPrPush := true  // default
zipxCacheRehydrateOnMerge := true  // default; LocalDir only
zipxCacheRehydrateTask := "compile"  // default
zipxCacheRehydrateExtraSteps := browserSetup  // after cache restore, before compile
zipxCacheRehydrateEnv := Map(
  "PLAYWRIGHT_BROWSERS_PATH" -> EnvValue.expr("$${{ github.workspace }}/target/ms-playwright"),
)
```
""",
      exampleValue {
        given PlanConfig = config.copy(skipMergedPrPush = true)
        DocsRender.jobs("verify-gate", "cache-rehydrate", "test")(Capability.test)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("verify-gate:"),
          yaml.contains("cache-rehydrate:"),
          yaml.contains("needs.verify-gate.outputs.run == 'false'"),
          yaml.contains("!startsWith(github.ref, 'refs/tags/')"),
        )
      ),
      exampleValue {
        given PlanConfig = config.copy(
          skipMergedPrPush = true,
          cacheRehydrateExtraSteps = _ => List(Step(name = Some("Install browsers"), run = Some("npm ci"))),
          cacheRehydrateEnv = Map(
            "PLAYWRIGHT_BROWSERS_PATH" -> EnvValue.expr("${{ github.workspace }}/target/ms-playwright")
          ),
        )
        DocsRender.jobs("cache-rehydrate")(Capability.test)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("Install browsers"),
          yaml.contains("PLAYWRIGHT_BROWSERS_PATH"),
          yaml.contains("npm ci"),
          yaml.contains("sbt 'compile'"),
        )
      ),
    ),
  )
end Verify
