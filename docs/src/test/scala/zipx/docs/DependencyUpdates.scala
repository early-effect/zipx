package zipx.docs

import neotype.unwrap
import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zipx.workflow.{Cron, DayOfWeek}
import zio.test.*

/** Bump locally, then open a PR. Three machines for three kinds of pin. */
object DependencyUpdates extends DocSpecSuite:

  def doc = page("Dependency updates")(
    md"""
Libraries, plugins, and GitHub Actions go stale. Some repos also pin a CDN URL plus a checksum, or vendor a file.
zipx does not try to be one bot for all of that.

**Most weeks you bump locally, look at the diff, and open a pull request yourself.** Same as any other change. The
commands are `zipxDepUpdate` (Scala libraries and sbt plugins) and `zipxPinUpdate` (everything that is not Maven and
not a GitHub Action). Details in **Before you open a PR**.

Three places versions live:

```mermaid
flowchart LR
  Act[GitHub Actions SHAs] --> Dep[Dependabot plus zipxDependabotSync]
  Scala[sbt and Maven libraries] --> Cat[ZipxVersions plus zipxDepUpdate]
  Other[CDN checksums, tarballs, vendor files] --> Pf[Pin feeds]
```

- **GitHub Actions** in generated workflows. GitHub's Dependabot can open a PR when an Action SHA moves; you sync that
  back into zipx's pin file. zipx does not write `dependabot.yml`; you add a `github-actions` ecosystem yourself. See
  **Action pins**.
- **Scala libraries and sbt plugins.** One typed catalog (`Lib` / `Plugin`), not a regex over `build.sbt`. You run
  `zipxDepUpdate`. There is no weekly zipx job that opens a catalog PR. See **Versions**.
- **Pins that are not Maven and not Actions.** A pin feed lists them; you run `zipxPinUpdate`, or CI can open a PR if
  you opt a feed into auto-apply. See **Pin feeds**.
""",
    section("Before you open a PR")(
      md"""
This is the intended loop. You do not need a dependency bot, and you do not need to know CI YAML.

1. **List what is stale.** In sbt: `zipxDepUpdate` for the catalog, `zipxPinUpdate` for pin feeds. Each prints every
   bump it would apply. `dry-run` lists only.
2. **Say yes, or type `y` at the prompt.** `yes` applies every listed bump (there is no "just this one"). A bare
   command with no terminal lists and stops; pass `yes` from a script.
3. **Reload if the catalog file changed.** `project/ZipxVersions.scala` is part of the build definition. Leave sbt or
   `reload` so the new versions are loaded.
4. **Regenerate if a plugin, sbt, or Scala version moved.** `sbt zipxWorkflowGenerate`, then commit `plugins.sbt` /
   `build.properties` (and workflows if they changed). A library-only bump does not need this.
5. **Run your tests.** Then commit and open a pull request, the same way you would for any other edit.

```text
sbt zipxDepUpdate             # list catalog bumps, then prompt
sbt "zipxDepUpdate yes"       # apply all listed catalog bumps
sbt "zipxDepUpdate dry-run"

sbt zipxPinUpdate             # list pin-feed bumps, then prompt
sbt "zipxPinUpdate yes"
sbt "zipxPinUpdate dry-run"
```

Catalog apply rewrites version *constructors* in the catalog file only (`Lib("g", "a", "from")`), not a search across
`build.sbt`. Pin-feed apply goes through the feed so a version and a checksum stay together. After either command,
**you** still commit and open the PR.
"""
    ),
    section("Optional: a bot that opens catalog PRs")(
      md"""
[Scala Steward](https://github.com/scala-steward-org/scala-steward) is a separate tool that opens pull requests when
Maven or sbt versions are outdated. Its apply path is still brittle: regex (and similar) rewrites of version strings
in the build. That is the weakness of almost every Scala bump tool. You do not need it: the catalog plus
`zipxDepUpdate` rewrites typed constructors in one file. See **Versions**.

If you still want that bot, zipx can generate a weekly workflow that runs it:

```scala
zipxScalaSteward := true
```

Then `zipxWorkflowGenerate` also writes `.github/workflows/zipx-scala-steward.yml`: weekly cron (Sunday 00:00 UTC)
plus `workflow_dispatch`, using the SHA-pinned `scala-steward-org/scala-steward-action` and the default GitHub
Actions token (no extra GitHub App secrets).

**Required repo/org setting:** [Allow GitHub Actions to create and approve pull requests](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository#preventing-github-actions-from-creating-or-approving-pull-requests).

This repo still generates that workflow because `zipxScalaSteward` is on here. The catalog is still how versions are
listed.

An alternative with no workflow is installing the public [Scala Steward GitHub App](https://github.com/apps/scala-steward)
on the org/repo. zipx's opt-in is the self-hosted Action path so pins and schedule stay in the build.
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
Only if you opted into the Scala Steward workflow. By default that tool opens one PR per dependency. zipx instead
generates a `pullRequests.grouping` config so updates land in a few PRs: one per dependency family, then everything
else split by minor-and-patch versus major.

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
