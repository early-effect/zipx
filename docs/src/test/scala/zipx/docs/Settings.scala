package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*

/** Settings and tasks reference (tables generated from [[ZipxSettings]]). */
object Settings extends DocSpecSuite:

  def doc = page("Settings")(
    md"""
You can skip this reference until you need a knob. Defaults are enough for Aggregate test + publish.

Write settings **without** `ThisBuild /`. zipx reads them from the root project (sbt 2).

A typed setting (`WorkflowName`, `JdkVersion`, `RunnerOs`, …) checks its literal where you write it; the `String`-typed
task settings below are checked at `zipxWorkflowGenerate` instead. See **Validation**.
""",
    section("Build-level")(
      md"""
${SettingDef.settingsTable(ZipxSettings.buildLevel)}
"""
    ),
    section("Per-project")(
      md"""
${SettingDef.settingsTable(ZipxSettings.projectLevel)}

The catalog types these as `SbtCommand` (same as `ModuleNode` / `PlanConfig`). The plugin still exposes
`settingKey[String]` because an opaque type there would need a `JsonFormat`; the check moves to
`zipxWorkflowGenerate`, which names the setting. `zipxTasks` / `cmd"…"` take real `TaskKey`s and skip it. See
**Validation**.

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
`Capability.TestName` / `.PublishName` / `.DockerName` / `.DeployName` / `.PinCheckName` for `needsCapabilities`. What a combination of
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

Resolve order: explicit `zipxActions` (≠ `Defaults`) → `.github/zipx/action-pins.yml` when present → jar
`ActionPins.Defaults`. Dependabot bumps workflow YAML; `zipxActionsPull` (or the sync workflow) writes the pin file
and regenerates so `zipxWorkflowCheck` stays green.

A present pin file must be readable in full: a line `zipx` cannot use (a typo'd key, an unpinned ref, a key pointing
at a different action) fails `zipxWorkflowGenerate` naming the line, rather than falling back to the jar pin for that
field. An *absent* file still falls back silently, since that is the documented default.
"""
    ),
    section("Pin feeds")(
      md"""
Pins Dependabot never sees (CDN + sha256, tarball tags, vendor files). Full guide: **Pin feeds**.

`zipxPinFeeds` registers feeds; `zipxPinPrGate` is All / Introduced / Off. The PR job is builtin `Capability.pinCheck`.
Scheduled apply and snapshot submit are companion workflows, not `ci.yml`. Local `zipxPinUpdate` lists outdated pins
and applies only after approval (`yes`, or an interactive `y`), including alert-only feeds. Then you commit and open
the PR.
"""
    ),
    section("Versions catalog")(
      md"""
Typed `Lib` / `Plugin` rows in `project/ZipxVersions.scala`. Full guide: **Versions**.

`zipxVersions` is the catalog; `zipxCheckDeps` fails generate when `libraryDependencies` contain a GAV that is not a
`Lib` row. `zipxWorkflowGenerate` writes `project/plugins.sbt` and `project/build.properties`. Local `zipxDepUpdate`
rewrites version literals in `zipxVersionsFile`. Then you commit and open the PR. After a plugin, sbt, or Scala bump,
`reload` and generate so those files match.
"""
    ),
  )
end Settings
