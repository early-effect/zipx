package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.shell.{Exec, Script, Word}
import zipx.workflow.{Expr, Step}
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
        given PlanConfig = config.copy(verifyCleanLabel = PlanConfig.verifyCleanLabel("clean"))
        DocsRender.jobs("test")(Capability.test)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("ZIPX_VERIFY_CLEAN_FULL"),
          yaml.contains("labels.*.name"),
          yaml.contains("cleanFull; test"),
        )
      ),
    ),
    section("Coverage (and why `test` is the wrong task here)")(
      md"""
On sbt 2.0 plain **`test` is `testQuick`**. It skips tests it deems unaffected and prints `No tests to run`, which reads
like success. That is usually what you want in a Verify job, where sbt's incrementality is the point. Under **coverage
it is a silent wrong answer**: the hand-rolled alias everyone writes,

```scala
addCommandAlias("testWithCoverage", "cleanFull; coverage; compile; test; coverageAggregate; coverageReport; coverageOff")
```

measures whichever tests sbt happened to run, satisfies `coverageMinimum` on near-zero data, and goes green. So zipx
builds the command instead of taking one:

```scala
zipxCapabilities += Coverage.once()   // coverage; testFull; coverageAggregate  (one session)
zipxCapabilities += Coverage.graph()  // one job per module, each measuring that module's own zipxTestTask
```

`Coverage.graph()` reads each module's `zipxTestTask` and substitutes `testFull` where it is still the default `test`.
A module that set the task itself is left alone, because an explicit choice outranks zipx's. Pass `_.testTask` for
literal inheritance, default and all:

```scala
zipxCapabilities += Coverage.graph(task = _.testTask)
```

Two things the alias has that the capability deliberately does not. No trailing **`coverageOff`**: the sbt session ends
with the job, and that command exists because a developer's shell outlives the command. And no **`cleanFull`**: use
`zipxVerifyClean` or the `clean` PR label above if you want one, so the choice is in one place.

`coverage` is a session-wide toggle, which is why enable / test / report are one `sbt '…; …; …'` invocation rather than
three steps.

Prefer **`once`**: `coverageAggregate` is a root task over every module's measurement data, so splitting it across jobs
means downloading and merging artifacts to get one number back. `graph` buys affected-gating (it is an ordinary Graph
Verify capability, so everything on the **Affected** page applies) at the cost of per-module minimums instead of a
build-wide one.

The report is uploaded with the already-pinned `actions/upload-artifact`, per module under `graph` so N jobs do not
collide on one artifact name. `if-no-files-found: error`, on purpose: a run that measured nothing produces no report,
and that should be a red job rather than an empty upload. Turn it off with `uploadReport = false`.
""",
      exampleValue {
        DocsRender.jobs("coverage")(Coverage.once())
      }.assert(yaml =>
        assertTrue(
          yaml.contains("sbt 'coverage; testFull; coverageAggregate'"),
          yaml.contains("upload-artifact"),
          yaml.contains("if-no-files-found: error"),
          !yaml.contains("coverageOff"),
        )
      ),
      exampleValue {
        DocsRender.job("coverage-schema")(Coverage.graph())
      }.assert(yaml =>
        assertTrue(
          // `schema` never set zipxTestTask, so this is the substitution doing its job.
          yaml.contains("sbt 'coverage; schema/testFull; schema/coverageReport'"),
          yaml.contains("coverage-report-schema"),
        )
      ),
    ),
    section("Affected-only PRs (Graph only)")(
      md"""
`zipxAffectedOnPR` (default `true`) emits an `affected` setup job only when a **Graph** Verify capability is present.
Aggregate and Layer always invoke their full stage command (they do not skip GitHub jobs). That is not the same as
"always recompile and retest everything": sbt 2's incremental `test` and cross-run task cache (restored by zipx at the
epoch, or via remote cache) still skip unaffected work, even on a cold JVM. See **Execution modes** ("Two kinds of
affected") and the **Affected** page for the fail-open handoff, who is gated, and `zipxAffectedPublish`, which extends
the same narrowing to Graph Publish jobs as a separate opt-in.

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
`extraSteps`. Prefer build-wide **`zipxEnv`** for vars needed on Verify **and** rehydrate; use
`zipxCacheRehydrateEnv` only for merge-only overlays. Neither is copied from Verify capability `extraSteps` /
`env`; assign the same setup function when you want step parity:

```scala
val browserSetup = Steps.built("browsers")(
  Step.run(Script(Exec("npm", Word.lit("ci")))).named("Install browsers")
)

zipxSkipMergedPrPush := true  // default
zipxCacheRehydrateOnMerge := true  // default; LocalDir only
zipxCacheRehydrateTask := "compile"  // default
zipxEnv := Map(
  "PLAYWRIGHT_BROWSERS_PATH" -> EnvValue.typed(Expr.github("workspace") ++ Expr.lit("/target/ms-playwright")),
)
zipxCacheRehydrateExtraSteps := browserSetup  // after cache restore, before compile
```

`EnvValue.typed` takes any `Expr`, so `++` concatenates a context reference with literal text instead of spelling the
`$${{ … }}` out. `EnvValue.expr` still accepts a raw string, and `zipxWorkflowGenerate` warns when one is used.

`browserSetup` is a **`Steps` bundle**, not a lambda: it carries a name, composes with `++`, gates with `.when(...)`,
and reports escape-hatch use so `zipxWorkflowGenerate` can warn and name it. The step body is a typed `Script`, so
nothing here is a hand-written shell string. See **Shell and steps** for the whole DSL.
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
          env = Map(
            "PLAYWRIGHT_BROWSERS_PATH" ->
              EnvValue.typed(Expr.github("workspace") ++ Expr.lit("/target/ms-playwright"))
          ),
          cacheRehydrateExtraSteps = Steps.built("browsers")(
            Step.run(Script(Exec("npm", Word.lit("ci")))).named("Install browsers")
          ),
        )
        DocsRender.jobs("cache-rehydrate", "test")(Capability.test)
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
