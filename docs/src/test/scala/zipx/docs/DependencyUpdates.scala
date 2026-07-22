package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.workflow.{Cron, DayOfWeek}
import zio.test.*

/** Dependabot (Actions) and Scala Steward (sbt/Scala deps). */
object DependencyUpdates extends DocSpecSuite:

  def doc = page("Dependency updates")(
    md"""
zipx splits dependency automation in two:

- **Dependabot** (`github-actions`) — bumps SHA-pinned GitHub Actions in generated workflows; sync back via the pin
  file (`zipxDependabotSync` / `zipxActionsPull`). See **Action pins**.
- **Scala Steward** — bumps sbt / Scala / library dependencies. Opt in with `zipxScalaSteward := true`.
""",
    section("Scala Steward (opt-in)")(
      md"""
```scala
zipxScalaSteward := true
```

Then `zipxWorkflowGenerate` also writes `.github/workflows/zipx-scala-steward.yml`: weekly cron (Sunday 00:00 UTC)
plus `workflow_dispatch`, using the SHA-pinned `scala-steward-org/scala-steward-action` and the default GitHub
Actions token (no `APP_*` secrets).

**Required repo/org setting:** [Allow GitHub Actions to create and approve pull requests](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository#preventing-github-actions-from-creating-or-approving-pull-requests).

zipx dogfoods this (`zipxScalaSteward := true` in the root build).

An alternative with no workflow is installing the public [Scala Steward GitHub App](https://github.com/apps/scala-steward)
on the org/repo. zipx’s opt-in is the self-hosted Action path so pins and schedule stay in the build.
""",
      exampleValue {
        val out = ScalaStewardWorkflow.render(ActionPins.Defaults, "ubuntu-latest")
        (
          Cron.weekly(DayOfWeek.Sunday).render,
          out.contains("scala-steward-org/scala-steward-action@"),
          out.contains("workflow_dispatch"),
        )
      }.assert { case (cron, hasAction, hasDispatch) =>
        assertTrue(cron == "0 0 * * 0", hasAction, hasDispatch)
      },
    ),
    section("Typed cron")(
      md"""
Schedules use a typed [[zipx.workflow.Cron]] AST (not raw strings):

```scala
Cron.weekly(DayOfWeek.Sunday)           // 0 0 * * 0
Cron.weekly(DayOfWeek.Monday, hour = 6) // 0 6 * * 1
Cron.daily(hour = 3, minute = 15)       // 15 3 * * *
Cron.hourly(minute = 45)                // 45 * * * *
Cron.raw("0 */6 * * *")                 // escape hatch
```

`Cron` / `DayOfWeek` are re-exported from the plugin `autoImport`.
""",
      exampleValue {
        (
          Cron.weekly(DayOfWeek.Monday, hour = 6, minute = 30).render,
          Cron.daily(hour = 3).render,
          Cron.raw("0 */6 * * *").render,
        )
      }.assert { case (weekly, daily, raw) =>
        assertTrue(weekly == "30 6 * * 1", daily == "0 3 * * *", raw == "0 */6 * * *")
      },
    ),
  )
end DependencyUpdates
