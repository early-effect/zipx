package zipx.core

/** Catalog of every public `zipx*` autoImport key. Plugin and Settings docs both consume this. */
object ZipxSettings:

  import SettingScope.*

  // ---- Build-level settings ----

  val capabilities: SettingDef[Seq[Capability]] =
    SettingDef.setting(
      SettingName("zipxCapabilities"),
      Seq.empty,
      SettingPurpose("Custom capabilities (same name replaces built-in)."),
      Build,
    )

  val cache: SettingDef[CacheBackend] =
    SettingDef.setting(
      SettingName("zipxCache"),
      CacheBackend.LocalDir,
      SettingPurpose("Cache backend: LocalDir (default), BazelRemoteSidecar, or ManagedRemote."),
      Build,
    )

  val workflowName: SettingDef[WorkflowName] =
    SettingDef.setting(
      SettingName("zipxWorkflowName"),
      PlanConfig.DefaultWorkflowName,
      SettingPurpose("Name of the generated GitHub Actions workflow."),
      Build,
    )

  val workflowPath: SettingDef[String] =
    SettingDef.setting(
      SettingName("zipxWorkflowPath"),
      ".github/workflows/ci.yml",
      SettingPurpose("Workflow file path relative to the build root (default .github/workflows/ci.yml)."),
      Build,
    )

  val javaVersion: SettingDef[JdkVersion] =
    SettingDef.setting(
      SettingName("zipxJavaVersion"),
      PlanConfig.DefaultJdkVersion,
      SettingPurpose("JDK major version for the CI matrix and cache key."),
      Build,
    )

  val runnerOs: SettingDef[RunnerOs] =
    SettingDef.setting(
      SettingName("zipxRunnerOs"),
      PlanConfig.DefaultRunnerOs,
      SettingPurpose("GitHub Actions runner label (default ubuntu-latest)."),
      Build,
    )

  val scalaMatrix: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxScalaMatrix"),
      true,
      SettingPurpose("Expand a per-module Scala matrix over crossScalaVersions (Graph test only)."),
      Build,
    )

  val matrixCollapse: SettingDef[Map[CapabilityName, MatrixCollapse]] =
    SettingDef.setting(
      SettingName("zipxMatrixCollapse"),
      Map.empty,
      SettingPurpose(
        "Per-capability MatrixCollapse defaults (Auto / Off / Strict / Coarse). Capability.withMatrixCollapse overrides. Empty = Auto."
      ),
      Build,
    )

  val cacheEpoch: SettingDef[CacheEpoch] =
    SettingDef.setting(
      SettingName("zipxCacheEpoch"),
      CacheEpoch.GitTags(),
      SettingPurpose(
        "LocalDir cache epoch strategy (default CacheEpoch.GitTags). Use CacheEpoch.Fixed(version.value) to bake at generate time."
      ),
      Build,
    )

  val pushBranches: SettingDef[Seq[String]] =
    SettingDef.setting(
      SettingName("zipxPushBranches"),
      Seq("main"),
      SettingPurpose("Branches whose pushes trigger CI."),
      Build,
    )

  val releaseTagPattern: SettingDef[String] =
    SettingDef.setting(
      SettingName("zipxReleaseTagPattern"),
      "v[0-9]+.[0-9]+.[0-9]+",
      SettingPurpose("Tag glob that gates publishing."),
      Build,
    )

  val actions: SettingDef[ActionPins] =
    SettingDef.setting(
      SettingName("zipxActions"),
      ActionPins.Defaults,
      SettingPurpose(
        "Hash-pinned GitHub Actions (checkout, setup-java, setup-sbt, cache). Override for one-offs; prefer the pin file."
      ),
      Build,
    )

  val actionsPath: SettingDef[String] =
    SettingDef.setting(
      SettingName("zipxActionsPath"),
      ActionPinFile.DefaultPath,
      SettingPurpose(
        "Path to the action-pins YAML relative to the build root (default .github/zipx/action-pins.yml). Empty disables file loading."
      ),
      Build,
    )

  val dependabotSync: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxDependabotSync"),
      false,
      SettingPurpose(
        "When true, also generate .github/workflows/zipx-action-pins-sync.yml to sync Dependabot SHA bumps into the pin file."
      ),
      Build,
    )

  val scalaSteward: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxScalaSteward"),
      false,
      SettingPurpose(
        "When true, also generate .github/workflows/zipx-scala-steward.yml (weekly Scala Steward via GITHUB_TOKEN)."
      ),
      Build,
    )

  val stewardGrouping: SettingDef[Seq[StewardGroup]] =
    SettingDef.setting(
      SettingName("zipxStewardGrouping"),
      ScalaStewardConfig.Defaults,
      SettingPurpose(
        "Scala Steward pullRequests.grouping written to .github/.scala-steward.conf so updates land in a few PRs instead of one each (default ScalaStewardConfig.Defaults). Empty disables the config file. Set it here, not in the repo's .scala-steward.conf: this list is matched first and ends in a catch-all."
      ),
      Build,
    )

  val workflowDispatch: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxWorkflowDispatch"),
      false,
      SettingPurpose("Emit on.workflow_dispatch so the workflow can be run manually (default false)."),
      Build,
    )

  val affectedOnPR: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxAffectedOnPR"),
      true,
      SettingPurpose("Whether Verify jobs run only for affected modules on PRs (default true)."),
      Build,
    )

  val affectedOnPush: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxAffectedOnPush"),
      false,
      SettingPurpose("Also restrict pushes to affected modules via the before-sha diff (default false)."),
      Build,
    )

  val affectedPublish: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxAffectedPublish"),
      false,
      SettingPurpose(
        "Also affected-gate Graph-scope Publish jobs, so one changed module does not rebuild every image (default false; release tags always publish everything). Separate from zipxAffectedOnPR because under-verifying is silently unsafe while under-publishing is loudly broken."
      ),
      Build,
    )

  val affectedDeploy: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxAffectedDeploy"),
      false,
      SettingPurpose(
        "Also affected-gate Graph-scope Deploy jobs, so a deploy skips exactly when the publish it consumes did (default false; release tags always deploy everything). Separate from zipxAffectedPublish because narrowing image pushes while still reconciling every destination is a legitimate combination."
      ),
      Build,
    )

  val skipMergedPrPush: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxSkipMergedPrPush"),
      true,
      SettingPurpose("Skip Verify on branch pushes when the commit already belongs to a merged PR (default true)."),
      Build,
    )

  val cacheRehydrateOnMerge: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxCacheRehydrateOnMerge"),
      true,
      SettingPurpose(
        "On merged-PR pushes (when skipMergedPrPush skips Verify), run a minimal LocalDir cache-rehydrate job so the default branch gets an actions/cache save for later PRs (default true; inert for remote caches)."
      ),
      Build,
    )

  val cacheRehydrateTask: SettingDef[SbtCommand] =
    SettingDef.setting(
      SettingName("zipxCacheRehydrateTask"),
      PlanConfig.DefaultCacheRehydrateTask,
      SettingPurpose("sbt command for the cache-rehydrate job (default compile). Not full Verify."),
      Build,
    )

  val cacheRehydrateExtraSteps: SettingDef[StepContext => List[zipx.workflow.Step]] =
    SettingDef.setting(
      SettingName("zipxCacheRehydrateExtraSteps"),
      (_ => Nil),
      SettingPurpose(
        "Optional steps on cache-rehydrate after LocalDir restore and before the rehydrate task (default empty). Not copied from Verify capabilities."
      ),
      Build,
    )

  val cacheRehydrateEnv: SettingDef[Map[String, EnvValue]] =
    SettingDef.setting(
      SettingName("zipxCacheRehydrateEnv"),
      Map.empty,
      SettingPurpose("Optional env for the cache-rehydrate job only (default empty). Overlay on zipxEnv."),
      Build,
    )

  val env: SettingDef[Map[String, EnvValue]] =
    SettingDef.setting(
      SettingName("zipxEnv"),
      Map.empty,
      SettingPurpose(
        "Build-wide job env for normal generated jobs (default empty). Capability/target env overlay this. Omitted on workflow_call callers."
      ),
      Build,
    )

  val cancelSupersededRuns: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxCancelSupersededRuns"),
      true,
      SettingPurpose(
        "Emit workflow concurrency so a new push cancels an in-flight run on the same ref (default true). Release-tag runs are never cancelled."
      ),
      Build,
    )

  val checkCommandNames: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxCheckCommandNames"),
      true,
      SettingPurpose(
        "Fail zipxWorkflowGenerate when a declared sbt command name is unknown (default true)."
      ),
      Build,
    )

  val verifyClean: SettingDef[VerifyClean] =
    SettingDef.setting(
      SettingName("zipxVerifyClean"),
      VerifyClean.None,
      SettingPurpose("Optional clean/cleanFull prepended to every Verify sbt command (default None)."),
      Build,
    )

  val verifyCleanLabel: SettingDef[Option[String]] =
    SettingDef.setting(
      SettingName("zipxVerifyCleanLabel"),
      Some("clean"),
      SettingPurpose(
        """When zipxVerifyClean is None, prepend cleanFull on PRs that have this label (default Some("clean")). None disables. One-off cache bust."""
      ),
      Build,
    )

  // ---- Per-project settings ----

  val ciRelevant: SettingDef[Boolean] =
    SettingDef.settingDerived(
      SettingName("zipxCiRelevant"),
      "`true` (false for aggregators)",
      SettingPurpose("Whether this module participates in the CI test fan-out."),
      Project,
    )

  val publish: SettingDef[Option[Boolean]] =
    SettingDef.settingDerived(
      SettingName("zipxPublish"),
      "from `publish / skip` (+ `publishArtifact`)",
      SettingPurpose("Force publish on/off; None (default) derives it from publish/skip."),
      Project,
    )

  val testTask: SettingDef[SbtCommand] =
    SettingDef.setting(
      SettingName("zipxTestTask"),
      ModuleNode.DefaultTestTask,
      SettingPurpose(
        "sbt command for Verify: Aggregate root and Graph/Layer per-module (plugin default: testFull)."
      ),
      Project,
    )

  val publishTask: SettingDef[SbtCommand] =
    SettingDef.setting(
      SettingName("zipxPublishTask"),
      ModuleNode.DefaultPublishTask,
      SettingPurpose("sbt command used to publish this module (plugin default: publish)."),
      Project,
    )

  val docker: SettingDef[Boolean] =
    SettingDef.settingDerived(
      SettingName("zipxDocker"),
      "from `DockerPlugin`",
      SettingPurpose("Whether this module publishes a docker image via Docker/publish (default false)."),
      Project,
    )

  // ---- Tasks / inputs ----

  val graph: SettingDef[Unit] =
    SettingDef.task(SettingName("zipxGraph"), SettingPurpose("Print the resolved module graph and topological layers."))

  val publishOrder: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxPublishOrder"),
      SettingPurpose("Print the dependency-ordered publish layers (contracted publish chain)."),
    )

  val workflowGenerate: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxWorkflowGenerate"),
      SettingPurpose("Generate the GitHub Actions workflow YAML from the build graph."),
    )

  val workflowCheck: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxWorkflowCheck"),
      SettingPurpose("Verify the checked-in workflow matches what the build would generate."),
    )

  val actionsPull: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxActionsPull"),
      SettingPurpose("Pull uses: SHA pins from the generated workflow into the action-pins file, then regenerate."),
    )

  val affectedModules: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxAffectedModules"),
      SettingPurpose("Print, as a JSON array, the modules affected by changes since the given git base ref."),
    )

  val buildLevel: List[SettingDef[?]] = List(
    capabilities,
    workflowName,
    workflowPath,
    javaVersion,
    runnerOs,
    scalaMatrix,
    matrixCollapse,
    actions,
    actionsPath,
    dependabotSync,
    scalaSteward,
    stewardGrouping,
    workflowDispatch,
    cache,
    cacheEpoch,
    pushBranches,
    releaseTagPattern,
    affectedOnPR,
    affectedOnPush,
    affectedPublish,
    affectedDeploy,
    skipMergedPrPush,
    cacheRehydrateOnMerge,
    cacheRehydrateTask,
    cacheRehydrateExtraSteps,
    cacheRehydrateEnv,
    env,
    cancelSupersededRuns,
    checkCommandNames,
    verifyClean,
    verifyCleanLabel,
  )

  val projectLevel: List[SettingDef[?]] = List(
    ciRelevant,
    publish,
    docker,
    testTask,
    publishTask,
  )

  val tasks: List[SettingDef[?]] = List(
    workflowGenerate,
    workflowCheck,
    actionsPull,
    graph,
    publishOrder,
    affectedModules,
  )

  /** Every public catalog entry, in docs-friendly order. */
  val all: List[SettingDef[?]] =
    buildLevel ++ projectLevel ++ tasks

  def names: Set[String] = all.map(d => d.name: String).toSet
end ZipxSettings
