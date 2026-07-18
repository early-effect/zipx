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
| `zipxActions` | `ActionPins` | hash-pinned defaults | `uses:` pins |
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
`env`, `workflowCall`.

Constructors: `Capability.test` / `.testJoined` / `.publish` / `.docker`, `.*Layers`, `.*Graph`, `.deploy` /
`.deployGraph`, `.custom`, `.once`. Packs: `ZipxCentral.*`, `ZipxDocs.pages`. A `Target` is
`(name, environment, env, condition)` with typed `EnvValue`s. Job env merge: cache → capability → target.
"""
    ),
    section("Tasks")(
      md"""
| Task | Purpose |
|---|---|
| `zipxWorkflowGenerate` | write the workflow YAML |
| `zipxWorkflowCheck` | fail if committed YAML is stale |
| `zipxGraph` | print modules, edges, flags, layers |
| `zipxPublishOrder` | print contracted publish waves |
| `zipxAffectedModules <base-ref>` | print affected modules (used by the `affected` job) |
"""
    ),
  )
end Settings
