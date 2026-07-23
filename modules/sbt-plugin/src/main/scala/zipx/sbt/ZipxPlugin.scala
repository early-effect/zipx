package zipx.sbt

import sbt.*
import sbt.Keys.*
import zipx.core.*
import zipx.workflow.Render

/** zipx — the build describes its own GitHub Actions CI.
  *
  * Introspects the sbt build graph (`buildDependencies`, per-project settings) into a [[zipx.core.ModuleGraph]], then
  * uses [[zipx.core.Planner]] to generate a workflow YAML that fans out per-module jobs wired by `needs` derived from
  * the real `dependsOn` graph, with dependency-ordered publishing and commit-stable caching.
  */
object ZipxPlugin extends AutoPlugin:
  override def trigger  = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport:
    // Re-export the core cache-backend ADT so users can write `zipxCache := CacheBackend.LocalDir` etc.
    type CacheBackend = zipx.core.CacheBackend
    val CacheBackend = zipx.core.CacheBackend
    type ActionPins = zipx.core.ActionPins
    val ActionPins = zipx.core.ActionPins
    // Re-export schedule helpers so companion workflows can use typed cron.
    type Cron = zipx.workflow.Cron
    val Cron = zipx.workflow.Cron
    type DayOfWeek = zipx.workflow.DayOfWeek
    val DayOfWeek = zipx.workflow.DayOfWeek

    // Re-export the capability/target model so users can define and append custom capabilities in build.sbt.
    type Capability = zipx.core.Capability
    val Capability = zipx.core.Capability
    type Target = zipx.core.Target
    val Target = zipx.core.Target
    type StepContext = zipx.core.StepContext
    val StepContext = zipx.core.StepContext
    type Phase = zipx.core.Phase
    val Phase = zipx.core.Phase
    type Gate = zipx.core.Gate
    val Gate = zipx.core.Gate
    type JobCondition = zipx.core.JobCondition
    val JobCondition = zipx.core.JobCondition
    type Ordering = zipx.core.Ordering
    val Ordering = zipx.core.Ordering
    type CapabilityScope = zipx.core.CapabilityScope
    val CapabilityScope = zipx.core.CapabilityScope
    type VerifyClean = zipx.core.VerifyClean
    val VerifyClean = zipx.core.VerifyClean
    // Typed env / secret references — prefer these over hand-written "${{ secrets.X }}" strings.
    type EnvValue = zipx.core.EnvValue
    val EnvValue = zipx.core.EnvValue
    val Secret   = zipx.core.Secret
    export zipx.core.EnvValue.secret
    // Early-effect Central paved path (publishSigned + sonaRelease).
    // Nested object: build.sbt only needs Capability from the plugin jar (not zipx-central on the meta classpath).
    object ZipxCentral:
      def release: Capability       = zipx.central.ZipxCentral.release
      def publishSigned: Capability = zipx.central.ZipxCentral.publishSigned
      def releaseOnce: Capability   = zipx.central.ZipxCentral.releaseOnce
      def signingEnv                = zipx.central.ZipxCentral.signingEnv
      def OrgSecretNames            = zipx.central.ZipxCentral.OrgSecretNames
      def gpgImportSteps            = zipx.central.ZipxCentral.gpgImportSteps
    end ZipxCentral
    // Early-effect Specular Pages paved path (org reusable workflow).
    object ZipxDocs:
      def pages(sbtProject: String = "docs", javaVersion: Option[String] = None): Capability =
        zipx.specular.ZipxDocs.pages(sbtProject, javaVersion)
      def ReusableWorkflow = zipx.specular.ZipxDocs.ReusableWorkflow
      def pagesPermissions = zipx.specular.ZipxDocs.pagesPermissions
    // GitHub Packages paved path (token env + packages: write; sbt owns publishTo).
    object ZipxGitHubPackages:
      def sameRepo(
          name: String = zipx.github.ZipxGitHubPackages.DefaultName,
          scope: CapabilityScope = CapabilityScope.Aggregate,
          repository: Option[String] = None,
          condition: Option[JobCondition] = None,
      ): Capability =
        zipx.github.ZipxGitHubPackages.sameRepo(name, scope, repository, condition)
      def sharedRegistry(
          tokenSecret: String = "GH_PACKAGES_TOKEN",
          name: String = zipx.github.ZipxGitHubPackages.DefaultName,
          scope: CapabilityScope = CapabilityScope.Aggregate,
          repository: Option[String] = None,
          condition: Option[JobCondition] = None,
          packagesRepo: Option[String] = None,
          publishOrg: Option[String] = None,
          publishOrgName: Option[String] = None,
      ): Capability =
        zipx.github.ZipxGitHubPackages.sharedRegistry(
          tokenSecret,
          name,
          scope,
          repository,
          condition,
          packagesRepo,
          publishOrg,
          publishOrgName,
        )
      def packagesPermissions = zipx.github.ZipxGitHubPackages.packagesPermissions
      def DefaultName         = zipx.github.ZipxGitHubPackages.DefaultName
      def PublishFlagEnv      = zipx.github.ZipxGitHubPackages.PublishFlagEnv
    end ZipxGitHubPackages
    // The workflow Step type, so extraSteps can be written in build.sbt.
    type Step = zipx.workflow.Step
    val Step = zipx.workflow.Step
    // Typed, IDE-friendly capability constructors that take a real TaskKey/InputKey instead of a command string:
    //   zipxTasks.once("fmt", scalafmtCheckAll)   zipxTasks.deploy(_.id == "svc", promote, targets = ...)
    val zipxTasks = zipx.sbt.CapabilityTasks
    // The cmd"…" interpolator: literal command syntax + typed key splices, e.g. cmd"+ ${testFull}".
    export zipx.sbt.CapabilityTasks.cmd

    // Build-level configuration.
    val zipxCapabilities =
      settingKey[Seq[Capability]]("CI capabilities (default: test, publish, docker?). Append custom ones here.")
    val zipxCache =
      settingKey[CacheBackend]("Cache backend: LocalDir (default), BazelRemoteSidecar, or ManagedRemote.")
    val zipxWorkflowName = settingKey[String]("Name of the generated GitHub Actions workflow.")
    val zipxWorkflowPath =
      settingKey[String]("Workflow file path relative to the build root (default .github/workflows/ci.yml).")
    val zipxJavaVersion  = settingKey[String]("JDK major version for the CI matrix and cache key.")
    val zipxRunnerOs     = settingKey[String]("GitHub Actions runner label (default ubuntu-latest).")
    val zipxScalaMatrix  = settingKey[Boolean]("Expand a per-module Scala matrix over crossScalaVersions.")
    val zipxCacheEpoch   = settingKey[String]("Commit-stable cache epoch (default: version). Rolls on release tags.")
    val zipxPushBranches = settingKey[Seq[String]]("Branches whose pushes trigger CI.")
    val zipxReleaseTagPattern = settingKey[String]("Tag glob that gates publishing.")
    val zipxActions           =
      settingKey[ActionPins](
        "Hash-pinned GitHub Actions (checkout, setup-java, setup-sbt, cache). Override for one-offs; prefer the pin file."
      )
    val zipxActionsPath =
      settingKey[String](
        "Path to the action-pins YAML relative to the build root (default .github/zipx/action-pins.yml). Empty disables file loading."
      )
    val zipxDependabotSync =
      settingKey[Boolean](
        "When true, also generate .github/workflows/zipx-action-pins-sync.yml to sync Dependabot SHA bumps into the pin file."
      )
    val zipxScalaSteward =
      settingKey[Boolean](
        "When true, also generate .github/workflows/zipx-scala-steward.yml (weekly Scala Steward via GITHUB_TOKEN)."
      )
    val zipxWorkflowDispatch =
      settingKey[Boolean]("Emit on.workflow_dispatch so the workflow can be run manually (default false).")

    // Per-project configuration (all default-derived; override only for edge cases).
    val zipxCiRelevant = settingKey[Boolean]("Whether this module participates in the CI test fan-out.")
    val zipxPublish    =
      settingKey[Option[Boolean]]("Force publish on/off; None (default) derives it from publish/skip.")
    val zipxTestTask = settingKey[String](
      "sbt task for Verify: Aggregate root command and Graph/Layer per-module task (default 'test')."
    )
    val zipxPublishTask = settingKey[String]("sbt task used to publish this module (default 'publish').")
    val zipxDocker      =
      settingKey[Boolean]("Whether this module publishes a docker image via Docker/publish (default false).")
    val zipxVerifyClean = settingKey[VerifyClean](
      "Optional clean/cleanFull prepended to every Verify sbt command (default None)."
    )

    val zipxAffectedOnPR =
      settingKey[Boolean]("Whether Verify jobs run only for affected modules on PRs (default true).")
    val zipxAffectedOnPush =
      settingKey[Boolean]("Also restrict pushes to affected modules via the before-sha diff (default false).")
    val zipxSkipMergedPrPush =
      settingKey[Boolean](
        "Skip Verify on branch pushes when the commit already belongs to a merged PR (default true)."
      )

    // Tasks.
    val zipxGraph            = taskKey[Unit]("Print the resolved module graph and topological layers.")
    val zipxPublishOrder     = taskKey[Unit]("Print the dependency-ordered publish layers (contracted publish chain).")
    val zipxWorkflowGenerate = taskKey[Unit]("Generate the GitHub Actions workflow YAML from the build graph.")
    val zipxWorkflowCheck    = taskKey[Unit]("Verify the checked-in workflow matches what the build would generate.")
    val zipxActionsPull      = taskKey[Unit](
      "Pull uses: SHA pins from the generated workflow into the action-pins file, then regenerate."
    )
    val zipxAffectedModules =
      inputKey[Unit]("Print, as a JSON array, the modules affected by changes since the given git base ref.")
  end autoImport

  import autoImport.*

  override def globalSettings: Seq[Setting[?]] = remoteCacheWiring ++ Seq(
    zipxCapabilities      := Seq.empty,
    zipxCache             := CacheBackend.LocalDir,
    zipxWorkflowName      := "CI",
    zipxJavaVersion       := "21",
    zipxRunnerOs          := "ubuntu-latest",
    zipxScalaMatrix       := true,
    zipxPushBranches      := Seq("main"),
    zipxReleaseTagPattern := "v[0-9]+.[0-9]+.[0-9]+",
    zipxWorkflowPath      := ".github/workflows/ci.yml",
    zipxAffectedOnPR      := true,
    zipxAffectedOnPush    := false,
    zipxSkipMergedPrPush  := true,
    zipxVerifyClean       := VerifyClean.None,
    zipxActions           := ActionPins.Defaults,
    zipxActionsPath       := ActionPinFile.DefaultPath,
    zipxDependabotSync    := false,
    zipxScalaSteward      := false,
    zipxWorkflowDispatch  := false,
  )

  /** Wires sbt's remote cache from the environment the generated workflow sets up (`ZIPX_REMOTE_CACHE`,
    * `ZIPX_REMOTE_CACHE_HEADER`). Inert when the env is unset (local dev / LocalDir backend).
    *
    * The gRPC transport (`sbt.plugins.RemoteCachePlugin`) is bundled transitively via sbt-zipx's dependency on
    * `sbt-remote-cache`, and triggers on AllRequirements — but its store is a no-op until `Global / remoteCache` is
    * `Some`, which only happens here when the CI job sets `ZIPX_REMOTE_CACHE`. So local builds are unaffected.
    */
  private def remoteCacheWiring: Seq[Setting[?]] =
    sys.env.get("ZIPX_REMOTE_CACHE").filter(_.nonEmpty) match
      case None         => Nil
      case Some(uriStr) =>
        Seq(
          Global / remoteCache := Some(uri(uriStr)),
          // sbt's content-addressed cache key hashes sources/classpath/scalacOptions but NOT the JDK or OS. For a shared
          // remote cache this is unsafe: a JDK-21 runner and a JDK-17 runner would read each other's blobs. Fold those
          // two axes into `cacheVersion` (mixed into every key) so heterogeneous runners get disjoint partitions.
          // The commit epoch is deliberately excluded — cross-epoch reuse is the whole point of a persistent remote cache.
          Global / cacheVersion := cacheVersionFor(runtimeJdkMajor, runtimeOs),
        ) ++ sys.env.get("ZIPX_REMOTE_CACHE_HEADER").filter(_.nonEmpty).toSeq.map { header =>
          Global / remoteCacheHeaders := Seq(header)
        }

  /** The JDK feature version at runtime (e.g. "21", "17", "1.8"). */
  private def runtimeJdkMajor: String = sys.props.getOrElse("java.specification.version", "unknown")

  /** A coarse OS family, matching what the `actions/cache` key uses for the local backend. */
  private def runtimeOs: String = sys.props.getOrElse("os.name", "unknown").toLowerCase.split(' ').head

  /** A stable 64-bit hash of the cache-correctness axes → `cacheVersion`. Deterministic across machines (FNV-1a over
    * the UTF-8 bytes), so the same (jdk, os) always yields the same partition and different ones never collide by
    * design.
    */
  private def cacheVersionFor(jdk: String, os: String): Long =
    val input = s"jdk=$jdk;os=$os"
    var hash  = 0xcbf29ce484222325L // FNV-1a 64-bit offset basis
    val prime = 0x100000001b3L
    input.getBytes(java.nio.charset.StandardCharsets.UTF_8).foreach { b =>
      hash = (hash ^ (b & 0xff)) * prime
    }
    hash & Long.MaxValue // keep it non-negative for readability in logs

  override def buildSettings: Seq[Setting[?]] = Seq(
    // Note: zipxCacheEpoch's default is NOT set here — it's resolved in planConfig from the root project's `version`
    // (via scope delegation) so a bare `version := ...` (a per-project common setting in sbt 2.0) is honored. An
    // explicit `zipxCacheEpoch := ...` (any scope) still wins.
    zipxGraph        := graphTask.value,
    zipxPublishOrder := publishOrderTask.value,
    // Side-effecting file write → Unit task key + Def.uncached, matching the established sbt-2.x plugin pattern
    // (side effects are not valid cached-task outputs).
    zipxWorkflowGenerate := Def.uncached {
      writeGeneratedWorkflows.value
    },
    zipxWorkflowCheck := checkTask.value,
    zipxActionsPull   := Def.uncached {
      actionsPullTask.value
    },
    zipxAffectedModules := affectedModulesTask.evaluated,
  )

  override def projectSettings: Seq[Setting[?]] = Seq(
    // An aggregator (aggregates ≥1 project) is a container, not a testable/publishable module — off by default.
    // These are plain settings, so users can override per project (e.g. `zipxCiRelevant := true`).
    zipxCiRelevant  := thisProject.value.aggregate.isEmpty,
    zipxPublish     := None,
    zipxTestTask    := "test",
    zipxPublishTask := "publish",
    // Auto-detect: a module opts into the docker capability by enabling sbt-native-packager's DockerPlugin.
    zipxDocker := thisProject.value.autoPlugins.exists(_.label == "com.typesafe.sbt.packager.docker.DockerPlugin"),
  )

  // ---- Build-state → ModuleGraph adapter ---------------------------------------------------------

  /** Project ids that opt out of publishing/CI cannot be read here (they are project-scoped settings); instead we read
    * them per-ref against the loaded structure. This runs inside a task so the settings are resolved.
    */
  private def buildGraph: Def.Initialize[Task[ModuleGraph]] = Def.task {
    val st        = state.value
    val extracted = Project.extract(st)
    val structure = extracted.structure
    val deps      = buildDependencies.value

    // Only projects in the root build unit, sorted by id for determinism.
    val refs = structure.allProjectRefs.sortBy(_.project)

    // Aggregators (aggregate ≥1 project) are containers, not publishable modules — never publish by default.
    val aggregatorIds: Set[String]                 = structure.allProjects.filter(_.aggregate.nonEmpty).map(_.id).toSet
    val resolvedById: Map[String, ResolvedProject] = structure.allProjects.map(p => p.id -> p).toMap
    val buildRoot                                  = (LocalRootProject / baseDirectory).value.toPath

    val nodes = refs.map { ref =>
      def read[A](key: SettingKey[A], default: A): A = extracted.getOpt(ref / key).getOrElse(default)
      // Derive publishing from `publishArtifact` (a Setting, unlike the `publish`/`skip` Tasks), unless the user forces
      // it via `zipxPublish := Some(_)`; aggregators never publish by default. (CI-relevance is defaulted in
      // projectSettings via thisProject.aggregate.)
      val publishes =
        read[Option[Boolean]](zipxPublish, None)
          .getOrElse(!aggregatorIds.contains(ref.project) && read(publishArtifact, true))
      val crossVersions =
        read(crossScalaVersions, Nil) match
          case Nil      => List(read(scalaVersion, "")).filter(_.nonEmpty)
          case versions => versions.toList
      // Module base dir relative to the build root, forward-slashed; "" for the root project itself.
      val baseDir =
        resolvedById
          .get(ref.project)
          .map(p => buildRoot.relativize(p.base.toPath).toString.replace('\\', '/'))
          .getOrElse("")
      ModuleNode(
        id = ref.project,
        dependsOn = deps.classpathRefs(ref).map(_.project).toList.distinct,
        publishes = publishes,
        ciRelevant = read(zipxCiRelevant, true),
        crossScalaVersions = crossVersions,
        testTask = read(zipxTestTask, "test"),
        publishTask = read(zipxPublishTask, "publish"),
        baseDir = baseDir,
        docker = read(zipxDocker, false),
      )
    }.toList

    ModuleGraph(nodes)
  }

  /** The root project's `ProjectRef`. Build-level zipx settings are read from *this* scope, not the task's own
    * (ThisBuild) scope, so they honor every sbt-2.0 assignment form: a bare `zipxX := ...` (a per-project *common*
    * setting), a `ThisBuild / zipxX := ...`, and the plugin's Global default all resolve via project→ThisBuild→Global
    * delegation. A ThisBuild-scoped read would miss the bare/common form (delegation only goes specific→general).
    */
  private def rootRef(structure: sbt.internal.BuildStructure): ProjectRef =
    ProjectRef(structure.root, structure.rootProject(structure.root))

  /** Read a build-level setting from the root project's scope, falling back to `default`. */
  private def readBuildSetting[A](extracted: Extracted, key: SettingKey[A], default: A): A =
    extracted.getOpt(rootRef(extracted.structure) / key).getOrElse(default)

  private def planConfig: Def.Initialize[Task[PlanConfig]] = Def.task {
    val extracted                                  = Project.extract(state.value)
    def read[A](key: SettingKey[A], default: A): A = readBuildSetting(extracted, key, default)
    // Epoch defaults to the root project's `version` (delegates project→ThisBuild→Global) so a bare `version := ...`
    // is honored; an explicit zipxCacheEpoch wins.
    val rootVersion = read(version, "0.0.0")
    val root        = (LocalRootProject / baseDirectory).value
    PlanConfig(
      workflowName = read(zipxWorkflowName, "CI"),
      scalaMatrix = read(zipxScalaMatrix, true),
      javaVersion = read(zipxJavaVersion, "21"),
      runnerOs = read(zipxRunnerOs, "ubuntu-latest"),
      affected = if read(zipxAffectedOnPR, true) then AffectedMode.AffectedOnPR else AffectedMode.Always,
      affectedOnPush = read(zipxAffectedOnPush, false),
      cache = read(zipxCache, CacheBackend.LocalDir),
      cacheEpoch = read(zipxCacheEpoch, rootVersion),
      pushBranches = read(zipxPushBranches, Seq("main")).toList,
      releaseTagPattern = read(zipxReleaseTagPattern, "v[0-9]+.[0-9]+.[0-9]+"),
      actions = resolveActionPins(extracted, root),
      workflowDispatch = read(zipxWorkflowDispatch, false),
      skipMergedPrPush = read(zipxSkipMergedPrPush, true),
      verifyClean = read(zipxVerifyClean, VerifyClean.None),
    )
  }

  /** Resolve pins: explicit `zipxActions` (≠ Defaults) wins; else the pin file when present; else Defaults. */
  private def resolveActionPins(extracted: Extracted, root: File): ActionPins =
    val setting = readBuildSetting(extracted, zipxActions, ActionPins.Defaults)
    if setting != ActionPins.Defaults then setting
    else
      val rel = readBuildSetting(extracted, zipxActionsPath, ActionPinFile.DefaultPath).trim
      if rel.isEmpty then ActionPins.Defaults
      else ActionPinFile.loadOption((root / rel).toPath).getOrElse(ActionPins.Defaults)

  /** The built-in capabilities zipx derives from the graph: Aggregate Verify (root `zipxTestTask`), library publish,
    * plus docker when any module opts in. Clean prefixes come from [[PlanConfig.verifyClean]], not the command string.
    */
  private def builtinCapabilities(graph: ModuleGraph, verifyTask: String): List[Capability] =
    val test = Capability.once(name = "test", command = verifyTask, phase = Phase.Verify, gate = Gate.Always)
    val base = List(test, Capability.publish)
    if graph.nodes.exists(_.docker) then base :+ Capability.docker else base

  private def renderWorkflow: Def.Initialize[Task[String]] = Def.task {
    val graph     = buildGraph.value
    val cfg       = planConfig.value
    val extracted = Project.extract(state.value)
    // Built-in capabilities plus any the user appended via zipxCapabilities (custom stages / deploys), read from the
    // root project's scope so a bare `zipxCapabilities += ...` (a per-project common setting) is honored.
    val userCaps     = readBuildSetting(extracted, zipxCapabilities, Seq.empty)
    val verifyTask   = readBuildSetting(extracted, zipxTestTask, "test")
    val capabilities = combineCapabilities(builtinCapabilities(graph, verifyTask), userCaps.toList)
    ActionPinFile.annotateUses(Render.render(Planner.plan(graph, capabilities, cfg)), cfg.actions)
  }

  private def writeGeneratedWorkflows: Def.Initialize[Task[Unit]] = Def.task {
    val log     = streams.value.log
    val out     = workflowFile.value
    val content = renderWorkflow.value
    IO.write(out, content)
    log.info(s"zipx wrote ${out.getPath}")
    writeSyncWorkflowIfEnabled.value
    writeStewardWorkflowIfEnabled.value
  }

  private def writeSyncWorkflowIfEnabled: Def.Initialize[Task[Unit]] = Def.task {
    val extracted = Project.extract(state.value)
    val enabled   = readBuildSetting(extracted, zipxDependabotSync, false)
    val root      = (LocalRootProject / baseDirectory).value
    val syncFile  = root / ActionPinsSyncWorkflow.DefaultPath
    if enabled then
      val cfg        = planConfig.value
      val actionsRel = readBuildSetting(extracted, zipxActionsPath, ActionPinFile.DefaultPath)
      val wfRel      = readBuildSetting(extracted, zipxWorkflowPath, ".github/workflows/ci.yml")
      val body       = ActionPinsSyncWorkflow.render(
        cfg.actions,
        cfg.javaVersion,
        cfg.runnerOs,
        actionsRel,
        wfRel,
      )
      IO.write(syncFile, body)
      streams.value.log.info(s"zipx wrote ${syncFile.getPath}")
    else if syncFile.exists then
      // Leave an existing file alone when disabled (user may have checked one in manually).
      ()
    end if
  }

  private def writeStewardWorkflowIfEnabled: Def.Initialize[Task[Unit]] = Def.task {
    val extracted   = Project.extract(state.value)
    val enabled     = readBuildSetting(extracted, zipxScalaSteward, false)
    val root        = (LocalRootProject / baseDirectory).value
    val stewardFile = root / ScalaStewardWorkflow.DefaultPath
    if enabled then
      val cfg  = planConfig.value
      val body = ScalaStewardWorkflow.render(cfg.actions, cfg.runnerOs)
      IO.write(stewardFile, body)
      streams.value.log.info(s"zipx wrote ${stewardFile.getPath}")
    else if stewardFile.exists then ()
    end if
  }

  private def actionsPullTask: Def.Initialize[Task[Unit]] = Def.task {
    val log       = streams.value.log
    val extracted = Project.extract(state.value)
    val root      = (LocalRootProject / baseDirectory).value
    val wfFile    = workflowFile.value
    if !wfFile.exists then sys.error(s"${wfFile.getPath} does not exist; nothing to pull from.")
    val rel = readBuildSetting(extracted, zipxActionsPath, ActionPinFile.DefaultPath).trim
    if rel.isEmpty then sys.error("zipxActionsPath is empty; refuse to pull pins without a pin file path.")
    val pinPath = (root / rel).toPath
    val base    = ActionPinFile.loadOption(pinPath).getOrElse(ActionPins.Defaults)
    val pulled  = ActionPinFile.pullFromWorkflow(IO.read(wfFile), base)
    ActionPinFile.write(pinPath, pulled)
    log.info(s"zipx wrote ${pinPath}")
    writeGeneratedWorkflows.value
  }

  /** Merge built-in and user capabilities: a user capability whose `name` matches a built-in *replaces* it (same-name
    * override), so e.g. a user can supply a multi-registry `docker` capability in place of the single-target default
    * without producing duplicate `docker-<module>` jobs. Order follows the built-ins, then any new user capabilities.
    */
  private def combineCapabilities(builtins: List[Capability], user: List[Capability]): List[Capability] =
    val userByName = user.map(c => c.name -> c).toMap
    val merged     = builtins.map(b => userByName.getOrElse(b.name, b))
    val extras     = user.filterNot(u => builtins.exists(_.name == u.name))
    merged ++ extras

  // ---- Tasks -------------------------------------------------------------------------------------

  private def graphTask: Def.Initialize[Task[Unit]] = Def.task {
    val graph = buildGraph.value
    val log   = streams.value.log
    log.info("zipx module graph (dependsOn):")
    graph.nodes.sortBy(_.id).foreach { n =>
      val flags = List(
        if n.publishes then "publishes" else "",
        if n.docker then "docker" else "",
        if !n.ciRelevant then "no-ci" else "",
      ).filter(_.nonEmpty)
      val extra = if flags.isEmpty then "" else s"  [${flags.mkString(", ")}]"
      log.info(s"  ${n.id} -> ${n.dependsOn.sorted.mkString(", ")}$extra")
    }
    log.info("topological layers (roots first):")
    graph.topologicalLayers.zipWithIndex.foreach { (layer, i) =>
      log.info(s"  L$i: ${layer.mkString(", ")}")
    }
  }

  /** Print the dependency-ordered publish layers: the publishing modules with edges contracted through non-publishers,
    * grouped into waves that may publish in parallel. This is the order zipx wires into the release `needs` graph.
    */
  private def publishOrderTask: Def.Initialize[Task[Unit]] = Def.task {
    val graph  = buildGraph.value
    val log    = streams.value.log
    val layers = graph.subsetLayers(_.publishes)
    if layers.isEmpty then log.info("zipx: no publishing modules.")
    else
      log.info("zipx publish order (each layer may publish in parallel; layer N needs layer N-1):")
      layers.zipWithIndex.foreach { (layer, i) =>
        log.info(s"  L$i: ${layer.mkString(", ")}")
      }
  }

  /** The absolute workflow file, resolved against the build root. `baseDirectory` is a task in sbt 2.x, so this must be
    * a task too (a setting cannot depend on it).
    */
  private def workflowFile: Def.Initialize[Task[File]] = Def.task {
    (LocalRootProject / baseDirectory).value / zipxWorkflowPath.value
  }

  private def checkTask: Def.Initialize[Task[Unit]] = Def.task {
    val out      = workflowFile.value
    val expected = renderWorkflow.value
    val actual   = if out.exists then IO.read(out) else ""
    if actual != expected then
      sys.error(
        s"${out.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result."
      )
    streams.value.log.info(s"zipx: ${out.getPath} is up to date.")
    val extracted = Project.extract(state.value)
    if readBuildSetting(extracted, zipxDependabotSync, false) then
      val root         = (LocalRootProject / baseDirectory).value
      val syncFile     = root / ActionPinsSyncWorkflow.DefaultPath
      val cfg          = planConfig.value
      val actionsRel   = readBuildSetting(extracted, zipxActionsPath, ActionPinFile.DefaultPath)
      val wfRel        = readBuildSetting(extracted, zipxWorkflowPath, ".github/workflows/ci.yml")
      val expectedSync = ActionPinsSyncWorkflow.render(
        cfg.actions,
        cfg.javaVersion,
        cfg.runnerOs,
        actionsRel,
        wfRel,
      )
      val actualSync = if syncFile.exists then IO.read(syncFile) else ""
      if actualSync != expectedSync then
        sys.error(
          s"${syncFile.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result."
        )
      streams.value.log.info(s"zipx: ${syncFile.getPath} is up to date.")
    end if
    if readBuildSetting(extracted, zipxScalaSteward, false) then
      val root            = (LocalRootProject / baseDirectory).value
      val stewardFile     = root / ScalaStewardWorkflow.DefaultPath
      val cfg             = planConfig.value
      val expectedSteward = ScalaStewardWorkflow.render(cfg.actions, cfg.runnerOs)
      val actualSteward   = if stewardFile.exists then IO.read(stewardFile) else ""
      if actualSteward != expectedSteward then
        sys.error(
          s"${stewardFile.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result."
        )
      streams.value.log.info(s"zipx: ${stewardFile.getPath} is up to date.")
    end if
  }

  /** `zipxAffectedModules <base-ref>` — diff against the base ref, map changed files to owning modules, expand the
    * reverse-dependency closure, and write the affected module ids as a JSON array to
    * `<base>/target/zipx-affected.json` (also printed for local use). The generated workflow's `affected` job reads
    * that stable path so sbt's log lines never pollute `GITHUB_OUTPUT`. (Do not use `(target).value` — under sbt 2 it
    * is a versioned `target/out/...` tree.)
    */
  private def affectedModulesTask: Def.Initialize[InputTask[Unit]] =
    Def.inputTask {
      val base    = sbt.complete.DefaultParsers.trimmed(sbt.complete.DefaultParsers.any.*.string).parsed.trim
      val graph   = buildGraph.value
      val root    = (LocalRootProject / baseDirectory).value
      val baseRef = if base.isEmpty then "HEAD^" else base
      val changed = gitDiffNames(root, baseRef)
      val modules = Affected.affectedModules(graph, changed).toList.sorted
      val json    = jsonArray(modules)
      val out     = root / "target" / "zipx-affected.json"
      IO.write(out, json + "\n")
      println(json)
    }

  /** Files changed on HEAD since its merge-base with `baseRef` (three-dot diff), repo-root-relative with forward
    * slashes. Returns empty on any git failure (caller treats an empty change set as "nothing affected").
    */
  private def gitDiffNames(root: File, baseRef: String): List[String] =
    try
      val lines = scala.collection.mutable.ListBuffer.empty[String]
      val code  =
        scala.sys.process
          .Process(Seq("git", "diff", "--name-only", s"$baseRef...HEAD"), root)
          .!(scala.sys.process.ProcessLogger(lines += _, _ => ()))
      if code == 0 then lines.map(_.trim).filter(_.nonEmpty).toList else Nil
    catch case scala.util.control.NonFatal(_) => Nil

  private def jsonArray(items: List[String]): String =
    items.map(s => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").mkString("[", ",", "]")

end ZipxPlugin
