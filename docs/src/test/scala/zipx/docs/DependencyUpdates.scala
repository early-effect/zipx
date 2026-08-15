package zipx.docs

import neotype.unwrap
import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zipx.workflow.{Cron, DayOfWeek}
import zio.test.*

/** Three machines: Dependabot (Actions), Scala Steward (sbt/Scala), pin feeds (everything else). */
object DependencyUpdates extends DocSpecSuite:

  def doc = page("Dependency updates")(
    md"""
zipx does not fold every ecosystem into one bot. Three machines, three owners:

```mermaid
flowchart LR
  Act[GitHub Actions SHAs] --> Dep[Dependabot plus zipxDependabotSync]
  Scala[sbt and Maven GAVs] --> St[zipxScalaSteward]
  Other[CDN sha256 pins, tarballs, vendor files] --> Pf[Pin feeds]
```

- **Dependabot** (`github-actions`) bumps SHA-pinned GitHub Actions in generated workflows; sync back via the pin
  file (`zipxDependabotSync` / `zipxActionsPull`). See **Action pins**.
- **Scala Steward** bumps sbt / Scala / library dependencies on CI. Opt in with `zipxScalaSteward := true`. Updates
  arrive grouped into a handful of PRs (`zipxStewardGrouping`). Locally, a typed catalog plus `zipxDepUpdate` is the
  apply path; see **Versions**.
- **Pin feeds** cover pins Dependabot never sees. zipx owns topology and policy; the build owns lookup and apply.
  Local `zipxPinUpdate` bumps with approval before a PR. See **Pin feeds**.

Do not generate `dependabot.yml`. Do not fold Steward onto `PinFeed`.
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
        ScalaStewardWorkflow.render(ActionPins.Defaults, "ubuntu-latest").yaml
      }.assert(yaml =>
        assertTrue(
          yaml.contains("cron: 0 0 * * 0") || yaml.contains("""cron: "0 0 * * 0""""),
          yaml.contains("scala-steward-org/scala-steward-action@"),
          yaml.contains("workflow_dispatch"),
        )
      ),
    ),
    section("Grouped update PRs")(
      md"""
By default Scala Steward opens one PR per dependency. zipx instead generates a `pullRequests.grouping` config so
updates land in a few PRs: one per dependency family, then everything else split by minor-and-patch versus major.

```scala
zipxStewardGrouping := ScalaStewardConfig.Defaults        // the default
zipxStewardGrouping := ScalaStewardConfig.Defaults.prepended(
  StewardGroup("http4s", Some("Update http4s"), List(StewardFilter(group = Some("org.http4s"))))
)
zipxStewardGrouping := Nil                                // back to one PR per dependency
```

Groups are **first-match-wins in list order**, so put narrow groups before broad ones. `StewardGroup` /
`StewardFilter` / `ScalaStewardConfig` are re-exported from the plugin `autoImport`.

Two things worth knowing, because neither is guessable:

1. **Set grouping here, not in `.scala-steward.conf`.** The generated config reaches Steward through the action's
   `repo-config` input, which is its *global* config channel and is merged **ahead** of the repo's own
   `.scala-steward.conf`. Since the default list ends in a `{ group = "*" }` catch-all, a `pullRequests.grouping`
   block in the repo file would never be reached. `zipxWorkflowGenerate` / `zipxWorkflowCheck` **warn** when the root
   file still sets that key. Everything else (`updates.ignore`, `updates.retracted`, ...) still belongs in the repo
   file and merges normally.
2. **The generated workflow gains a checkout step.** The action reads `repo-config` off the runner filesystem, not
   out of Steward's own clone, and it does not check anything out itself. Worse, it *silently* ignores a missing file
   at the default path. So `.github/.scala-steward.conf` is checked out by the workflow, and `zipxWorkflowCheck`
   drift-checks it: the check failing is what tells you the config is missing, because Steward never would.

One more non-obvious detail: patch bumps deliberately ride along in the `minor` group, since a separate patch group
would re-fragment the PRs this is meant to consolidate. And the trailing catch-all is load-bearing rather than
decorative: Steward's version filters need both sides of a bump to parse as strict semver, and anything that fails
to parse escapes every version-filtered group.
""",
      exampleValue {
        ScalaStewardConfig.render(ScalaStewardConfig.Defaults)
      }.assert(conf =>
        assertTrue(
          conf.contains("pullRequests.grouping = ["),
          conf.contains("""filter = [{ group = "rocks.earlyeffect" }]"""),
          conf.indexOf("""group = "rocks.earlyeffect"""") < conf.indexOf("""version = "minor""""),
          conf.indexOf("""version = "minor"""") < conf.indexOf("""version = "major""""),
          conf.indexOf("""version = "major"""") < conf.indexOf("""group = "*""""),
        )
      ),
      exampleValue {
        ScalaStewardWorkflow
          .render(
            ActionPins.Defaults,
            "ubuntu-latest",
            configPath = Some(ScalaStewardWorkflow.DefaultConfigPath),
          )
          .yaml
      }.assert(yaml =>
        assertTrue(
          yaml.contains("repo-config: .github/.scala-steward.conf"),
          yaml.indexOf(ActionPins.Defaults.checkout.unwrap) < yaml.indexOf(ActionPins.Defaults.scalaSteward.unwrap),
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
