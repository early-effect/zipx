package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*

/** Settings and tasks reference (tables generated from [[ZipxSettings]]). */
object Settings extends DocSpecSuite:

  def doc = page("Settings")(
    md"""
You can skip this reference until you need a knob. Defaults are enough for Aggregate Verify (test, fmt,
workflow-check, advisories) plus publish.

Write settings **without** `ThisBuild /`. zipx reads them from the root project (sbt 2).

A typed setting (`WorkflowName`, `JdkVersion`, `RunnerOs`, …) checks its literal where you write it.
`zipxTestTask` / `zipxPublishTask` / `zipxCacheRehydrateTask` are `SbtCommand` (prefer `zipxTasks` so a renamed key
fails at load). Declared command names are checked at `zipxWorkflowGenerate`. See **Validation**.
""",
    section("Build-level")(
      md"""
${SettingDef.settingsTable(ZipxSettings.buildLevel)}
"""
    ),
    section("Per-project")(
      md"""
${SettingDef.settingsTable(ZipxSettings.projectLevel)}

The catalog types these as `SbtCommand` (same as `ModuleNode` / `PlanConfig`). The plugin exposes
`settingKey[SbtCommand]` (`zipxTestTask`, `zipxPublishTask`, `zipxCacheRehydrateTask`). Prefer `zipxTasks` /
`cmd"…"` so a renamed task fails at load. See **Validation**.

By default a module is in the publish graph when it is not an aggregator, `publish / skip` is false, and
`publishArtifact` is true. Prefer `publish / skip := true` for non-publishers; set `zipxPublish := Some(false/true)`
only to override that derivation.
"""
    ),
    section("Capability model")(
      md"""
`Capability` fields: `name` (a `CapabilityName`), `phase`, `ordering`, `gate`, `participates`, `command`, `matrixed`,
`targets`, `needsCapabilities` (`List[CapabilityName]`), `permissions`, `runsOn`, `extraSteps`, `scope`
(`Aggregate` / `Layer` / `Graph` / `Once`),
`env`, `workflowCall`, `condition` (`Option[JobCondition]`, default `None`; prefer `withCondition(...)` to set, or
`andCondition(...)` to layer onto packs that already ship a condition). Compose with `JobCondition` `&&` / `||` / `!`.

`extraSteps` is a `Steps` bundle rather than a hand-written lambda: build steps with `Step.run(script)` /
`Step.uses(pin)`, bodies with the shell AST, and `${'$'}{{ … }}` values with `Expr`. Bundles compose with `++` and gate
with `.when(...)`, so a pack can publish one and a build can extend it. See **Shell and steps**.

Constructors: `Capability.test` / `.testJoined` / `.publish` / `.docker`, `.*Layers`, `.*Graph`, `.deploy` /
`.deployGraph`, `.custom`, `.once`. Packs: `ZipxCentral.*`, `ZipxGitHubPackages.*`, `ZipxDocs.pages`. A `Target` is
`(name, environment, env, condition)` with typed `EnvValue`s and `JobCondition`.

`CapabilityName` and `TargetName` are validated wrappers, not aliases for `String`: joined with `-` they *are* the
`jobs.<job_id>` key GitHub sees, so a space or a `/` in one used to produce a workflow that failed on push. A literal
is checked where you write it, `CapabilityName("docker-stg")`, and the built-in names are available as
`Capability.TestName` / `.FmtName` / `.WorkflowCheckName` / `.AdvisoriesName` / `.PublishName` / `.DockerName` /
`.DeployName` / `.PinCheckName` for `needsCapabilities`. What a combination of
fields cannot be checked at a literal (`needsCapabilities` cycles, `workflowCall` beside `services`, a never-true `if:`)
is checked at `zipxWorkflowGenerate`; see **Validation**.

Job env merge: `zipxEnv` → cache
backend → capability → target (`zipxCacheRehydrateEnv` overlays `zipxEnv` on rehydrate only). `zipxEnv` is omitted on
reusable-workflow caller jobs (`workflowCall` / `uses:`). See **Job conditions** for recipes (fork gate, PR-label
stage ECR, multi-publish, docs on dispatch).
"""
    ),
    section("Tasks")(
      md"""
${SettingDef.tasksTable(ZipxSettings.tasks)}
"""
    ),
    section("Action pins")(
      md"""
SHA pins for generated `uses:` lines. Full guide: **Action pins**.

Resolve order: leftover `.github/zipx/action-pins.yml` fails generate (paste `Action` vals). Else explicit
`zipxActions` (≠ `Defaults`) wins. Else jar `ActionPins.Defaults` overlaid with catalog `Action` rows.
`zipxActionUpdate` looks up GitHub releases, peels a SHA, and rewrites constructors. YAML is jar/generate output,
not an input. A `github-actions` Dependabot ecosystem is not needed.
"""
    ),
    section("Pin feeds")(
      md"""
Pins Dependabot never sees (CDN + sha256, tarball tags, vendor files). Full guide: **Pin feeds**.

`zipxPinFeeds` registers lookup and policy; inventory is catalog `Pin` vals (`zipxPins`). `zipxPinPrGate` is All /
Introduced / Off (Off skips pin OSV inside `zipxAdvisoryCheck`). Pin OSV folds into the builtin **advisories** job.
Scheduled apply and snapshot submit are companion workflows, not `ci.yml`. Local `zipxPinUpdate` lists outdated pins
and rewrites `Pin(...)` after approval (`yes`, or an interactive `y`), including alert-only feeds. Then you commit
and open the PR.
"""
    ),
    section("Versions catalog")(
      md"""
Typed `Lib` / `Plugin` / `Pin` / `Action` vals. You extend `ZipxVersions` and call `MyVersions.settings`, which collects
every val. You do not maintain a `coords` list. Excludes (`.excluding`) live on the row, not at the `libraryDependencies`
use site. Loaded plugins that emit themselves (`zipxSelfPlugins`) write their own `plugins.sbt` line; your `Plugin` vals
are the rest. Full guide: **Versions**. Plugin authors: **Extending Versions**.

`settings` sets `scalaVersion` and the catalog keys. `zipxCheckDeps` fails generate when `libraryDependencies` contain a
GAV that is not a `Lib` row. `zipxWorkflowGenerate` writes `project/plugins.sbt` and `project/build.properties`. Local
`zipxDepUpdate` / `zipxPinUpdate` / `zipxActionUpdate` rewrite constructors in `zipxVersionsFile`. Then you commit and
open the PR. After a plugin, sbt, Scala, or Action bump, `reload` and generate so those files match.
"""
    ),
  )
end Settings
