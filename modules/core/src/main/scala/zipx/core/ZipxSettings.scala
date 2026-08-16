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
        "Hash-pinned GitHub Actions. Override for one-offs; catalog Action vals overlay jar Defaults. See Action pins."
      ),
      Build,
    )

  val actionsPath: SettingDef[String] =
    SettingDef.setting(
      SettingName("zipxActionsPath"),
      ActionPinFile.DefaultPath,
      SettingPurpose(
        "Legacy pin YAML path. If this file exists, generate fails (paste Action vals). Not an input."
      ),
      Build,
    )

  val actionRows: SettingDef[Seq[Action]] =
    SettingDef.setting(
      SettingName("zipxActionRows"),
      Seq.empty,
      SettingPurpose(
        "Action rows collected from the ZipxVersions object (every Action val). Overlay onto ActionPins.Defaults."
      ),
      Build,
    )

  val verify: SettingDef[ZipxVerify] =
    SettingDef.setting(
      SettingName("zipxVerify"),
      ZipxVerify.Strict,
      SettingPurpose(
        "Parallel Verify gates: fmt, workflow-check, advisories. Default Strict (all On). Skip(reason) still emits the job."
      ),
      Build,
    )

  val leftoverSteward: SettingDef[LeftoverOpt] =
    SettingDef.setting(
      SettingName("zipxLeftoverSteward"),
      LeftoverOpt.Fail,
      SettingPurpose(
        "If zipx-scala-steward.yml is on disk: Fail generate/check (default) or Warn(reason). Does not generate a bot workflow."
      ),
      Build,
    )

  val pinFeeds: SettingDef[Seq[PinFeed]] =
    SettingDef.setting(
      SettingName("zipxPinFeeds"),
      Seq.empty,
      SettingPurpose(
        "Pin feeds zipx orchestrates (CDN/sha256 pins, later Docker/JDK). Empty by default. zipx owns Ignore/Report/Update policy and OSV; inventory is catalog Pin vals. See Pin feeds."
      ),
      Build,
    )

  val pinPrGate: SettingDef[PinPrGate] =
    SettingDef.setting(
      SettingName("zipxPinPrGate"),
      PinPrGate.All,
      SettingPurpose(
        "PR pin-feed advisory gate inside zipxAdvisoryCheck: All (default), Introduced (new or version-changed vs the PR base), or Off (skip pin OSV; ZipxVerify.advisories Skip turns the whole job off)."
      ),
      Build,
    )

  val versions: SettingDef[Seq[ZipxCoord]] =
    SettingDef.setting(
      SettingName("zipxVersions"),
      Seq.empty,
      SettingPurpose(
        "Lib / Plugin rows collected from the ZipxVersions object (every val). Empty skips catalog generate. See Versions."
      ),
      Build,
    )

  val pins: SettingDef[Seq[Pin]] =
    SettingDef.setting(
      SettingName("zipxPins"),
      Seq.empty,
      SettingPurpose(
        "Pin rows collected from the ZipxVersions object (every Pin val). Inventory for zipxPinFeeds. See Pin feeds."
      ),
      Build,
    )

  val sbtVersionCoord: SettingDef[Option[SbtVersion]] =
    SettingDef.setting(
      SettingName("zipxSbt"),
      None,
      SettingPurpose("When set, zipx generates project/build.properties from this sbt version."),
      Build,
    )

  val scalaVersionCoord: SettingDef[Option[ScalaVersion]] =
    SettingDef.setting(
      SettingName("zipxScala"),
      None,
      SettingPurpose("When set with zipxCheckDeps, ThisBuild / scalaVersion must match."),
      Build,
    )

  val checkDeps: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxCheckDeps"),
      false,
      SettingPurpose(
        "Fail generate/check when libraryDependencies contain a GAV that is not a Lib in zipxVersions, or when zipxScala does not match scalaVersion."
      ),
      Build,
    )

  val emitSelf: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxEmitSelf"),
      true,
      SettingPurpose(
        "When true, generated project/plugins.sbt starts with the loaded sbt-zipx GAV. Dogfood sets false (zipx is loaded from source)."
      ),
      Build,
    )

  val pluginVersion: SettingDef[Option[String]] =
    SettingDef.setting(
      SettingName("zipxPluginVersion"),
      None,
      SettingPurpose(
        "Override the sbt-zipx version written when zipxEmitSelf is true. Scripted sets this via -Dplugin.version; dogfood leaves it empty."
      ),
      Build,
    )

  val selfPlugins: SettingDef[Seq[Plugin]] =
    SettingDef.setting(
      SettingName("zipxSelfPlugins"),
      Seq.empty,
      SettingPurpose(
        "Loaded sbt plugins (besides sbt-zipx) that generate writes into project/plugins.sbt from the classpath version. A plugin that sits on zipx appends with ZipxSelf.emit. See Extending Versions."
      ),
      Build,
    )

  val versionsFile: SettingDef[String] =
    SettingDef.setting(
      SettingName("zipxVersionsFile"),
      ZipxCatalog.DefaultVersionsFile,
      SettingPurpose("Catalog source zipxDepUpdate and zipxPinUpdate rewrite (default project/ZipxVersions.scala)."),
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

  val advisoryCheck: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxAdvisoryCheck"),
      SettingPurpose(
        "OSV on catalog Libs, Action pins, and Pin vals. Fails on findings at or above min-severity. See Verify."
      ),
    )

  val actionUpdate: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxActionUpdate"),
      SettingPurpose(
        "Local Action pin bumps: GitHub releases + SHA peel + OSV. Rewrites Action constructors after yes. dry-run lists only."
      ),
    )

  val affectedModules: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxAffectedModules"),
      SettingPurpose("Print, as a JSON array, the modules affected by changes since the given git base ref."),
    )

  val pinCheck: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxPinCheck"),
      SettingPurpose(
        "Scheduled pin-feed check: outdated lookup plus OSV. Applies under PinAction.Update. Non-zero exit on Report findings."
      ),
    )

  val pinCheckPr: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxPinCheckPr"),
      SettingPurpose(
        "PR pin-check: OSV on current inventory (Introduced diffs vs ZIPX_PIN_BASE_SHA). Never applies or submits a snapshot."
      ),
    )

  val pinSubmit: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxPinSubmit"),
      SettingPurpose(
        "Submit a GitHub dependency snapshot for feeds with submitSnapshot. Default-branch companion only."
      ),
    )

  val pinInventory: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxPinInventory"),
      SettingPurpose(
        "Write target/zipx-pin-inventory.json of current pin-feed inventory (used by Introduced at the PR base SHA)."
      ),
    )

  val pinUpdate: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxPinUpdate"),
      SettingPurpose(
        "Local outdated pin bumps with approval: lists candidates from zipxPins, rewrites Pin constructors in zipxVersionsFile after yes, then optional feed materialize. dry-run lists only. Ignores PinAction so alert-only feeds can still bump before a PR."
      ),
    )

  val depUpdate: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxDepUpdate"),
      SettingPurpose(
        "Local catalog bumps with approval: Coursier/Maven lookup of zipxVersions, rewrite of zipxVersionsFile after yes (or an interactive y). dry-run lists only."
      ),
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
    actionRows,
    verify,
    leftoverSteward,
    pinFeeds,
    pinPrGate,
    versions,
    pins,
    sbtVersionCoord,
    scalaVersionCoord,
    checkDeps,
    emitSelf,
    pluginVersion,
    selfPlugins,
    versionsFile,
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
    advisoryCheck,
    graph,
    publishOrder,
    affectedModules,
    pinCheck,
    pinCheckPr,
    pinSubmit,
    pinInventory,
    pinUpdate,
    depUpdate,
    actionUpdate,
  )

  /** Every public catalog entry, in docs-friendly order. */
  val all: List[SettingDef[?]] =
    buildLevel ++ projectLevel ++ tasks

  def names: Set[String] = all.map(d => d.name: String).toSet
end ZipxSettings
