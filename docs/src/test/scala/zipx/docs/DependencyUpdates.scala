package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zipx.workflow.{Cron, DayOfWeek}
import zio.test.*

/** Catalog bumps: scheduled PR from zipx-version-updates.yml, or the same apply locally. ZipxVersions is required. */
object DependencyUpdates extends DocSpecSuite:

  def doc = page("Dependency updates")(
    md"""
Libraries, plugins, and GitHub Actions go stale. zipx replaces Scala Steward and a `github-actions` Dependabot
ecosystem: typed constructors in `project/ZipxVersions.scala`, a scheduled companion that rewrites those constructors
and opens a PR.

**ZipxVersions is required.** `zipxCheckDeps` fails undeclared GAVs. Leftover `zipx-scala-steward.yml` fails generate
(`zipxLeftoverSteward`). Do not install a search-replace bot; it cannot move version with sha256 / purl / git SHA.

Four catalog kinds:

```mermaid
flowchart LR
  Act[GitHub Actions] --> ActU[zipxActionUpdate]
  Scala[sbt and Maven libraries] --> Cat[zipxDepUpdate]
  Plug[sbt plugins] --> Cat
  Other[CDN checksums, tarballs, vendor files] --> Pf[zipxPinUpdate]
```

- **GitHub Actions.** `Action("owner/repo", "vX.Y.Z", sha = "…")`. `zipxActionUpdate` talks to the GitHub API, peels a
  tag to a 40-hex SHA, and queries OSV. See **Action pins**.
- **Scala libraries and sbt plugins.** `Lib` / `Plugin` vals. `zipxDepUpdate`. See **Versions**.
- **Pins that are not Maven and not Actions.** `Pin` vals. A pin feed is lookup and policy only. `zipxPinUpdate`, or
  CI opens `zipx/pin-updates` if you opt a feed into `Update`. See **Pin feeds**.
""",
    section("Scheduled PR")(
      md"""
Default `zipxVersionUpdates := true` writes `.github/workflows/zipx-version-updates.yml` (schedule plus
`workflow_dispatch`). The default schedule is Sunday 00:00 UTC; set `zipxVersionUpdatesSchedule` to change it
(`Cron.daily`, `Cron.weekly`, `Cron.raw`). The job runs `zipxDepUpdate yes`, `zipxActionUpdate yes`,
`zipxPinUpdate yes`, then `zipxWorkflowGenerate`, and `gh pr create`s `zipx/version-updates` as
`github-actions[bot]`. That PR is every ZipxVersions row kind: Lib / Plugin / Action / Pin constructors, plus
`plugins.sbt` when a plugin moved.

`zipxVersionUpdates := false` deletes the companion.

**Required repo/org setting:** [Allow GitHub Actions to create and
approve pull requests](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository#preventing-github-actions-from-creating-or-approving-pull-requests).
`GITHUB_TOKEN` is enough, the same as Scala Steward. It cannot create or update `.github/workflows/` files, so the
companion restores that directory before commit. Java and sbt pins live in `zipx-sbt-setup`. Generate the companion
workflow once from a clone; the bot then leaves it alone.
""",
      exampleValue {
        VersionUpdatesWorkflow.render(ActionPins.Defaults, "21", "ubuntu-latest").yaml
      }.assert(yaml =>
        assertTrue(
          yaml.contains("workflow_dispatch"),
          yaml.contains("zipxDepUpdate yes"),
          yaml.contains("zipxActionUpdate yes"),
          yaml.contains("zipxPinUpdate yes"),
          yaml.contains("zipxWorkflowGenerate"),
          yaml.contains("zipx/version-updates") || yaml.contains("gh pr create"),
          yaml.contains("contents: write") || yaml.contains("contents:write"),
          yaml.contains("pull-requests: write") || yaml.contains("pull-requests:write"),
          yaml.contains("./.github/actions/zipx-sbt-setup"),
          yaml.contains("git restore --staged --worktree .github/workflows"),
          !yaml.contains("workflows:"),
        )
      ),
    ),
    section("Local apply")(
      md"""
The same rewrite, without waiting for the schedule. You do not need to know CI YAML.

1. **List what is stale.** `zipxDepUpdate` for Maven, `zipxActionUpdate` for Actions, `zipxPinUpdate` for pin feeds.
   `dry-run` lists only.
2. **Say yes, or type `y` at the prompt.** `yes` applies every listed bump. A bare command with no terminal lists and
   stops; pass `yes` from a script. Empty Action rows: `yes` is a no-op (the scheduled job stays green).
3. **Reload if the catalog file changed.** `project/ZipxVersions.scala` is part of the build definition.
4. **Regenerate if a plugin, sbt, Scala, or Action version moved.** `sbt zipxWorkflowGenerate`. The scheduled job
   always generates after apply.

```text
sbt zipxDepUpdate             # list catalog bumps, then prompt
sbt "zipxDepUpdate yes"       # apply all listed catalog bumps
sbt "zipxDepUpdate dry-run"

sbt zipxActionUpdate
sbt "zipxActionUpdate yes"
sbt "zipxActionUpdate dry-run"

sbt zipxPinUpdate             # list pin-feed bumps, then prompt
sbt "zipxPinUpdate yes"
sbt "zipxPinUpdate dry-run"
```

Catalog apply rewrites constructors in the catalog file only: `Lib("g", "a", "from")` / `Plugin(...)` for Maven,
`Action("owner/repo", "from", sha = …)` so version and git SHA stay together, and
`Pin("feed", "id", "from", sha256 = …, purl = …)` so version, checksum, and PURL stay together.
"""
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

`Cron` / `DayOfWeek` are re-exported from the plugin `autoImport`. The version-updates companion default is
`Cron.weekly(DayOfWeek.Sunday)`; set `zipxVersionUpdatesSchedule` to change it. Pin-feed companions use the same
default.
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
