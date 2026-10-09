package zipx.core

import zipx.workflow.Cron

/** Catalog of every public `zipx*` autoImport key. Plugin and Settings docs both consume this. */
object ZipxSettings:

  import SettingScope.*

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
        "LocalDir cache epoch strategy: CacheEpoch.ShipCatalog when the catalog has Ship rows, else CacheEpoch.GitTags."
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
        "If zipx-scala-steward.yml is on disk: Fail generate/check (default) or Warn(reason). The replacement is zipx-version-updates.yml (zipxVersionUpdates)."
      ),
      Build,
    )

  val versionUpdates: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxVersionUpdates"),
      true,
      SettingPurpose(
        "Emit .github/workflows/zipx-version-updates.yml: schedule plus dispatch, cs launch zipx-cli catalog update --yes --verify-load, then zipxPinUpdate / zipxCatalogGenerate, opens zipx/version-updates-$GITHUB_RUN_ID labeled clean. Commits everything except repo-root .github/workflows. Nested extra generate is zipxVersionUpdatesExtraSteps. Default true. false deletes the companion."
      ),
      Build,
    )

  val versionUpdatesSchedule: SettingDef[Cron] =
    SettingDef.setting(
      SettingName("zipxVersionUpdatesSchedule"),
      VersionUpdatesWorkflow.DefaultSchedule,
      SettingPurpose(
        "Cron for zipx-version-updates.yml. Default Sunday 00:00 UTC. Use Cron.daily / Cron.weekly / Cron.raw."
      ),
      Build,
    )

  val versionUpdatesPreSteps: SettingDef[Seq[zipx.workflow.Step]] =
    SettingDef.setting(
      SettingName("zipxVersionUpdatesPreSteps"),
      Seq.empty,
      SettingPurpose(
        "Extra steps on the version-updates companion after zipx-sbt-setup and before zipx-cli apply (default empty). Typical use: publishLocal the whole in-dev graph (not only cli/) so cs launch can resolve zipx-cli plus its modules from m2Local."
      ),
      Build,
    )

  val versionUpdatesExtraSteps: SettingDef[Seq[zipx.workflow.Step]] =
    SettingDef.setting(
      SettingName("zipxVersionUpdatesExtraSteps"),
      Seq.empty,
      SettingPurpose(
        "Extra steps on the version-updates companion after zipxCatalogGenerate and before opening the PR (default empty). Typical use: publishLocal an in-dev sbt plugin and zipxWorkflowGenerate a nested example. That tree's .github/workflows/ is not repo-root, so GITHUB_TOKEN can commit it."
      ),
      Build,
    )

  val releaseWorkflow: SettingDef[Option[ReleaseWorkflow]] =
    SettingDef.setting(
      SettingName("zipxReleaseWorkflow"),
      None,
      SettingPurpose(
        "Emit .github/workflows/zipx-release.yml. A tag publishes that ship. A dispatch publishes the ships named in the ships field (default all), plus unreleased in-repo upstreams, in one session and one deployment. Presets: ZipxCentral.releases, ZipxGitHubPackages.releases, ZipxMaven.releases. Default None. None deletes the companion."
      ),
      Build,
    )

  val snapshotRegistries: SettingDef[Seq[ArtifactRegistry]] =
    SettingDef.setting(
      SettingName("zipxSnapshotRegistries"),
      Seq.empty,
      SettingPurpose(
        "Snapshot repositories resolved beside the publish registry while a catalog pin ends in -SNAPSHOT. The publish registry's snapshot repository is already included. A pin names no registry of its own. Default empty."
      ),
      Build,
    )

  val shellWorkflows: SettingDef[Seq[ShellWorkflow]] =
    SettingDef.setting(
      SettingName("zipxShellWorkflows"),
      Seq.empty,
      SettingPurpose(
        "Extra workflow_dispatch workflows. Each job checks the repo out and runs zipx-sbt-setup from this build, then the steps the build supplies. There is no sbt command unless a step runs sbt. Paths under .github/workflows that zipx already owns (ci.yml, the companions) are refused."
      ),
      Build,
    )

  val coverageWorkflow: SettingDef[Option[CoverageWorkflow]] =
    SettingDef.setting(
      SettingName("zipxCoverageWorkflow"),
      None,
      SettingPurpose(
        "Emit .github/workflows/zipx-coverage.yml from Coverage.workflow(triggers): coverage; testFull; coverageAggregate on CoverageTrigger.Scheduled / Dispatch / PrLabel, restoring the build cache and never saving it. Default None. None deletes the companion."
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

  val preRelease: SettingDef[PreRelease] =
    SettingDef.setting(
      SettingName("zipxPreRelease"),
      PreRelease.Skip,
      SettingPurpose(
        "Whether zipxDepUpdate / zipxPinUpdate may list pre-releases (2.1.0-alpha1). Skip (default) stays on the last stable; Include tracks alphas. Actions already skip GitHub prereleases. The scheduled companion uses this setting."
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

  val ships: SettingDef[Seq[PublishedRow]] =
    SettingDef.setting(
      SettingName("zipxShips"),
      Seq.empty,
      SettingPurpose(
        "Ship / ShipGroup rows collected from the ZipxVersions object. Presence is the independent-versioning flag."
      ),
      Build,
    )

  val modverPropagate: SettingDef[ModverPropagate] =
    SettingDef.setting(
      SettingName("zipxModverPropagate"),
      ModverPropagate.MatchBump,
      SettingPurpose(
        "Reverse-dep bump policy across Ship rows. MatchBump (default) floors each published reverse-dep at the triggering kind, so a row re-releases when an upstream row it depends on breaks; PatchPublished patches them; Never is only the lifted+MiMa set; Custom is the whole policy."
      ),
      Build,
    )

  val matrixRoot: SettingDef[Option[ModuleId]] =
    SettingDef.setting(
      SettingName("zipxMatrixRoot"),
      None,
      SettingPurpose(
        "Override the inferred matrix root for this project (Scala-version axes, unusual layouts). Default None."
      ),
      Project,
    )

  val snapshotStatus: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxSnapshotStatus"),
      SettingPurpose(
        "Read the <line>-SNAPSHOT pointer and say whether a catalog pin is current, newer, deleted (snapshots are kept 90 days), or a local build. One artifact name, group:artifact, or no arguments for every snapshot pin. Does not rewrite the pin."
      ),
    )

  val snapshotAdvance: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxSnapshotAdvance"),
      SettingPurpose(
        "Rewrite a commit pin to the sha in the pointer. One artifact name, group:artifact, or no arguments for every commit pin. A dirty pin is refused. Does not run during update, and does not commit."
      ),
    )

  val releasePlan: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxReleasePlan"),
      SettingPurpose(
        "Print which ships can release and which snapshot pins block them. Uploads nothing. No arguments, or all, is every unreleased ship: all refuses when any of them is blocked. A ship name limits the report. shadow prints the post-release paragraph from target/zipx-release-tags.txt."
      ),
    )

  val pinRelease: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxPinRelease"),
      SettingPurpose(
        "Rewrite a commit pin to its own release line after that line is on the release repository. Refuses a missing release, a dirty pin, and a pin that is already a release. Does not jump to a newer line."
      ),
    )

  val modverBump: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxModverBump"),
      SettingPurpose(
        "Rewrite Ship / ShipGroup versions in zipxVersionsFile. No args, or a kind alone, opens every row the release registry already has, at patch unless a kind is named. A ship id rewrites that row even when it is not released. Does not run MiMa."
      ),
    )

  val driftGate: SettingDef[DriftGate] =
    SettingDef.setting(
      SettingName("zipxDriftGate"),
      DriftGate.Fail,
      SettingPurpose(
        "Fail (default) or Warn when a row's release tag is in the clone and its sources have changed. Fail stops that row's compile and fails zipxSnapshotPublish. Warn logs and continues. Snapshot publish confirms the number on the zipxReleaseWorkflow registry (Central, GitHub Packages, CodeArtifact, or any other Maven release URL) and fails closed when that registry cannot be read."
      ),
      Build,
    )

  val modverCompat: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxModverCompat"),
      SettingPurpose(
        "Compile the lifted bump set, run MiMa, write target/zipx-modver-report.json. Fail closed on a missing diff."
      ),
    )

  val modverCheck: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxModverCheck"),
      SettingPurpose(
        "Fail closed on a missing or undersized Ship bump. Reads the min-bump report after zipxModverCompat."
      ),
    )

  val releaseDrift: SettingDef[Seq[String]] =
    SettingDef.taskOf[Seq[String]](
      SettingName("zipxReleaseDrift"),
      SettingPurpose(
        "Released rows with changes since their release tag: each publishes no snapshot until its number moves."
      ),
    )

  val modverSuggest: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxModverSuggest"),
      SettingPurpose(
        "Sticky PR comment with suggested Ship / ShipGroup constructors. Best-effort on forks."
      ),
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
      SettingPurpose("When set with zipxCheckDeps, scalaVersion must match."),
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
      SettingPurpose(
        "Also restrict pushes to modules changed since this workflow's last successful push run on the branch (default false)."
      ),
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
      SettingPurpose(
        "sbt command for the cache-rehydrate job (default Test/compile, so the next PR's test job restores test classes too). Not full Verify."
      ),
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
        "Build-wide job env for normal generated jobs (default empty). Capability/target env overlay this. Omitted on workflow_call callers. Every generated sbt job also sets COURSIER_TTL=0s, and that key wins over this map."
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
        "When zipxVerifyClean is None, prepend cleanFull on PRs that have this label (default Some(\"clean\")). " +
          "None disables. Does not skip the LocalDir restore."
      ),
      Build,
    )

  val cachePurgeLabel: SettingDef[Option[String]] =
    SettingDef.setting(
      SettingName("zipxCachePurgeLabel"),
      Some("purge"),
      SettingPurpose(
        "On a pull_request payload with this label (default Some(\"purge\")), sbt jobs skip the LocalDir restore " +
          "and a save owner still saves a fresh snapshot. None disables."
      ),
      Build,
    )

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

  val imageRefs: SettingDef[Seq[String]] =
    SettingDef.setting(
      SettingName("zipxImageRefs"),
      Seq.empty,
      SettingPurpose(
        "This module's image references, as (Docker / dockerAliases).value.map(_.toString). zipx-deploy.yml checks each with docker manifest inspect and pushes only when one is missing. Required on image modules under DeployTrigger.Manual."
      ),
      Project,
    )

  val deployTrigger: SettingDef[DeployTrigger] =
    SettingDef.setting(
      SettingName("zipxDeployTrigger"),
      DeployTrigger.OnMerge,
      SettingPurpose(
        "OnMerge (default) keeps image pushes and deploys in ci.yml. Manual(images = \"zipx-images\") moves them to a dispatched .github/workflows/zipx-deploy.yml with modules (changed / all / one), target (choose, a target, or a Target.group), and sha inputs. DeployTrigger.staged(deployLabel, skipLabel) runs the same workflow on every merge and on PRs carrying deployLabel, for DeployStage.PreProduction targets only; production deploys only from a dispatch on the default branch."
      ),
      Build,
    )

  val depCleanup: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxDepCleanup"),
      SettingPurpose(
        "Doctor: selected catalog Libs that the Maven graph already pulls. Prints val names. Does not rewrite ZipxVersions."
      ),
    )

  val depCleanupFail: SettingDef[Boolean] =
    SettingDef.setting(
      SettingName("zipxDepCleanupFail"),
      false,
      SettingPurpose("When true, zipxDepCleanup fails if the report is non-empty. Default false (doctor, not a gate)."),
      Build,
    )

  val graph: SettingDef[Unit] =
    SettingDef.task(SettingName("zipxGraph"), SettingPurpose("Print the resolved module graph and topological layers."))

  val publishOrder: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxPublishOrder"),
      SettingPurpose("Print the dependency-ordered publish layers (contracted publish chain)."),
    )

  val catalogGenerate: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxCatalogGenerate"),
      SettingPurpose(
        "Write project/plugins.sbt, project/build.properties, project/zipx-ci.env, and .github/actions composites. Does not write .github/workflows."
      ),
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
        "Local Action pin bumps: GitHub releases + SHA peel + OSV. Rewrites Action constructors after yes. dry-run lists only. The scheduled companion runs this with yes."
      ),
    )

  val affectedModules: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxAffectedModules"),
      SettingPurpose("Print, as a JSON array, the modules affected by changes since the given git base ref."),
    )

  val deployPlan: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxDeployPlan"),
      SettingPurpose(
        "zipx-deploy.yml's resolve step: reads the dispatch inputs and each Environment's last deploys, and writes the plan to target/zipx-deploy/."
      ),
    )

  val imageMissing: SettingDef[Unit] =
    SettingDef.task(
      SettingName("zipxImageMissing"),
      SettingPurpose(
        "Per module: writes true to target/zipx-image-missing when some zipxImageRefs tag is not in its registry."
      ),
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
        "Local outdated pin bumps with approval: lists candidates from zipxPins, rewrites Pin constructors in zipxVersionsFile after yes, then optional feed materialize. dry-run lists only. Ignores PinAction so alert-only feeds can still bump before a PR. The scheduled companion runs this with yes."
      ),
    )

  val depUpdate: SettingDef[Unit] =
    SettingDef.input(
      SettingName("zipxDepUpdate"),
      SettingPurpose(
        "Local catalog bumps with approval: Coursier/Maven lookup of zipxVersions, rewrite of zipxVersionsFile after yes (or an interactive y). dry-run lists only. A commit pin or <line>-SNAPSHOT pointer is not moved to a newer release; the log names zipxPinRelease when that line is on the registry. The scheduled companion runs this with yes."
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
    versionUpdates,
    versionUpdatesSchedule,
    versionUpdatesPreSteps,
    versionUpdatesExtraSteps,
    releaseWorkflow,
    snapshotRegistries,
    shellWorkflows,
    coverageWorkflow,
    pinFeeds,
    pinPrGate,
    preRelease,
    versions,
    pins,
    ships,
    modverPropagate,
    driftGate,
    sbtVersionCoord,
    scalaVersionCoord,
    checkDeps,
    depCleanupFail,
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
    cachePurgeLabel,
    deployTrigger,
  )

  val projectLevel: List[SettingDef[?]] = List(
    ciRelevant,
    publish,
    docker,
    testTask,
    publishTask,
    matrixRoot,
    imageRefs,
  )

  val tasks: List[SettingDef[?]] = List(
    catalogGenerate,
    workflowGenerate,
    workflowCheck,
    advisoryCheck,
    graph,
    depCleanup,
    publishOrder,
    affectedModules,
    deployPlan,
    imageMissing,
    pinCheck,
    pinCheckPr,
    pinSubmit,
    pinInventory,
    pinUpdate,
    depUpdate,
    actionUpdate,
    snapshotStatus,
    snapshotAdvance,
    releasePlan,
    pinRelease,
    modverBump,
    modverCompat,
    modverCheck,
    releaseDrift,
    modverSuggest,
  )

  /** Every public catalog entry, in docs-friendly order. */
  val all: List[SettingDef[?]] =
    buildLevel ++ projectLevel ++ tasks

  def names: Set[String] = all.map(d => d.name: String).toSet
end ZipxSettings
