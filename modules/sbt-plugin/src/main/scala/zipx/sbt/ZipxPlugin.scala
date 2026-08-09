package zipx.sbt

import sbt.*
import sbt.Keys.*
import zipx.core.*
import zipx.workflow.Render
import zipx.workflow.Step

/** zipx: the build describes its own GitHub Actions CI.
  *
  * Introspects the sbt build graph (`buildDependencies`, per-project settings) into a [[zipx.core.ModuleGraph]], then
  * uses [[zipx.core.Planner]] to generate a workflow YAML that fans out per-module jobs wired by `needs` derived from
  * the real `dependsOn` graph, with dependency-ordered publishing and commit-stable caching.
  */
object ZipxPlugin extends AutoPlugin:
  override def trigger  = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport:
    type CacheBackend = zipx.core.CacheBackend
    val CacheBackend = zipx.core.CacheBackend
    type CacheEpoch = zipx.core.CacheEpoch
    val CacheEpoch = zipx.core.CacheEpoch
    type ActionPins = zipx.core.ActionPins
    val ActionPins = zipx.core.ActionPins
    type Cron = zipx.workflow.Cron
    val Cron = zipx.workflow.Cron
    type DayOfWeek = zipx.workflow.DayOfWeek
    val DayOfWeek = zipx.workflow.DayOfWeek
    type StewardGroup = zipx.core.StewardGroup
    val StewardGroup = zipx.core.StewardGroup
    type StewardFilter = zipx.core.StewardFilter
    val StewardFilter      = zipx.core.StewardFilter
    val ScalaStewardConfig = zipx.core.ScalaStewardConfig

    type Capability = zipx.core.Capability
    val Capability = zipx.core.Capability
    type Target = zipx.core.Target
    val Target = zipx.core.Target
    type StepContext = zipx.core.StepContext
    val StepContext = zipx.core.StepContext

    /** Whether a capability's targets each get a job or all share one. A build names it when it passes `targetFanOut`
      * to `Capability.custom`; `Capability.withSharedTargets` / `withTargets` set it without naming it, which is the
      * shorter path.
      */
    type TargetFanOut = zipx.core.TargetFanOut
    val TargetFanOut = zipx.core.TargetFanOut

    /** The names that become GitHub job ids, so a `build.sbt` can write one: `CapabilityName("docker-stg")`,
      * `Target(TargetName("stg"))`. Both are validated at compile time when the argument is a literal, which is the
      * usual case in a build file.
      */
    type CapabilityName = zipx.core.CapabilityName
    val CapabilityName = zipx.core.CapabilityName
    type TargetName = zipx.core.TargetName
    val TargetName = zipx.core.TargetName

    /** The validated settings types: see [[zipxWorkflowName]], [[zipxJavaVersion]] and [[zipxRunnerOs]]. A build names
      * one when it overrides the setting, `zipxJavaVersion := JdkVersion("17")`, and gets the check at the point of
      * writing rather than at generate time.
      *
      * The JDK one is `JdkVersion` and not `JavaVersion` because sbt 2.0 exports a `sbt.JavaVersion`: with both in a
      * `build.sbt`'s scope, naming it would be an ambiguous reference rather than a shadow.
      */
    type WorkflowName = zipx.core.WorkflowName
    val WorkflowName = zipx.core.WorkflowName
    type JdkVersion = zipx.core.JdkVersion
    val JdkVersion = zipx.core.JdkVersion
    type RunnerOs = zipx.core.RunnerOs
    val RunnerOs = zipx.core.RunnerOs

    /** Exported under its own name rather than as `Command`, which is sbt's own name in a `build.sbt` (see the note at
      * the end of this object). A `Capability`'s `command` is this type, so a build that writes one literally needs it.
      */
    type SbtCommand = zipx.core.SbtCommand
    val SbtCommand = zipx.core.SbtCommand
    type SbtCommandText = zipx.core.SbtCommandText
    val SbtCommandText = zipx.core.SbtCommandText
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
    type EnvValue = zipx.core.EnvValue
    val EnvValue = zipx.core.EnvValue
    val Secret   = zipx.core.Secret
    export zipx.core.EnvValue.secret

    /** Nested object rather than a re-exported type: this way a `build.sbt` needs only `Capability` from the plugin
      * jar, not `zipx-central` on the meta classpath.
      */
    object ZipxCentral:
      def release: Capability       = zipx.central.ZipxCentral.release
      def publishSigned: Capability = zipx.central.ZipxCentral.publishSigned
      def releaseOnce: Capability   = zipx.central.ZipxCentral.releaseOnce
      def signingEnv                = zipx.central.ZipxCentral.signingEnv
      def OrgSecretNames            = zipx.central.ZipxCentral.OrgSecretNames
      def gpgImportSteps            = zipx.central.ZipxCentral.gpgImportSteps
    end ZipxCentral

    /** The AWS pack. The newtypes are re-exported as `type` + `val` pairs rather than hidden behind factories, because
      * a `build.sbt` writes `EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1"))` as a literal and that
      * is exactly where the `inline apply` check earns its keep.
      */
    object ZipxAws:
      type EcrRegistry = zipx.aws.EcrRegistry
      val EcrRegistry = zipx.aws.EcrRegistry
      type EcrImage = zipx.aws.EcrImage
      val EcrImage = zipx.aws.EcrImage
      type AwsAccountId = zipx.aws.AwsAccountId
      val AwsAccountId = zipx.aws.AwsAccountId
      type AwsRegion = zipx.aws.AwsRegion
      val AwsRegion = zipx.aws.AwsRegion
      type EcrRepository = zipx.aws.EcrRepository
      val EcrRepository = zipx.aws.EcrRepository
      type ImageTag = zipx.aws.ImageTag
      val ImageTag = zipx.aws.ImageTag

      def oidcLoginSteps: Steps                                                     = zipx.aws.ZipxAws.oidcLoginSteps
      def ecrLoginSteps: Steps                                                      = zipx.aws.ZipxAws.ecrLoginSteps
      def oidcPermissions                                                           = zipx.aws.ZipxAws.oidcPermissions
      def registryEnv(registry: EcrRegistry, role: EnvValue): Map[String, EnvValue] =
        zipx.aws.ZipxAws.registryEnv(registry, role)
      def imageEnv(image: EcrImage, role: EnvValue): Map[String, EnvValue] =
        zipx.aws.ZipxAws.imageEnv(image, role)
      def registryTargets(registries: List[(TargetName, EcrRegistry, EnvValue)]): List[Target] =
        zipx.aws.ZipxAws.registryTargets(registries)
      def dockerPublish(
          registry: EcrRegistry,
          role: EnvValue,
          name: CapabilityName = Capability.DockerName,
          scope: CapabilityScope = CapabilityScope.Aggregate,
          condition: Option[JobCondition] = None,
      ): Capability =
        zipx.aws.ZipxAws.dockerPublish(registry, role, name, scope, condition)
      def sharedLoginSteps: Steps = zipx.aws.ZipxAws.sharedLoginSteps

      /** Several registries pushed from **one** job, the shape to prefer for a multi-registry image: see
        * `TargetFanOut`.
        */
      def dockerPublishAll(
          registries: List[(TargetName, EcrRegistry, EnvValue)],
          name: CapabilityName = Capability.DockerName,
          scope: CapabilityScope = CapabilityScope.Aggregate,
          condition: Option[JobCondition] = None,
      ): Capability =
        zipx.aws.ZipxAws.dockerPublishAll(registries, name, scope, condition)
      def RoleEnv                             = zipx.aws.ZipxAws.RoleEnv
      def RegionEnv                           = zipx.aws.ZipxAws.RegionEnv
      def RegistryEnv                         = zipx.aws.ZipxAws.RegistryEnv
      def CredentialsPinKey                   = zipx.aws.ZipxAws.CredentialsPinKey
      def EcrLoginPinKey                      = zipx.aws.ZipxAws.EcrLoginPinKey
      def credentialsAction(pins: ActionPins) = zipx.aws.ZipxAws.credentialsAction(pins)
      def DefaultCredentialsAction            = zipx.aws.ZipxAws.DefaultCredentialsAction
    end ZipxAws

    object ZipxDocs:
      def pages(sbtProject: String = "docs", javaVersion: Option[JdkVersion] = None): Capability =
        zipx.specular.ZipxDocs.pages(sbtProject, javaVersion)
      def ReusableWorkflow = zipx.specular.ZipxDocs.ReusableWorkflow
      def pagesPermissions = zipx.specular.ZipxDocs.pagesPermissions
      def deployWhen       = zipx.specular.ZipxDocs.deployWhen
      def DocsName         = zipx.specular.ZipxDocs.DocsName
    end ZipxDocs

    object ZipxGitHubPackages:
      def sameRepo(
          name: CapabilityName = zipx.github.ZipxGitHubPackages.DefaultName,
          scope: CapabilityScope = CapabilityScope.Aggregate,
          condition: Option[JobCondition] = None,
      ): Capability =
        zipx.github.ZipxGitHubPackages.sameRepo(name, scope, condition)
      def sharedRegistry(
          token: EnvValue = zipx.core.EnvValue.secret("GH_PACKAGES_TOKEN"),
          name: CapabilityName = zipx.github.ZipxGitHubPackages.DefaultName,
          scope: CapabilityScope = CapabilityScope.Aggregate,
          condition: Option[JobCondition] = None,
          packagesRepo: Option[String] = None,
          publishOrg: Option[String] = None,
          publishOrgName: Option[String] = None,
      ): Capability =
        zipx.github.ZipxGitHubPackages.sharedRegistry(
          token,
          name,
          scope,
          condition,
          packagesRepo,
          publishOrg,
          publishOrgName,
        )
      def packagesPermissions = zipx.github.ZipxGitHubPackages.packagesPermissions
      def DefaultName         = zipx.github.ZipxGitHubPackages.DefaultName
      def PublishFlagEnv      = zipx.github.ZipxGitHubPackages.PublishFlagEnv
    end ZipxGitHubPackages
    type Step = zipx.workflow.Step
    val Step = zipx.workflow.Step
    type Expr = zipx.workflow.Expr
    val Expr = zipx.workflow.Expr
    type Steps = zipx.core.Steps
    val Steps = zipx.core.Steps

    type Script = zipx.shell.Script
    val Script = zipx.shell.Script
    type Word = zipx.shell.Word
    val Word = zipx.shell.Word
    val Exec = zipx.shell.Exec
    export zipx.shell.sh

    type ShTest = zipx.shell.ShTest
    val ShTest = zipx.shell.ShTest
    type Block = zipx.shell.Block
    val Block       = zipx.shell.Block
    val If          = zipx.shell.If
    val ForIn       = zipx.shell.ForIn
    val While       = zipx.shell.While
    val Assign      = zipx.shell.Assign
    val VarName     = zipx.shell.VarName
    val GlobPattern = zipx.shell.GlobPattern
    val Raw         = zipx.shell.Raw
    val RawLine     = zipx.shell.RawLine
    // `zipx.shell.Command` and `InlineCommand` stay unexported: `Command` is sbt's own name in a `build.sbt`
    // (`commands += Command.command(…)`), and shadowing it would break that.

    val zipxTasks = zipx.sbt.CapabilityTasks
    export zipx.sbt.CapabilityTasks.cmd

    val zipxCapabilities =
      settingKey[Seq[Capability]]("CI capabilities (default: test, publish, docker?). Append custom ones here.")
    val zipxCache =
      settingKey[CacheBackend]("Cache backend: LocalDir (default), BazelRemoteSidecar, or ManagedRemote.")
    val zipxWorkflowName = settingKey[WorkflowName]("Name of the generated GitHub Actions workflow.")
    val zipxWorkflowPath =
      settingKey[String]("Workflow file path relative to the build root (default .github/workflows/ci.yml).")
    val zipxJavaVersion = settingKey[JdkVersion]("JDK major version for the CI matrix and cache key.")
    val zipxRunnerOs    = settingKey[RunnerOs]("GitHub Actions runner label (default ubuntu-latest).")
    val zipxScalaMatrix = settingKey[Boolean]("Expand a per-module Scala matrix over crossScalaVersions.")
    val zipxCacheEpoch  =
      settingKey[CacheEpoch](
        "LocalDir cache epoch strategy (default CacheEpoch.GitTags). Use CacheEpoch.Fixed(version.value) to bake at generate time."
      )
    val zipxPushBranches      = settingKey[Seq[String]]("Branches whose pushes trigger CI.")
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
    val zipxStewardGrouping =
      settingKey[Seq[StewardGroup]](
        "Scala Steward pullRequests.grouping written to .github/.scala-steward.conf so updates land in a few PRs " +
          "instead of one each (default ScalaStewardConfig.Defaults). Empty disables the config file. " +
          "Set it here, not in the repo's .scala-steward.conf: this list is matched first and ends in a catch-all."
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
    val zipxVerifyCleanLabel =
      settingKey[Option[String]](
        "When zipxVerifyClean is None, prepend cleanFull on PRs that have this label (default Some(\"clean\")). " +
          "None disables. One-off cache bust."
      )

    val zipxAffectedOnPR =
      settingKey[Boolean]("Whether Verify jobs run only for affected modules on PRs (default true).")
    val zipxAffectedOnPush =
      settingKey[Boolean]("Also restrict pushes to affected modules via the before-sha diff (default false).")
    val zipxAffectedPublish =
      settingKey[Boolean](
        "Also affected-gate Graph-scope Publish jobs, so one changed module does not rebuild every image " +
          "(default false; release tags always publish everything). Separate from zipxAffectedOnPR because " +
          "under-verifying is silently unsafe while under-publishing is loudly broken."
      )
    val zipxSkipMergedPrPush =
      settingKey[Boolean](
        "Skip Verify on branch pushes when the commit already belongs to a merged PR (default true)."
      )
    val zipxCacheRehydrateOnMerge =
      settingKey[Boolean](
        "On merged-PR pushes (when skipMergedPrPush skips Verify), run a minimal LocalDir cache-rehydrate job " +
          "so the default branch gets an actions/cache save for later PRs (default true; inert for remote caches)."
      )
    val zipxCacheRehydrateTask =
      settingKey[String](
        "sbt command for the cache-rehydrate job (default compile). Not full Verify."
      )
    val zipxCacheRehydrateExtraSteps =
      settingKey[StepContext => List[Step]](
        "Optional steps on cache-rehydrate after LocalDir restore and before the rehydrate task (default empty). " +
          "Not copied from Verify capabilities."
      )
    val zipxCacheRehydrateEnv =
      settingKey[Map[String, EnvValue]](
        "Optional env for the cache-rehydrate job only (default empty). Overlay on zipxEnv."
      )
    val zipxEnv =
      settingKey[Map[String, EnvValue]](
        "Build-wide job env for normal generated jobs (default empty). Capability/target env overlay this. Omitted on workflow_call callers."
      )
    val zipxCancelSupersededRuns =
      settingKey[Boolean](
        "Emit workflow concurrency so a new push cancels an in-flight run on the same ref (default true). " +
          "Release-tag runs are never cancelled."
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
    zipxCapabilities             := Seq.empty,
    zipxCache                    := CacheBackend.LocalDir,
    zipxCacheEpoch               := CacheEpoch.GitTags(),
    zipxWorkflowName             := PlanConfig.DefaultWorkflowName,
    zipxJavaVersion              := PlanConfig.DefaultJdkVersion,
    zipxRunnerOs                 := PlanConfig.DefaultRunnerOs,
    zipxScalaMatrix              := true,
    zipxPushBranches             := Seq("main"),
    zipxReleaseTagPattern        := "v[0-9]+.[0-9]+.[0-9]+",
    zipxWorkflowPath             := ".github/workflows/ci.yml",
    zipxAffectedOnPR             := true,
    zipxAffectedOnPush           := false,
    zipxAffectedPublish          := false,
    zipxSkipMergedPrPush         := true,
    zipxCacheRehydrateOnMerge    := true,
    zipxCacheRehydrateTask       := "compile",
    zipxCacheRehydrateExtraSteps := (_ => Nil),
    zipxCacheRehydrateEnv        := Map.empty,
    zipxEnv                      := Map.empty,
    zipxCancelSupersededRuns     := true,
    zipxVerifyClean              := VerifyClean.None,
    zipxVerifyCleanLabel         := Some("clean"),
    zipxActions                  := ActionPins.Defaults,
    zipxActionsPath              := ActionPinFile.DefaultPath,
    zipxDependabotSync           := false,
    zipxScalaSteward             := false,
    zipxStewardGrouping          := ScalaStewardConfig.Defaults,
    zipxWorkflowDispatch         := false,
  )

  /** Wires sbt's remote cache from the environment the generated workflow sets up, and is inert when that env is unset:
    * the bundled gRPC transport (`sbt.plugins.RemoteCachePlugin`) triggers on AllRequirements but no-ops until
    * `Global / remoteCache` is `Some`, so local builds are unaffected.
    */
  private def remoteCacheWiring: Seq[Setting[?]] =
    sys.env.get(RemoteCacheProof.envUri).filter(_.nonEmpty) match
      case None         => Nil
      case Some(uriStr) =>
        Seq(
          Global / remoteCache  := Some(uri(uriStr)),
          Global / cacheVersion := cacheVersionFor(runtimeJdkMajor, runtimeOs),
        ) ++ sys.env.get(RemoteCacheProof.envHeader).filter(_.nonEmpty).toSeq.map { header =>
          Global / remoteCacheHeaders := Seq(header)
        }

  private def runtimeJdkMajor: String = sys.props.getOrElse("java.specification.version", "unknown")

  private def runtimeOs: String = sys.props.getOrElse("os.name", "unknown").toLowerCase.split(' ').head

  /** Partitions the remote cache by the two axes sbt's own content-addressed key omits. sbt hashes sources, classpath
    * and scalacOptions but not the JDK or the OS, so without this a JDK-21 runner and a JDK-17 runner would read each
    * other's blobs. The commit epoch is deliberately not an axis: cross-epoch reuse is the point of a persistent cache.
    *
    * FNV-1a over the UTF-8 bytes, so the same (jdk, os) hashes the same on every machine.
    */
  private def cacheVersionFor(jdk: String, os: String): Long =
    val FnvOffsetBasis = 0xcbf29ce484222325L
    val FnvPrime       = 0x100000001b3L
    var hash           = FnvOffsetBasis
    s"jdk=$jdk;os=$os".getBytes(java.nio.charset.StandardCharsets.UTF_8).foreach { b =>
      hash = (hash ^ (b & 0xff)) * FnvPrime
    }
    hash & Long.MaxValue

  override def buildSettings: Seq[Setting[?]] = Seq(
    zipxGraph        := graphTask.value,
    zipxPublishOrder := publishOrderTask.value,
    // `Def.uncached` because a file write is not a valid cached-task output.
    zipxWorkflowGenerate := Def.uncached {
      writeGeneratedWorkflows.value
    },
    zipxWorkflowCheck := checkTask.value,
    zipxActionsPull   := Def.uncached {
      actionsPullTask.value
    },
    zipxAffectedModules := affectedModulesTask.evaluated,
  )

  /** An aggregator is a container rather than a testable module, so it is CI-irrelevant by default. Plain settings, so
    * a project can override any of them.
    */
  override def projectSettings: Seq[Setting[?]] = Seq(
    zipxCiRelevant  := thisProject.value.aggregate.isEmpty,
    zipxPublish     := None,
    zipxTestTask    := "test",
    zipxPublishTask := "publish",
    zipxDocker      := thisProject.value.autoPlugins.exists(_.label == DockerPluginLabel),
  )

  /** A module opts into the docker capability by enabling sbt-native-packager's `DockerPlugin`, detected by label so
    * zipx needs no dependency on it.
    */
  private inline val DockerPluginLabel = "com.typesafe.sbt.packager.docker.DockerPlugin"

  /** The loaded build as a [[ModuleGraph]]. A task rather than a setting: the per-project settings it reads are
    * resolved per-ref against the loaded structure.
    */
  private def buildGraph: Def.Initialize[Task[ModuleGraph]] = Def.task {
    val st        = state.value
    val extracted = Project.extract(st)
    val structure = extracted.structure
    val deps      = buildDependencies.value

    val refsSortedForDeterminism                   = structure.allProjectRefs.sortBy(_.project)
    val aggregatorIds: Set[String]                 = structure.allProjects.filter(_.aggregate.nonEmpty).map(_.id).toSet
    val resolvedById: Map[String, ResolvedProject] = structure.allProjects.map(p => p.id -> p).toMap
    val buildRoot                                  = (LocalRootProject / baseDirectory).value.toPath

    val nodes = refsSortedForDeterminism.map { ref =>
      def read[A](key: SettingKey[A], default: A): A = extracted.getOpt(ref / key).getOrElse(default)
      val explicitOverride                           = read[Option[Boolean]](zipxPublish, None)
      val isAggregator                               = aggregatorIds.contains(ref.project)
      // `publish / skip` is a TaskKey, hence `runTask`; `publishArtifact` is a Setting.
      val skipsPublish      = extracted.runTask(ref / publish / skip, st)._2
      val publishesArtifact = read(publishArtifact, true)
      val publishes         = explicitOverride.getOrElse(!isAggregator && !skipsPublish && publishesArtifact)
      val crossVersions     =
        read(crossScalaVersions, Nil) match
          case Nil      => List(read(scalaVersion, "")).filter(_.nonEmpty)
          case versions => versions.toList
      val baseDir =
        resolvedById
          .get(ref.project)
          .map(p => buildRoot.relativize(p.base.toPath).toString.replace('\\', '/'))
          .getOrElse("")
      ModuleNode(
        // The one place a project id enters zipx, so the one place it is checked. sbt admits any id starting with a
        // `Character.isLetter`, so `café` is a legal project; GitHub job ids are ASCII, and a workflow naming that
        // module would be rejected on push. Reported here, before anything is written, rather than thrown from the
        // middle of planning. The newtype's message already quotes the offending id, so no prefix is added.
        id = orFail(ModuleId.make(ref.project)),
        dependsOn = deps.classpathRefs(ref).map(_.project).toList.distinct,
        publishes = publishes,
        ciRelevant = read(zipxCiRelevant, true),
        crossScalaVersions = crossVersions,
        testTask = orFail(typedCommand("zipxTestTask", read(zipxTestTask, "test"))),
        publishTask = orFail(typedCommand("zipxPublishTask", read(zipxPublishTask, "publish"))),
        baseDir = baseDir,
        docker = read(zipxDocker, false),
      )
    }.toList

    // sbt rejects a `dependsOn` cycle when it loads the build, so this cannot fail for a build that got this far. It
    // goes through `orFail` anyway: that is the boundary's job, and a graph is user input regardless of who checked it.
    orFail(ModuleGraph.make(nodes))
  }

  private def rootRef(structure: sbt.internal.BuildStructure): ProjectRef =
    ProjectRef(structure.root, structure.rootProject(structure.root))

  /** Reads a build-level setting from the *root project's* scope rather than ThisBuild's, so that every sbt-2.0
    * assignment form resolves: a bare `zipxX := …` (a per-project common setting), a `ThisBuild / zipxX := …`, and the
    * plugin's Global default all reach here via project → ThisBuild → Global delegation. A ThisBuild-scoped read would
    * miss the bare form, since delegation only goes specific → general.
    */
  private def readBuildSetting[A](extracted: Extracted, key: SettingKey[A], default: A): A =
    extracted.getOpt(rootRef(extracted.structure) / key).getOrElse(default)

  private def planConfig: Def.Initialize[Task[PlanConfig]] = Def.task {
    val extracted                                  = Project.extract(state.value)
    def read[A](key: SettingKey[A], default: A): A = readBuildSetting(extracted, key, default)
    val root                                       = (LocalRootProject / baseDirectory).value
    PlanConfig(
      workflowName = read(zipxWorkflowName, PlanConfig.DefaultWorkflowName),
      scalaMatrix = read(zipxScalaMatrix, true),
      javaVersion = read(zipxJavaVersion, PlanConfig.DefaultJdkVersion),
      runnerOs = read(zipxRunnerOs, PlanConfig.DefaultRunnerOs),
      affected = if read(zipxAffectedOnPR, true) then AffectedMode.AffectedOnPR else AffectedMode.Always,
      affectedOnPush = read(zipxAffectedOnPush, false),
      affectedPublish = read(zipxAffectedPublish, false),
      cache = read(zipxCache, CacheBackend.LocalDir),
      cacheEpoch = read(zipxCacheEpoch, CacheEpoch.GitTags()),
      pushBranches = read(zipxPushBranches, Seq("main")).toList,
      releaseTagPattern = read(zipxReleaseTagPattern, "v[0-9]+.[0-9]+.[0-9]+"),
      actions = resolveActionPins(extracted, root),
      workflowDispatch = read(zipxWorkflowDispatch, false),
      skipMergedPrPush = read(zipxSkipMergedPrPush, true),
      cacheRehydrateOnMerge = read(zipxCacheRehydrateOnMerge, true),
      cacheRehydrateTask = orFail(typedCommand("zipxCacheRehydrateTask", read(zipxCacheRehydrateTask, "compile"))),
      cacheRehydrateExtraSteps = read(zipxCacheRehydrateExtraSteps, (_ => Nil)),
      cacheRehydrateEnv = read(zipxCacheRehydrateEnv, Map.empty),
      env = read(zipxEnv, Map.empty),
      verifyClean = read(zipxVerifyClean, VerifyClean.None),
      verifyCleanLabel = orFail(typedVerifyCleanLabel(read(zipxVerifyCleanLabel, Some("clean")))),
      cancelSupersededRuns = read(zipxCancelSupersededRuns, true),
    )
  }

  /** A pin file that is present but unreadable fails the build through [[orFail]] rather than falling back to
    * `Defaults`. The fallback is what made a typo'd key silently revert a deliberately held-back pin to the version
    * baked into the zipx jar. An *absent* file still falls back, since that is the documented way to take the jar
    * defaults.
    */
  private def resolveActionPins(extracted: Extracted, root: File): ActionPins =
    val setting        = readBuildSetting(extracted, zipxActions, ActionPins.Defaults)
    val userOverrodeIt = setting != ActionPins.Defaults
    if userOverrodeIt then setting
    else
      val rel = readBuildSetting(extracted, zipxActionsPath, ActionPinFile.DefaultPath).trim
      if rel.isEmpty then ActionPins.Defaults
      else ActionPinFile.loadOption((root / rel).toPath).fold(ActionPins.Defaults)(orFail)

  /** Clean prefixes come from [[PlanConfig.verifyClean]] rather than from `verifyTask`, so the command string here is
    * only the task.
    */
  private def builtinCapabilities(graph: ModuleGraph, verifyTask: SbtCommand): List[Capability] =
    val test =
      Capability.once(name = Capability.TestName, command = verifyTask, phase = Phase.Verify, gate = Gate.Always)
    val base = List(test, Capability.publish)
    if graph.nodes.exists(_.docker) then base :+ Capability.docker else base

  /** The one place a zipx failure value becomes a thrown error. The libraries below report failures as `Either` and
    * never throw; sbt's task contract is the opposite, a task fails by throwing. This is the seam, and it lives here so
    * that a library caller still sees the `Either`.
    */
  private def orFail[A](result: Either[String, A]): A =
    result.fold(error => sys.error(s"zipx: $error"), identity)

  private def typedVerifyCleanLabel(label: Option[String]): Either[String, Option[zipx.workflow.ExprLiteral]] =
    label match
      case None        => Right(None)
      case Some(value) =>
        PlanConfig.verifyCleanLabelMake(value).left.map(error => s"zipxVerifyCleanLabel: $error")

  /** A command-valued setting as an [[SbtCommand]]. The settings stay `String`-typed, because a `build.sbt` assigns
    * them as ordinary strings and an opaque type in a `settingKey` would need an sbt `JsonFormat`; the check moves
    * here, where every other config value is already checked. `zipxTasks` and `cmd"…"` are the typed route that skips
    * this.
    */
  private def typedCommand(setting: String, command: String): Either[String, SbtCommand] =
    SbtCommand.make(command).left.map(error => s"$setting: $error")

  private def renderWorkflow: Def.Initialize[Task[String]] = Def.task {
    val graph        = buildGraph.value
    val cfg          = planConfig.value
    val extracted    = Project.extract(state.value)
    val userCaps     = readBuildSetting(extracted, zipxCapabilities, Seq.empty)
    val verifyTask   = orFail(typedCommand("zipxTestTask", readBuildSetting(extracted, zipxTestTask, "test")))
    val capabilities = combineCapabilities(builtinCapabilities(graph, verifyTask), userCaps.toList)
    val yaml         = orFail(Render.render(Planner.plan(graph, capabilities, cfg)))
    ActionPinFile.annotateUses(yaml, cfg.actions)
  }

  private def writeGeneratedWorkflows: Def.Initialize[Task[Unit]] = Def.task {
    val log     = streams.value.log
    val out     = workflowFile.value
    val content = renderWorkflow.value
    IO.write(out, content)
    log.info(s"zipx wrote ${out.getPath}")
    warnRawFragments.value
    writeSyncWorkflowIfEnabled.value
    writeStewardWorkflowIfEnabled.value
  }

  /** Warns once per escape-hatch fragment, naming the bundle. Raw content is typed, so it cannot emit YAML GitHub fails
    * to parse; it can still emit broken shell, and nothing checks that.
    */
  private def warnRawFragments: Def.Initialize[Task[Unit]] = Def.task {
    val log          = streams.value.log
    val graph        = buildGraph.value
    val cfg          = planConfig.value
    val extracted    = Project.extract(state.value)
    val userCaps     = readBuildSetting(extracted, zipxCapabilities, Seq.empty)
    val verifyTask   = orFail(typedCommand("zipxTestTask", readBuildSetting(extracted, zipxTestTask, "test")))
    val capabilities = combineCapabilities(builtinCapabilities(graph, verifyTask), userCaps.toList)
    Steps.rawWarnings(capabilities, cfg).foreach(w => log.warn(s"zipx: $w"))
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
      val body       = orFail(
        ActionPinsSyncWorkflow.render(
          cfg.actions,
          cfg.javaVersion,
          cfg.runnerOs,
          actionsRel,
          wfRel,
        )
      )
      IO.write(syncFile, body)
      streams.value.log.info(s"zipx wrote ${syncFile.getPath}")
    end if
  }

  /** Shared by generate and check, so the two cannot disagree about whether the config file should exist. */
  private def stewardGrouping(extracted: Extracted): Option[String] =
    val groups = readBuildSetting(extracted, zipxStewardGrouping, ScalaStewardConfig.Defaults).toList
    Option.when(groups.nonEmpty)(ScalaStewardConfig.render(groups))

  private def writeStewardWorkflowIfEnabled: Def.Initialize[Task[Unit]] = Def.task {
    val extracted   = Project.extract(state.value)
    val enabled     = readBuildSetting(extracted, zipxScalaSteward, false)
    val root        = (LocalRootProject / baseDirectory).value
    val stewardFile = root / ScalaStewardWorkflow.DefaultPath
    if enabled then
      val cfg        = planConfig.value
      val log        = streams.value.log
      val maybeConf  = stewardGrouping(extracted)
      val configPath = maybeConf.map(_ => ScalaStewardWorkflow.DefaultConfigPath)
      maybeConf.foreach { conf =>
        val confFile = root / ScalaStewardWorkflow.DefaultConfigPath
        IO.write(confFile, conf)
        log.info(s"zipx wrote ${confFile.getPath}")
      }
      val body = orFail(ScalaStewardWorkflow.render(cfg.actions, cfg.runnerOs, configPath = configPath))
      IO.write(stewardFile, body)
      log.info(s"zipx wrote ${stewardFile.getPath}")
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
    // A pull rewrites the pin file, so both reads fail loudly: overwriting a file zipx could not read would launder the
    // unreadable line away, and a workflow whose `uses:` is not a valid ref is exactly what must not be pulled in.
    val base   = ActionPinFile.loadOption(pinPath).fold(ActionPins.Defaults)(orFail)
    val pulled = orFail(ActionPinFile.pullFromWorkflow(IO.read(wfFile), base))
    ActionPinFile.write(pinPath, pulled)
    log.info(s"zipx wrote ${pinPath}")
    writeGeneratedWorkflows.value
  }

  /** A user capability whose `name` matches a built-in *replaces* it, so supplying a multi-registry `docker` capability
    * yields one set of `docker-<module>` jobs rather than duplicates.
    */
  private def combineCapabilities(builtins: List[Capability], user: List[Capability]): List[Capability] =
    val userByName = user.map(c => c.name -> c).toMap
    val overridden = builtins.map(b => userByName.getOrElse(b.name, b))
    val newlyAdded = user.filterNot(u => builtins.exists(_.name == u.name))
    overridden ++ newlyAdded

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

  /** A task rather than a setting because `baseDirectory` is a task in sbt 2.x. */
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
      val expectedSync = orFail(
        ActionPinsSyncWorkflow.render(
          cfg.actions,
          cfg.javaVersion,
          cfg.runnerOs,
          actionsRel,
          wfRel,
        )
      )
      val actualSync = if syncFile.exists then IO.read(syncFile) else ""
      if actualSync != expectedSync then
        sys.error(
          s"${syncFile.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result."
        )
      streams.value.log.info(s"zipx: ${syncFile.getPath} is up to date.")
    end if
    if readBuildSetting(extracted, zipxScalaSteward, false) then
      val root        = (LocalRootProject / baseDirectory).value
      val stewardFile = root / ScalaStewardWorkflow.DefaultPath
      val cfg         = planConfig.value
      val maybeConf   = stewardGrouping(extracted)
      val configPath  = maybeConf.map(_ => ScalaStewardWorkflow.DefaultConfigPath)
      // Checked before the workflow itself: the Steward action ignores a missing config at the default path silently,
      // so this drift check is the only thing that catches it.
      maybeConf.foreach { expectedConf =>
        val confFile   = root / ScalaStewardWorkflow.DefaultConfigPath
        val actualConf = if confFile.exists then IO.read(confFile) else ""
        if actualConf != expectedConf then
          sys.error(
            s"${confFile.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result."
          )
        streams.value.log.info(s"zipx: ${confFile.getPath} is up to date.")
      }
      val expectedSteward = orFail(ScalaStewardWorkflow.render(cfg.actions, cfg.runnerOs, configPath = configPath))
      val actualSteward   = if stewardFile.exists then IO.read(stewardFile) else ""
      if actualSteward != expectedSteward then
        sys.error(
          s"${stewardFile.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result."
        )
      streams.value.log.info(s"zipx: ${stewardFile.getPath} is up to date.")
    end if
  }

  /** `zipxAffectedModules <base-ref>`. Writes the ids to a fixed `target/zipx-affected.json` rather than stdout because
    * the generated `affected` job reads the file, which keeps sbt's log lines out of `GITHUB_OUTPUT`. The path is built
    * from `baseDirectory`, not `(target).value`, which under sbt 2 is a versioned `target/out/…` tree.
    */
  private def affectedModulesTask: Def.Initialize[InputTask[Unit]] =
    Def.inputTask {
      val base         = sbt.complete.DefaultParsers.trimmed(sbt.complete.DefaultParsers.any.*.string).parsed.trim
      val graph        = buildGraph.value
      val root         = (LocalRootProject / baseDirectory).value
      val baseRef      = if base.isEmpty then "HEAD^" else base
      val changedFiles = gitDiffNames(root, baseRef)
      val diffFailed   = changedFiles.isEmpty
      if diffFailed then
        streams.value.log.warn(
          s"zipx: could not diff against '$baseRef', emitting ${jsonArray(Affected.AllSentinel)} so every job runs. " +
            "Affected-only gating is disabled for this run."
        )
      val json = jsonArray(Affected.outputModules(graph, changedFiles))
      IO.write(root / "target" / "zipx-affected.json", json + "\n")
      println(json)
    }

  /** Files changed on HEAD since its merge-base with `baseRef`, repo-root-relative with forward slashes.
    *
    * `None` means the diff *failed*, `Some(Nil)` means it succeeded and found nothing. [[Affected.outputModules]] needs
    * that distinction to fail open; collapsing both to `Nil` is what once made a bad base ref skip every Verify job and
    * report the PR green.
    */
  private def gitDiffNames(root: File, baseRef: String): Option[List[String]] =
    try
      val lines = scala.collection.mutable.ListBuffer.empty[String]
      val code  =
        scala.sys.process
          .Process(Seq("git", "diff", "--name-only", s"$baseRef...HEAD"), root)
          .!(scala.sys.process.ProcessLogger(lines += _, _ => ()))
      if code == 0 then Some(lines.map(_.trim).filter(_.nonEmpty).toList) else None
    catch case scala.util.control.NonFatal(_) => None

  private def jsonArray(items: List[String]): String =
    items.map(s => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").mkString("[", ",", "]")

end ZipxPlugin
