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
        ScalaStewardWorkflow.render(ActionPins.Defaults, "ubuntu-latest")
      }.assert(yaml =>
        assertTrue(
          yaml.contains("cron: 0 0 * * 0") || yaml.contains("""cron: "0 0 * * 0""""),
          yaml.contains("scala-steward-org/scala-steward-action@"),
          yaml.contains("workflow_dispatch"),
        )
      ),
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
        List(
          s"weekly: ${Cron.weekly(DayOfWeek.Monday, hour = 6, minute = 30).render}",
          s"daily:  ${Cron.daily(hour = 3).render}",
          s"raw:    ${Cron.raw("0 */6 * * *").render}",
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("weekly: 30 6 * * 1"),
          text.contains("daily:  0 3 * * *"),
          text.contains("raw:    0 */6 * * *"),
        )
      ),
    ),
  )
end DependencyUpdates
