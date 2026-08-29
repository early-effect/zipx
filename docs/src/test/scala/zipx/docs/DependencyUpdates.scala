package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zipx.shell.{Exec, Script, Word}
import zipx.workflow.{Cron, DayOfWeek, Step}
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
  CI opens `zipx/pin-updates-${'$'}GITHUB_RUN_ID` if you opt a feed into `Update`. See **Pin feeds**.
""",
    section("Scheduled PR")(
      md"""
Default `zipxVersionUpdates := true` writes `.github/workflows/zipx-version-updates.yml` (schedule plus
`workflow_dispatch`). The default schedule is Sunday 00:00 UTC; set `zipxVersionUpdatesSchedule` to change it
(`Cron.daily`, `Cron.weekly`, `Cron.raw`). The job installs `cs` via `zipx-sbt-setup` (`coursier: true`), runs
`cs launch --ttl Inf --repository m2Local --repository ivy2Local --repository central rocks.earlyeffect:zipx-cli_3:${'$'}ZIPX_CLI_VERSION -- catalog update --yes --verify-load`
(a release writes `ZIPX_CLI_VERSION` to `project/zipx-ci.env`; in-dev dogfood exports it from `zipxVersionUpdatesPreSteps`). `--repository m2Local` is how zipx dogfood resolves a just-`publishLocal`'d `0.0.0-ci` CLI (sbt 2 writes Maven local; default `cs` does not search it). Empty `m2Local` on a consumer runner is a no-op. Then
`zipxPinUpdate yes` and `zipxCatalogGenerate`, and opens a PR as `github-actions[bot]` unless App secrets are set (below). The branch is
`zipx/version-updates-${'$'}GITHUB_RUN_ID` so a second dispatch cannot overwrite an open PR. The PR is labeled **`clean`**,
so Verify runs `cleanFull` (same label as a one-off human PR). That PR is every ZipxVersions row kind: Lib / Plugin /
Action / Pin constructors, plus `plugins.sbt` and composites when those moved.

**The companion never writes repo-root `.github/workflows/`.** `GITHUB_TOKEN` cannot push those files: GitHub App
tokens need a `workflows` git permission that `permissions:` cannot grant (the same reject Scala Steward hits). The job
parameterizes instead of rewriting YAML:

- **JDK and runner** come from `project/zipx-ci.env` at runtime.
- **Java and sbt Action pins** live in `zipx-sbt-setup` (the bot can push `.github/actions/`).
- **Checkout** is a major tag (`actions/checkout@v7`). `uses:` cannot be an expression, so a SHA pin would force a
  workflow rewrite. Root `ci.yml` stays SHA-pinned.
- **`git add`** excludes repo-root `.github/workflows` only. Nested trees such as `examples/monorepo/.github/workflows/`
  are staged.

`zipxVersionUpdatesPreSteps` (default empty) runs after setup and before `zipx-cli` apply. zipx dogfoods this to
`publishLocal` the **whole** in-dev graph (not `cli/publishLocal` alone: `cs launch` still needs `zipx-core` and
`zipx-syntax` at the same dynver) and export `ZIPX_CLI_VERSION` on `GITHUB_ENV`, so Sunday `cs launch` can resolve that
version without baking dynver into committed `zipx-ci.env`.

`zipxVersionUpdatesExtraSteps` (default empty) runs after `zipxCatalogGenerate` and before the PR opens. Any zipx repo
can set it. The usual case is an **sbt plugin** whose nested example (or scripted fixture) must see the in-dev plugin:
`publishLocal`, then `zipxWorkflowGenerate` in that tree. Nested `.github/workflows/` is not repo-root, so
`GITHUB_TOKEN` can commit that `ci.yml` and its composites.

```scala
zipxVersionUpdatesExtraSteps := Seq(
  Step.run(publish).named("Publish plugin locally"),
  Step.run(generate).named("Generate example workflow").in("examples/foo"),
)
```

zipx dogfoods this for `examples/monorepo` (`ExampleCheck.companionSteps` in `project/`). You do not regenerate that
example by hand on every Action pin bump.

Generate the companion YAML once from a clone (human `zipxWorkflowGenerate`); the bot then leaves the companion file
alone. A checkout SHA bump in the catalog can still make root `zipxWorkflowCheck` fail. The PR body names this run's
branch (`zipx/version-updates-${'$'}GITHUB_RUN_ID`) and the exact commands to regenerate **repo-root** workflows onto it:

```text
git fetch origin zipx/version-updates-<run-id>
git checkout zipx/version-updates-<run-id>
sbt zipxWorkflowGenerate
git add .github/workflows
git commit -m "ci: regenerate workflows"
git push origin zipx/version-updates-<run-id>
```

