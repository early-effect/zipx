package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.workflow.{Cron, DayOfWeek}
import zio.test.*

/** Bump locally, then open a PR. Four catalog kinds; ZipxVersions is required. */
object DependencyUpdates extends DocSpecSuite:

  def doc = page("Dependency updates")(
    md"""
Libraries, plugins, GitHub Actions, and CDN/vendor pins go stale. zipx does not try to be a weekly bot for that.

**ZipxVersions is required.** Versions live in typed constructors in `project/ZipxVersions.scala`. `zipxCheckDeps`
fails undeclared GAVs. You bump locally with `zipxDepUpdate` / `zipxPinUpdate` / `zipxActionUpdate`, look at a
constructor diff, run tests, and open the PR. That is the product. There is no weekly bot job for the catalog.

A regex across `build.sbt` cannot move version with sha256 / purl / git SHA, and a string-only PR would still fail
generate. Do not install a search-replace bot.

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
  CI can open a PR if you opt a feed into `Update`. See **Pin feeds**.
""",
    section("Before you open a PR")(
      md"""
This is the intended loop. You do not need a dependency bot, and you do not need to know CI YAML.

1. **List what is stale.** In sbt: `zipxDepUpdate` for Maven, `zipxPinUpdate` for pin feeds, `zipxActionUpdate` for
   Actions. Each prints every bump it would apply. `dry-run` lists only.
2. **Say yes, or type `y` at the prompt.** `yes` applies every listed bump (there is no "just this one"). A bare
   command with no terminal lists and stops; pass `yes` from a script.
3. **Reload if the catalog file changed.** `project/ZipxVersions.scala` is part of the build definition. Leave sbt or
   `reload` so the new versions are loaded.
4. **Regenerate if a plugin, sbt, Scala, or Action version moved.** `sbt zipxWorkflowGenerate`, then commit
   `plugins.sbt` / `build.properties` (and workflows if they changed). A library-only bump does not need this.
5. **Run your tests.** Then commit and open a pull request, the same way you would for any other edit.

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
`Pin("feed", "id", "from", sha256 = …, purl = …)` so version, checksum, and PURL stay together. Not a search across
`build.sbt`. After any of those commands, **you** still commit and open the PR (unless a pin feed is `Update` and CI
already opened `zipx/pin-updates`).
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

`Cron` / `DayOfWeek` are re-exported from the plugin `autoImport`. Pin-feed companions use the same Sunday weekly
default. See **Pin feeds**.
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
