package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite

/** Settings and tasks reference. */
object Settings extends DocSpecSuite:

  def doc = page("Settings")(
    md"""
All settings have sensible derived defaults. Write them as **bare settings** (no `ThisBuild /`).
""",
    section("Build-level")(
      md"""
| Setting | Type | Default | Purpose |
|---|---|---|---|
| `zipxCapabilities` | `Seq[Capability]` | `Seq.empty` | custom capabilities (same name replaces built-in) |
| `zipxWorkflowName` | `String` | `"CI"` | workflow `name:` |
| `zipxWorkflowPath` | `String` | `.github/workflows/ci.yml` | output path |
| `zipxJavaVersion` | `String` | `"21"` | JDK for setup-java and cache key |
| `zipxRunnerOs` | `String` | `"ubuntu-latest"` | default runner |
| `zipxScalaMatrix` | `Boolean` | `true` | per-module Scala matrix (**Graph** test only) |
| `zipxActions` | `ActionPins` | jar defaults | one-off `uses:` override (**prefer pin file**; see **Action pins**) |
| `zipxActionsPath` | `String` | `.github/zipx/action-pins.yml` | pin file path (`""` disables file loading) |
| `zipxDependabotSync` | `Boolean` | `false` | also generate `.github/workflows/zipx-action-pins-sync.yml` |
| `zipxScalaSteward` | `Boolean` | `false` | also generate `.github/workflows/zipx-scala-steward.yml` |
| `zipxWorkflowDispatch` | `Boolean` | `false` | emit `on.workflow_dispatch` |
| `zipxCache` | `CacheBackend` | `LocalDir` | cache strategy |
| `zipxCacheEpoch` | `String` | `version` | commit-stable cache epoch |
| `zipxPushBranches` | `Seq[String]` | `Seq("main")` | push triggers |
| `zipxReleaseTagPattern` | `String` | `v[0-9]+.[0-9]+.[0-9]+` | publish gate |
| `zipxAffectedOnPR` | `Boolean` | `true` | affected setup when Graph Verify present |
| `zipxAffectedOnPush` | `Boolean` | `false` | also scope pushes |
| `zipxSkipMergedPrPush` | `Boolean` | `true` | skip Verify on merged-PR pushes |
| `zipxVerifyClean` | `VerifyClean` | `None` | optional clean before Verify commands |
"""
    ),
    section("Per-project")(
      md"""
| Setting | Type | Default | Purpose |
|---|---|---|---|
| `zipxCiRelevant` | `Boolean` | `true` (false for aggregators) | include in test participation |
| `zipxPublish` | `Option[Boolean]` | derived from `publishArtifact` | force publish on/off |
| `zipxDocker` | `Boolean` | derived from `DockerPlugin` | build a docker image |
| `zipxTestTask` | `String` | `"test"` | Aggregate root Verify + Graph/Layer task |
| `zipxPublishTask` | `String` | `"publish"` | publish task |
"""
    ),
    section("Capability model")(
      md"""
`Capability` fields: `name`, `phase`, `ordering`, `gate`, `participates`, `command`, `matrixed`, `targets`,
`needsCapabilities`, `permissions`, `runsOn`, `extraSteps`, `scope` (`Aggregate` / `Layer` / `Graph` / `Once`),
`env`, `workflowCall`, `condition` (`Option[JobCondition]`, default `None`; prefer `withCondition(...)` to set, or
`andCondition(...)` to layer onto packs that already ship a condition). Compose with `JobCondition` `&&` / `||` / `!`.

Constructors: `Capability.test` / `.testJoined` / `.publish` / `.docker`, `.*Layers`, `.*Graph`, `.deploy` /
`.deployGraph`, `.custom`, `.once`. Packs: `ZipxCentral.*`, `ZipxGitHubPackages.*`, `ZipxDocs.pages`. A `Target` is
`(name, environment, env, condition)` with typed `EnvValue`s and `JobCondition`. Job env merge: cache → capability →
target. See **Job conditions** for recipes (fork gate, PR-label stage ECR, multi-publish, docs on dispatch).
"""
    ),
    section("Tasks")(
      md"""
| Task | Purpose |
|---|---|
| `zipxWorkflowGenerate` | write the workflow YAML (and companion workflows when enabled) |
| `zipxWorkflowCheck` | fail if committed YAML is stale (includes companions when enabled) |
| `zipxActionsPull` | pull `uses:` SHAs from the workflow into the pin file, then regenerate |
| `zipxGraph` | print modules, edges, flags, layers |
| `zipxPublishOrder` | print contracted publish waves |
| `zipxAffectedModules <base-ref>` | print affected modules (used by the `affected` job) |
"""
    ),
    section("Action pins")(
      md"""
SHA pins for generated `uses:` lines. Full guide: **Action pins**.

Resolve order: explicit `zipxActions` (≠ `Defaults`) → `.github/zipx/action-pins.yml` when present → jar
`ActionPins.Defaults`. Dependabot bumps workflow YAML; `zipxActionsPull` (or the sync workflow) writes the pin file
and regenerates so `zipxWorkflowCheck` stays green.
"""
    ),
  )
end Settings