Do not commit those root workflow files to `main`. Composites under `.github/actions/` (and nested example YAML) are
already in the bot commit.

`zipxVersionUpdates := false` deletes the companion.

**Required repo/org setting:** [Allow GitHub Actions to create and
approve pull requests](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository#preventing-github-actions-from-creating-or-approving-pull-requests).
No PAT.

`GITHUB_TOKEN` still opens the PR by default. Since GitHub's 11 June 2026 change, that `pull_request` CI waits for a
write-access **Approve workflows to run**. Set org (or repo) secrets `ZIPX_APP_ID` and `ZIPX_APP_PRIVATE_KEY` to mint
an installation token before checkout: the PR author is the App, a write collaborator, and CI starts on its own. Both
secrets or neither; exactly one fails the job. Grant the App contents + pull-requests + issues, not `workflows`. Do
**not** loosen **Fork pull request workflows** to skip the banner: that would also auto-run stranger forks.
""",
      exampleValue {
        VersionUpdatesWorkflow.render(ActionPins.Defaults).yaml
      }.assert(yaml =>
        assertTrue(
          yaml.contains("workflow_dispatch"),
          yaml.contains("cs launch"),
          yaml.contains("catalog update"),
          yaml.contains("zipxPinUpdate yes"),
          yaml.contains("zipxCatalogGenerate"),
          yaml.contains("zipx/version-updates-${GITHUB_RUN_ID}") || yaml.contains("gh pr create"),
          yaml.contains("contents: write") || yaml.contains("contents:write"),
          yaml.contains("pull-requests: write") || yaml.contains("pull-requests:write"),
          yaml.contains("issues: write") || yaml.contains("issues:write"),
          yaml.contains("--label clean"),
          yaml.contains("./.github/actions/zipx-sbt-setup"),
          yaml.contains("project/zipx-ci.env"),
          yaml.contains("git add --all"),
          yaml.contains(":!.github/workflows"),
          !yaml.contains("git add -A"),
          yaml.contains("--body-file"),
          yaml.contains("sbt zipxWorkflowGenerate"),
          yaml.contains("git push origin zipx/version-updates-${GITHUB_RUN_ID}"),
          yaml.contains("coursier: \"true\"") || yaml.contains("coursier: true"),
          yaml.contains("--repository m2Local"),
          yaml.contains("--repository ivy2Local"),
          yaml.contains("--repository central"),
          yaml.contains("Detect GitHub App credentials"),
          yaml.contains("actions/create-github-app-token@v3"),
          yaml.contains("ZIPX_APP_ID"),
          yaml.contains("ZIPX_APP_PRIVATE_KEY"),
          yaml.indexOf("Detect GitHub App credentials") < yaml.indexOf("actions/checkout@v7"),
          yaml.indexOf("Apply catalog updates") < yaml.indexOf("Open update PR"),
          yaml.indexOf("sbt zipxCatalogGenerate") < yaml.indexOf("Open update PR"),
          yaml.indexOf("sbt zipxWorkflowGenerate", yaml.indexOf("Open update PR")) > yaml.indexOf("Open update PR"),
        )
      ),
      exampleValue {
        val extra = List(
          Step
            .run(Script.strict(Exec("sbt", Word.squote("publishLocal"))))
            .named("Publish plugin locally")
            .build,
          Step
            .run(Script.strict(Exec("sbt", Word.squote("zipxWorkflowGenerate"))))
            .named("Generate example workflow")
            .in("examples/foo")
            .build,
        )
        VersionUpdatesWorkflow.render(ActionPins.Defaults, extraSteps = extra).yaml
      }.assert(yaml =>
        val applyAt = yaml.indexOf("Apply catalog updates")
        val pubAt   = yaml.indexOf("Publish plugin locally")
        val genAt   = yaml.indexOf("Generate example workflow")
        val openAt  = yaml.indexOf("Open update PR")
        assertTrue(
          pubAt > applyAt,
          genAt > pubAt,
          genAt < openAt,
          yaml.contains("working-directory: examples/foo"),
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
4. **Regenerate catalog outputs if a plugin, sbt, Scala, or Action version moved.** `sbt zipxCatalogGenerate` writes
   `plugins.sbt`, composites, and `project/zipx-ci.env`. Use `sbt zipxWorkflowGenerate` when `ci.yml` itself must
   change (checkout major, job graph). The scheduled job runs `zipxCatalogGenerate` only.

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

Lookup skips pre-releases by default (`zipxPreRelease := PreRelease.Skip`). A stable `2.0.18` does not become
`2.1.0-alpha1`. Set `zipxPreRelease := PreRelease.Include` to list alphas. GitHub Action lookup already ignores
prerelease releases.
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
