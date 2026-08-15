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

    type PinFeed = zipx.core.PinFeed
    val PinFeed = zipx.core.PinFeed
    type PinFeedName = zipx.core.PinFeedName
    val PinFeedName = zipx.core.PinFeedName
    type PinAction = zipx.core.PinAction
    val PinAction = zipx.core.PinAction
    type PinPrGate = zipx.core.PinPrGate
    val PinPrGate = zipx.core.PinPrGate
    type Purl = zipx.core.Purl
    val Purl = zipx.core.Purl
    type PinnedDep = zipx.core.PinnedDep
    val PinnedDep = zipx.core.PinnedDep
    type VersionStrategy = zipx.core.VersionStrategy
    val VersionStrategy = zipx.core.VersionStrategy
    type AdvisorySeverity = zipx.core.AdvisorySeverity
    val AdvisorySeverity = zipx.core.AdvisorySeverity

    type ZipxCoord = zipx.core.ZipxCoord
    type Lib       = zipx.core.Lib
    val Lib = zipx.core.Lib
    type Plugin = zipx.core.Plugin
    val Plugin = zipx.core.Plugin
    type Cross = zipx.core.Cross
    val Cross = zipx.core.Cross
    type SbtVersion = zipx.core.SbtVersion
    val SbtVersion = zipx.core.SbtVersion
    type ScalaVersion = zipx.core.ScalaVersion
    val ScalaVersion = zipx.core.ScalaVersion
    type ZipxExclude = zipx.core.ZipxExclude
    val ZipxExclude = zipx.core.ZipxExclude
    val ZipxDeps    = zipx.sbt.ZipxDeps
    val ZipxCatalog = zipx.core.ZipxCatalog
    type PinLookup = zipx.core.PinLookup
    type PinApply  = zipx.core.PinApply

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

    /** A sidecar container for a capability's jobs: `Capability.testGraph.withService("postgres",
      * JobService("postgres:17", ports = List("5432:5432")))`.
      */
    type JobService = zipx.workflow.JobService
    val JobService = zipx.workflow.JobService

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

    /** Not a settings type: a Node toolchain is per-capability,
      * `Capability.testGraph.withNodeVersion(NodeVersion("22"))`.
      */
    type NodeVersion = zipx.core.NodeVersion
    val NodeVersion = zipx.core.NodeVersion

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
    type MatrixCollapse = zipx.core.MatrixCollapse
    val MatrixCollapse = zipx.core.MatrixCollapse
    type VerifyClean = zipx.core.VerifyClean
    val VerifyClean = zipx.core.VerifyClean
    type EnvValue = zipx.core.EnvValue
    val EnvValue = zipx.core.EnvValue
    val Secret   = zipx.core.Secret
    export zipx.core.EnvValue.secret

    /** Nested object rather than a re-exported type: this way a `build.sbt` needs only `Capability` from the plugin
      * jar, not `zipx-central` on the meta classpath. Release vals are built from real keys (`publishSigned`,
      * `sonaRelease`); pure signing helpers still come from the central jar.
      */
    object ZipxCentral:
      import com.jsuereth.sbtpgp.PgpKeys.publishSigned as publishSignedKey
      import sbt.internal.librarymanagement.Publishing

      private def withSigning(cap: Capability): Capability =
        cap.withEnv(zipx.central.ZipxCentral.signingEnv).withExtraSteps(zipx.central.ZipxCentral.gpgImportSteps)

      /** Aggregate: every publishing module's `publishSigned`, then `sonaRelease` once. */
      def release: Capability =
        withSigning(
          Capability.publish
            .runningEachCross(CapabilityTasks.of(publishSignedKey))
            .thenOnce(CapabilityTasks.of(Publishing.sonaRelease))
        )

      /** Root Once: `publishSigned; sonaRelease`. Prefer [[release]] when the root aggregate is not the publish set. */
      def releaseRoot: Capability =
        withSigning(
          Capability.once(
            name = Capability.PublishName,
            command = CapabilityTasks.session(publishSignedKey, Publishing.sonaRelease),
            phase = Phase.Publish,
            gate = Gate.OnReleaseTag,
          )
        )

      /** Graph fan-out publish; pair with [[releaseOnce]]. */
      def publishSigned: Capability =
        withSigning(
          Capability.publishGraph
            .runningEachCross(CapabilityTasks.of(publishSignedKey))
            .withPostSteps(zipx.central.ZipxCentral.uploadStagingSteps)
        )

      def releaseOnce: Capability =
        Capability.once(
          name = CapabilityName("central-release"),
          command = CapabilityTasks.of(Publishing.sonaRelease),
          phase = Phase.Publish,
          gate = Gate.OnReleaseTag,
          needsCapabilities = List(Capability.PublishName),
          env = zipx.central.ZipxCentral.signingEnv,
          extraSteps = zipx.central.ZipxCentral.downloadStagingSteps ++ zipx.central.ZipxCentral.gpgImportSteps,
        )

      def signingEnv     = zipx.central.ZipxCentral.signingEnv
      def OrgSecretNames = zipx.central.ZipxCentral.OrgSecretNames
      def gpgImportSteps = zipx.central.ZipxCentral.gpgImportSteps
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

    /** scoverage, as `zipxCapabilities += Coverage.once()`. In `zipx-core` rather than a pack because the thing it
      * guards against, sbt 2's `test` being `testQuick`, is a core concern; see [[zipx.core.Coverage]].
      */
    val Coverage = zipx.core.Coverage
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

    /** `zipxPublish := zipxOn` / `zipxOff` / `zipxAuto` instead of `Some(true)` / `Some(false)` / `None`. */
    val zipxOn: Option[Boolean]   = Some(true)
    val zipxOff: Option[Boolean]  = Some(false)
    val zipxAuto: Option[Boolean] = None

    // Descriptions come from [[ZipxSettings]] (sbt macros require settingKey/taskKey/inputKey on the val RHS).
    val zipxCapabilities      = settingKey[Seq[Capability]](ZipxSettings.capabilities.description)
    val zipxCache             = settingKey[CacheBackend](ZipxSettings.cache.description)
    val zipxWorkflowName      = settingKey[WorkflowName](ZipxSettings.workflowName.description)
    val zipxWorkflowPath      = settingKey[String](ZipxSettings.workflowPath.description)
    val zipxJavaVersion       = settingKey[JdkVersion](ZipxSettings.javaVersion.description)
    val zipxRunnerOs          = settingKey[RunnerOs](ZipxSettings.runnerOs.description)
    val zipxScalaMatrix       = settingKey[Boolean](ZipxSettings.scalaMatrix.description)
    val zipxMatrixCollapse    = settingKey[Map[CapabilityName, MatrixCollapse]](ZipxSettings.matrixCollapse.description)
    val zipxCacheEpoch        = settingKey[CacheEpoch](ZipxSettings.cacheEpoch.description)
    val zipxPushBranches      = settingKey[Seq[String]](ZipxSettings.pushBranches.description)
    val zipxReleaseTagPattern = settingKey[String](ZipxSettings.releaseTagPattern.description)
    val zipxActions           = settingKey[ActionPins](ZipxSettings.actions.description)
    val zipxActionsPath       = settingKey[String](ZipxSettings.actionsPath.description)
    val zipxDependabotSync    = settingKey[Boolean](ZipxSettings.dependabotSync.description)
    val zipxScalaSteward      = settingKey[Boolean](ZipxSettings.scalaSteward.description)
    val zipxStewardGrouping   = settingKey[Seq[StewardGroup]](ZipxSettings.stewardGrouping.description)
    val zipxWorkflowDispatch  = settingKey[Boolean](ZipxSettings.workflowDispatch.description)
    val zipxCiRelevant        = settingKey[Boolean](ZipxSettings.ciRelevant.description)
    val zipxPublish           = settingKey[Option[Boolean]](ZipxSettings.publish.description)
    val zipxTestTask          = settingKey[SbtCommand](ZipxSettings.testTask.description)
    val zipxPublishTask       = settingKey[SbtCommand](ZipxSettings.publishTask.description)
    val zipxDocker            = settingKey[Boolean](ZipxSettings.docker.description)
    val zipxVerifyClean       = settingKey[VerifyClean](ZipxSettings.verifyClean.description)
    val zipxVerifyCleanLabel  = settingKey[Option[String]](ZipxSettings.verifyCleanLabel.description)
    val zipxAffectedOnPR      = settingKey[Boolean](ZipxSettings.affectedOnPR.description)
    val zipxAffectedOnPush    = settingKey[Boolean](ZipxSettings.affectedOnPush.description)
    val zipxAffectedPublish   = settingKey[Boolean](ZipxSettings.affectedPublish.description)
    val zipxAffectedDeploy    = settingKey[Boolean](ZipxSettings.affectedDeploy.description)
    val zipxSkipMergedPrPush  = settingKey[Boolean](ZipxSettings.skipMergedPrPush.description)
    val zipxCacheRehydrateOnMerge    = settingKey[Boolean](ZipxSettings.cacheRehydrateOnMerge.description)
    val zipxCacheRehydrateTask       = settingKey[SbtCommand](ZipxSettings.cacheRehydrateTask.description)
    val zipxCacheRehydrateExtraSteps =
      settingKey[StepContext => List[Step]](ZipxSettings.cacheRehydrateExtraSteps.description)
    val zipxCacheRehydrateEnv    = settingKey[Map[String, EnvValue]](ZipxSettings.cacheRehydrateEnv.description)
    val zipxEnv                  = settingKey[Map[String, EnvValue]](ZipxSettings.env.description)
    val zipxCancelSupersededRuns = settingKey[Boolean](ZipxSettings.cancelSupersededRuns.description)
    val zipxCheckCommandNames    = settingKey[Boolean](ZipxSettings.checkCommandNames.description)
    val zipxPinFeeds             = settingKey[Seq[PinFeed]](ZipxSettings.pinFeeds.description)
    val zipxPinPrGate            = settingKey[PinPrGate](ZipxSettings.pinPrGate.description)
    val zipxVersions             = settingKey[Seq[ZipxCoord]](ZipxSettings.versions.description)
    val zipxSbt                  = settingKey[Option[SbtVersion]](ZipxSettings.sbtVersionCoord.description)
    val zipxScala                = settingKey[Option[ScalaVersion]](ZipxSettings.scalaVersionCoord.description)
    val zipxCheckDeps            = settingKey[Boolean](ZipxSettings.checkDeps.description)
    val zipxEmitSelf             = settingKey[Boolean](ZipxSettings.emitSelf.description)
    val zipxPluginVersion        = settingKey[Option[String]](ZipxSettings.pluginVersion.description)
    val zipxVersionsFile         = settingKey[String](ZipxSettings.versionsFile.description)

    val zipxGraph            = taskKey[Unit](ZipxSettings.graph.description)
    val zipxPublishOrder     = taskKey[Unit](ZipxSettings.publishOrder.description)
    val zipxWorkflowGenerate = taskKey[Unit](ZipxSettings.workflowGenerate.description)
    val zipxWorkflowCheck    = taskKey[Unit](ZipxSettings.workflowCheck.description)
    val zipxActionsPull      = taskKey[Unit](ZipxSettings.actionsPull.description)
    val zipxAffectedModules  = inputKey[Unit](ZipxSettings.affectedModules.description)
    val zipxPinCheck         = taskKey[Unit](ZipxSettings.pinCheck.description)
    val zipxPinCheckPr       = taskKey[Unit](ZipxSettings.pinCheckPr.description)
    val zipxPinSubmit        = taskKey[Unit](ZipxSettings.pinSubmit.description)
    val zipxPinInventory     = taskKey[Unit](ZipxSettings.pinInventory.description)
    val zipxPinUpdate        = inputKey[Unit](ZipxSettings.pinUpdate.description)
    val zipxDepUpdate        = inputKey[Unit](ZipxSettings.depUpdate.description)
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
    zipxMatrixCollapse           := Map.empty,
    zipxPushBranches             := Seq("main"),
    zipxReleaseTagPattern        := "v[0-9]+.[0-9]+.[0-9]+",
    zipxWorkflowPath             := ".github/workflows/ci.yml",
    zipxAffectedOnPR             := true,
    zipxAffectedOnPush           := false,
    zipxAffectedPublish          := false,
    zipxAffectedDeploy           := false,
    zipxSkipMergedPrPush         := true,
    zipxCacheRehydrateOnMerge    := true,
    zipxCacheRehydrateTask       := CapabilityTasks.of(compile),
    zipxCacheRehydrateExtraSteps := (_ => Nil),
    zipxCacheRehydrateEnv        := Map.empty,
    zipxEnv                      := Map.empty,
    zipxCancelSupersededRuns     := true,
    zipxCheckCommandNames        := true,
    zipxVerifyClean              := VerifyClean.None,
    zipxVerifyCleanLabel         := Some("clean"),
    zipxActions                  := ActionPins.Defaults,
    zipxActionsPath              := ActionPinFile.DefaultPath,
    zipxDependabotSync           := false,
    zipxScalaSteward             := false,
    zipxStewardGrouping          := ScalaStewardConfig.Defaults,
    zipxPinFeeds                 := Seq.empty,
    zipxPinPrGate                := PinPrGate.All,
    zipxVersions                 := Seq.empty,
    zipxSbt                      := None,
    zipxScala                    := None,
    zipxCheckDeps                := false,
    zipxEmitSelf                 := true,
    zipxPluginVersion            := None,
    zipxVersionsFile             := ZipxCatalog.DefaultVersionsFile,
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
      Def
        .sequential(
          Def.task(validateCatalog(Project.extract(state.value))),
          writeGeneratedWorkflows,
        )
        .value
    },
    zipxWorkflowCheck := checkTask.value,
    zipxActionsPull   := Def.uncached {
      actionsPullTask.value
    },
    zipxAffectedModules := affectedModulesTask.evaluated,
    zipxPinCheck        := Def.uncached { pinCheckTask.value },
    zipxPinCheckPr      := Def.uncached { pinCheckPrTask.value },
    zipxPinSubmit       := Def.uncached { pinSubmitTask.value },
    zipxPinInventory    := Def.uncached { pinInventoryTask.value },
    zipxPinUpdate       := pinUpdateTask.evaluated,
    zipxDepUpdate       := depUpdateTask.evaluated,
  )

  /** An aggregator is a container rather than a testable module, so it is CI-irrelevant by default. Plain settings, so
    * a project can override any of them.
    */
  override def projectSettings: Seq[Setting[?]] = Seq(
    zipxCiRelevant  := thisProject.value.aggregate.isEmpty,
    zipxPublish     := zipxAuto,
    zipxTestTask    := CapabilityTasks.of(testFull),
    zipxPublishTask := CapabilityTasks.of(publish),
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
      val baseDir = resolvedById.get(ref.project).map(p => relativeToRoot(buildRoot, p.base)).getOrElse("")
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
        testTask = read(zipxTestTask, CapabilityTasks.of(testFull)),
        publishTask = read(zipxPublishTask, CapabilityTasks.of(publish)),
        baseDir = baseDir,
        sourcePaths = sourcePathsFor(ref, extracted, buildRoot),
        docker = read(zipxDocker, false),
      )
    }.toList

    // sbt rejects a `dependsOn` cycle when it loads the build, so this cannot fail for a build that got this far. It
    // goes through `orFail` anyway: that is the boundary's job, and a graph is user input regardless of who checked it.
    orFail(ModuleGraph.make(nodes))
  }

  /** A path relative to the build root, with forward slashes, matching what `git diff --name-only` prints. */
  private def relativeToRoot(buildRoot: java.nio.file.Path, f: File): String =
    buildRoot.relativize(f.toPath).toString.replace('\\', '/')

  /** A project's Compile and Test source directories relative to the build root, for [[ModuleNode.sourcePaths]].
    *
    * Two filters: a path outside the build root (`../…`, from a source dependency elsewhere on disk) can never match a
    * git path, and a machine-owned one (under `target`, or a `projectMatrix` row's `.sbt/matrix/<id>`) is never edited.
    * Directories that do not exist yet are kept, so creating `src/main/scalajs` later needs no regeneration.
    */
  private def sourcePathsFor(
      ref: ProjectRef,
      extracted: Extracted,
      buildRoot: java.nio.file.Path,
  ): List[String] =
    val dirs = Seq(Compile, Test).flatMap { config =>
      extracted.getOpt(ref / config / unmanagedSourceDirectories).toList.flatten
    }
    dirs
      .map(relativeToRoot(buildRoot, _))
      .filterNot(p => p.isEmpty || p.startsWith("../") || isGeneratedPath(p))
      .distinct
      .sorted
      .toList
  end sourcePathsFor

  /** sbt's output tree, or a `projectMatrix` row's synthetic base under `.sbt/`. */
  private def isGeneratedPath(path: String): Boolean =
    val segments = path.split('/')
    segments.contains("target") || segments.headOption.contains(".sbt")

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
      matrixCollapse = read(zipxMatrixCollapse, Map.empty),
      javaVersion = read(zipxJavaVersion, PlanConfig.DefaultJdkVersion),
      runnerOs = read(zipxRunnerOs, PlanConfig.DefaultRunnerOs),
      affected = if read(zipxAffectedOnPR, true) then AffectedMode.AffectedOnPR else AffectedMode.Always,
      affectedOnPush = read(zipxAffectedOnPush, false),
      affectedPublish = read(zipxAffectedPublish, false),
      affectedDeploy = read(zipxAffectedDeploy, false),
      cache = read(zipxCache, CacheBackend.LocalDir),
      cacheEpoch = read(zipxCacheEpoch, CacheEpoch.GitTags()),
      pushBranches = read(zipxPushBranches, Seq("main")).toList,
      releaseTagPattern = read(zipxReleaseTagPattern, "v[0-9]+.[0-9]+.[0-9]+"),
      actions = resolveActionPins(extracted, root),
      workflowDispatch = read(zipxWorkflowDispatch, false),
      skipMergedPrPush = read(zipxSkipMergedPrPush, true),
      cacheRehydrateOnMerge = read(zipxCacheRehydrateOnMerge, true),
      cacheRehydrateTask = read(zipxCacheRehydrateTask, CapabilityTasks.of(compile)),
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
  private def builtinCapabilities(
      graph: ModuleGraph,
      verifyTask: SbtCommand,
      feeds: Seq[PinFeed],
      gate: PinPrGate,
  ): List[Capability] =
    val test =
      Capability.once(name = Capability.TestName, command = verifyTask, phase = Phase.Verify, gate = Gate.Always)
    val base       = List(test, Capability.publish)
    val withDocker = if graph.nodes.exists(_.docker) then base :+ Capability.docker else base
    if PinFeeds.emitPrGate(feeds, gate) then
      val pin =
        val cap = Capability.pinCheck(CapabilityTasks.of(zipxPinCheckPr))
        if gate == PinPrGate.Introduced then cap.withExtraSteps(PinCheck.fetchBaseSha) else cap
      withDocker :+ pin
    else withDocker
  end builtinCapabilities

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

  private def knownCommandNames(st: State, extracted: Extracted): Set[String] =
    val fromState    = st.definedCommands.flatMap(_.nameOption)
    val fromProjects = extracted.structure.allProjectRefs.flatMap { r =>
      extracted.getOpt(r / commands).toList.flatten.flatMap(_.nameOption)
    }
    (fromState ++ fromProjects).toSet

  /** Fail generate/check when a capability declares a command name sbt does not know (aliases, `sonaRelease`, …). */
  private def checkCommandNames(
      capabilities: List[Capability],
      st: State,
      extracted: Extracted,
  ): Unit =
    if !readBuildSetting(extracted, zipxCheckCommandNames, true) then ()
    else
      val known = knownCommandNames(st, extracted)
      if known.isEmpty then ()
      else
        val missing = capabilities
          .flatMap(_.declaredNames)
          .map(n => n: String)
          .distinct
          .filterNot(known.contains)
        if missing.nonEmpty then
          val hint = missing
            .map { name =>
              val nearest = known.minByOption(k => distance(name, k)).filter(k => distance(name, k) <= 3)
              nearest match
                case Some(k) => s"'$name' (did you mean '$k'?)"
                case None    => s"'$name'"
            }
            .mkString(", ")
          sys.error(
            s"zipx: unknown sbt command name(s): $hint. Add the plugin/alias that defines them, or set zipxCheckCommandNames := false"
          )
        end if
      end if

  private def distance(a: String, b: String): Int =
    val m  = a.length
    val n  = b.length
    val dp = Array.ofDim[Int](m + 1, n + 1)
    for i <- 0 to m do dp(i)(0) = i
    for j <- 0 to n do dp(0)(j) = j
    for i <- 1 to m; j <- 1 to n do
      val cost = if a(i - 1) == b(j - 1) then 0 else 1
      dp(i)(j) = (dp(i - 1)(j) + 1).min(dp(i)(j - 1) + 1).min(dp(i - 1)(j - 1) + cost)
    dp(m)(n)
  end distance

  private def renderWorkflow: Def.Initialize[Task[String]] = Def.task {
    val graph        = buildGraph.value
    val cfg          = planConfig.value
    val st           = state.value
    val extracted    = Project.extract(st)
    val userCaps     = readBuildSetting(extracted, zipxCapabilities, Seq.empty)
    val verifyTask   = readBuildSetting(extracted, zipxTestTask, CapabilityTasks.of(testFull))
    val feeds        = readBuildSetting(extracted, zipxPinFeeds, Seq.empty)
    val gate         = readBuildSetting(extracted, zipxPinPrGate, PinPrGate.All)
    val capabilities = combineCapabilities(builtinCapabilities(graph, verifyTask, feeds, gate), userCaps.toList)
    checkCommandNames(capabilities, st, extracted)
    val yaml = orFail(Render.render(Planner.plan(graph, capabilities, cfg)))
    ActionPinFile.annotateUses(yaml, cfg.actions)
  }

  private def writeGeneratedWorkflows: Def.Initialize[Task[Unit]] = Def.task {
    val log     = streams.value.log
    val out     = workflowFile.value
    val content = renderWorkflow.value
    IO.write(out, content)
    log.info(s"zipx wrote ${out.getPath}")
    warnRawFragments.value
    writeCompositeActions.value
    writeSyncWorkflowIfEnabled.value
    writeStewardWorkflowIfEnabled.value
    writePinWorkflowsIfEnabled.value
    writeCatalogIfEnabled.value
  }

  private def writeCompositeActions: Def.Initialize[Task[Unit]] = Def.task {
    val log   = streams.value.log
    val root  = (LocalRootProject / baseDirectory).value
    val cfg   = planConfig.value
    val files = orFail(ZipxComposites.artifacts(cfg.actions, cfg.cacheEpoch))
    files.foreach { (rel, body) =>
      val file = root / rel
      IO.write(file, body)
      log.info(s"zipx wrote ${file.getPath}")
    }
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
    val verifyTask   = readBuildSetting(extracted, zipxTestTask, CapabilityTasks.of(testFull))
    val feeds        = readBuildSetting(extracted, zipxPinFeeds, Seq.empty)
    val gate         = readBuildSetting(extracted, zipxPinPrGate, PinPrGate.All)
    val capabilities = combineCapabilities(builtinCapabilities(graph, verifyTask, feeds, gate), userCaps.toList)
    Steps.rawWarnings(capabilities, cfg).foreach(w => log.warn(s"zipx: $w"))
    MatrixCollapse.warnings(capabilities, graph, cfg).foreach(w => log.warn(s"zipx: $w"))
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
      warnDeadRepoRootStewardGrouping(root, log)
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

  private def warnDeadRepoRootStewardGrouping(root: File, log: Logger): Unit =
    val repoConf = root / ".scala-steward.conf"
    if repoConf.exists && ScalaStewardConfig.repoRootGroupingIsDead(IO.read(repoConf)) then
      log.warn(s"zipx: ${ScalaStewardConfig.RepoRootGroupingWarning}")

  private def writePinWorkflowsIfEnabled: Def.Initialize[Task[Unit]] = Def.task {
    val extracted = Project.extract(state.value)
    val feeds     = readBuildSetting(extracted, zipxPinFeeds, Seq.empty)
    if PinFeeds.emitCompanions(feeds) then
      val cfg       = planConfig.value
      val root      = (LocalRootProject / baseDirectory).value
      val log       = streams.value.log
      val checkBody = orFail(
        PinCheckWorkflow.render(cfg.actions, cfg.javaVersion, cfg.runnerOs, PinFeeds.hasUpdate(feeds))
      )
      writeCompanion(root, PinCheckWorkflow.DefaultPath, checkBody, log)
      if PinFeeds.emitSnapshot(feeds) then
        val snapBody = orFail(
          PinSnapshotWorkflow.render(cfg.actions, cfg.javaVersion, cfg.runnerOs, cfg.pushBranches)
        )
        writeCompanion(root, PinSnapshotWorkflow.DefaultPath, snapBody, log)
    end if
  }

  private def checkPinWorkflows(root: File, cfg: PlanConfig, extracted: Extracted, log: Logger): Unit =
    val feeds = readBuildSetting(extracted, zipxPinFeeds, Seq.empty)
    if PinFeeds.emitCompanions(feeds) then
      val expectedCheck = orFail(
        PinCheckWorkflow.render(cfg.actions, cfg.javaVersion, cfg.runnerOs, PinFeeds.hasUpdate(feeds))
      )
      checkCompanion(root, PinCheckWorkflow.DefaultPath, expectedCheck, log)
      if PinFeeds.emitSnapshot(feeds) then
        val expectedSnap = orFail(
          PinSnapshotWorkflow.render(cfg.actions, cfg.javaVersion, cfg.runnerOs, cfg.pushBranches)
        )
        checkCompanion(root, PinSnapshotWorkflow.DefaultPath, expectedSnap, log)
    end if
  end checkPinWorkflows

  private def writeCompanion(root: File, rel: String, body: String, log: Logger): Unit =
    val file = root / rel
    IO.write(file, body)
    log.info(s"zipx wrote ${file.getPath}")

  private def checkCompanion(root: File, rel: String, expected: String, log: Logger): Unit =
    val file   = root / rel
    val actual = if file.exists then IO.read(file) else ""
    if actual != expected then
      sys.error(s"${file.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result.")
    log.info(s"zipx: ${file.getPath} is up to date.")

  private def pinInventoryTask: Def.Initialize[Task[Unit]] = Def.task {
    val extracted = Project.extract(state.value)
    val feeds     = readBuildSetting(extracted, zipxPinFeeds, Seq.empty)
    val root      = (LocalRootProject / baseDirectory).value
    val file      = root / PinInventory.RelPath
    IO.write(file, PinInventory.render(feeds) + "\n")
    streams.value.log.info(s"zipx wrote ${file.getPath}")
  }

  private def pinCheckTask: Def.Initialize[Task[Unit]] = Def.task {
    val extracted = Project.extract(state.value)
    val feeds     = readBuildSetting(extracted, zipxPinFeeds, Seq.empty)
    val report    = orFail(PinEngine.scheduled(feeds, OsvAdvisorySource()))
    streams.value.log.info(PinEngine.summary(report))
    if report.failsJob then sys.error("zipx: pin check findings")
  }

  private def pinCheckPrTask: Def.Initialize[Task[Unit]] = Def.task {
    val extracted = Project.extract(state.value)
    val feeds     = readBuildSetting(extracted, zipxPinFeeds, Seq.empty)
    val gate      = readBuildSetting(extracted, zipxPinPrGate, PinPrGate.All)
    val root      = (LocalRootProject / baseDirectory).value
    val base      =
      if gate == PinPrGate.Introduced then
        sys.env.get(PinCheck.BaseShaEnv).filter(_.nonEmpty) match
          case None      => sys.error(s"zipx: ${PinCheck.BaseShaEnv} is required for PinPrGate.Introduced")
          case Some(sha) => inventoryAtBase(root, sha, streams.value.log)
      else Map.empty
    val report = orFail(PinEngine.prGate(feeds, OsvAdvisorySource(), gate, base))
    streams.value.log.info(PinEngine.summary(report))
    if report.failsJob then sys.error("zipx: pin check findings")
  }

  private def pinUpdateTask: Def.Initialize[InputTask[Unit]] =
    Def.inputTask {
      val arg       = sbt.complete.DefaultParsers.trimmed(sbt.complete.DefaultParsers.any.*.string).parsed.trim
      val extracted = Project.extract(state.value)
      val feeds     = readBuildSetting(extracted, zipxPinFeeds, Seq.empty)
      val log       = streams.value.log
      val bumps     = orFail(PinEngine.outdated(feeds))
      log.info(s"zipx pin update:\n${PinEngine.formatBumps(bumps)}")
      if bumps.isEmpty then ()
      else
        val applyNow = arg match
          case "yes" | "--yes"         => true
          case "dry-run" | "--dry-run" => false
          case ""                      =>
            confirmPinUpdates(bumps.size) match
              case None =>
                log.info("zipx: no TTY; pass 'yes' to apply, or 'dry-run' to list only.")
                false
              case Some(ok) => ok
          case other =>
            sys.error(s"zipx: unknown zipxPinUpdate argument '$other' (yes or dry-run)")
        if applyNow then
          val applied = orFail(PinEngine.applyBumps(feeds, bumps))
          log.info(s"zipx: applied ${applied.size} pin update(s)")
        else log.info("zipx: no pin updates applied")
      end if
    }

  /** `Some(true)` if the operator confirmed, `Some(false)` if they declined, `None` if there is no console to ask. */
  private def confirmPinUpdates(n: Int): Option[Boolean] =
    Option(System.console()).map { console =>
      val line = Option(console.readLine(s"Apply $n pin update(s)? [y/N] ")).getOrElse("").trim
      line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes")
    }

  private def pinSubmitTask: Def.Initialize[Task[Unit]] = Def.task {
    val extracted = Project.extract(state.value)
    val feeds     = readBuildSetting(extracted, zipxPinFeeds, Seq.empty).filter(_.submitSnapshot)
    if feeds.isEmpty then streams.value.log.info("zipx: no pin feeds opted into snapshot submit")
    else
      val token = sys.env.getOrElse("GITHUB_TOKEN", sys.error("zipx: GITHUB_TOKEN is required for zipxPinSubmit"))
      val repo  =
        sys.env.getOrElse("GITHUB_REPOSITORY", sys.error("zipx: GITHUB_REPOSITORY is required for zipxPinSubmit"))
      val sha   = sys.env.getOrElse("GITHUB_SHA", sys.error("zipx: GITHUB_SHA is required for zipxPinSubmit"))
      val ref   = sys.env.getOrElse("GITHUB_REF", sys.error("zipx: GITHUB_REF is required for zipxPinSubmit"))
      val jobId = sys.env.getOrElse("GITHUB_RUN_ID", "local")
      val body  = PinSnapshot.render(feeds, sha, ref, jobId, java.time.Instant.now.toString, "zipx")
      orFail(PinSnapshot.submit(token, repo, body))
      streams.value.log.info("zipx: submitted pin snapshot")
    end if
  }

  private def inventoryAtBase(root: File, sha: String, log: Logger): Map[PinFeedName, List[PinnedDep]] =
    import scala.sys.process.*
    val work = root / "target" / "zipx-pin-base"
    if work.exists then Process(Seq("git", "worktree", "remove", "--force", work.getAbsolutePath), root).!
    Process(Seq("git", "fetch", "--no-tags", "origin", sha), root).!
    val add = Process(Seq("git", "worktree", "add", "--detach", work.getAbsolutePath, sha), root).!
    if add != 0 then sys.error(s"zipx: could not create pin-check worktree at $sha")
    log.info(s"zipx: evaluating pin inventory at $sha")
    val code = Process(Seq("sbt", "-batch", "zipxPinInventory"), work).!
    if code != 0 then sys.error("zipx: zipxPinInventory failed in the pin-check base worktree")
    val jsonFile = work / PinInventory.RelPath
    if !jsonFile.exists then sys.error(s"zipx: missing ${jsonFile.getPath}")
    orFail(PinInventory.parse(IO.read(jsonFile)))
  end inventoryAtBase

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
    val base        = ActionPinFile.loadOption(pinPath).fold(ActionPins.Defaults)(orFail)
    val wfYaml      = IO.read(wfFile)
    val actionYamls =
      ZipxComposites.artifacts(base, planConfig.value.cacheEpoch).toOption.toList.flatMap(_.keys).flatMap { rel =>
        val f = root / rel
        if f.exists then Some(IO.read(f)) else None
      }
    val pulled = orFail(
      actionYamls.foldLeft[Either[String, ActionPins]](ActionPinFile.pullFromWorkflow(wfYaml, base)) { (acc, yaml) =>
        acc.flatMap(pins => ActionPinFile.pullFromWorkflow(yaml, pins))
      }
    )
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
    val cfg             = planConfig.value
    val root            = (LocalRootProject / baseDirectory).value
    val expectedActions = orFail(ZipxComposites.artifacts(cfg.actions, cfg.cacheEpoch))
    expectedActions.foreach { (rel, expectedBody) =>
      val file   = root / rel
      val actual = if file.exists then IO.read(file) else ""
      if actual != expectedBody then
        sys.error(
          s"${file.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result."
        )
      streams.value.log.info(s"zipx: ${file.getPath} is up to date.")
    }
    val extracted = Project.extract(state.value)
    if readBuildSetting(extracted, zipxDependabotSync, false) then
      val syncFile     = root / ActionPinsSyncWorkflow.DefaultPath
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
      val stewardFile = root / ScalaStewardWorkflow.DefaultPath
      val maybeConf   = stewardGrouping(extracted)
      val configPath  = maybeConf.map(_ => ScalaStewardWorkflow.DefaultConfigPath)
      warnDeadRepoRootStewardGrouping(root, streams.value.log)
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
    checkPinWorkflows(root, cfg, extracted, streams.value.log)
    validateCatalog(extracted)
    checkCatalog(root, extracted, streams.value.log)
  }

  private def writeCatalogIfEnabled: Def.Initialize[Task[Unit]] = Def.task {
    val extracted = Project.extract(state.value)
    val root      = (LocalRootProject / baseDirectory).value
    syncCatalogFiles(extracted, root, streams.value.log, write = true)
  }

  private def checkCatalog(root: File, extracted: Extracted, log: Logger): Unit =
    syncCatalogFiles(extracted, root, log, write = false)

  private def validateCatalog(extracted: Extracted): Unit =
    val coords = readBuildSetting(extracted, zipxVersions, Seq.empty)
    val check  = readBuildSetting(extracted, zipxCheckDeps, false)
    val scalaV = readBuildSetting(extracted, zipxScala, None)
    if check && coords.isEmpty then
      sys.error(
        "zipx: zipxCheckDeps is true but zipxVersions is empty. Add Lib / Plugin rows, or set zipxCheckDeps := false."
      )
    if check then
      ZipxCatalog.scalaMismatch(declaredScalaVersion(extracted), scalaV).foreach(sys.error)
      val extra = ZipxCatalog.extraLibs(declaredGavs(extracted), coords)
      if extra.nonEmpty then
        sys.error(
          s"zipx: libraryDependencies not in zipxVersions: ${extra.map(_.render).mkString(", ")}. Add a Lib row or select via ZipxDeps."
        )
  end validateCatalog

  private def syncCatalogFiles(extracted: Extracted, root: File, log: Logger, write: Boolean): Unit =
    val coords  = readBuildSetting(extracted, zipxVersions, Seq.empty)
    val sbtVer  = readBuildSetting(extracted, zipxSbt, None)
    val plugins = ZipxCatalog.plugins(coords)
    val self    = if coords.nonEmpty then loadedZipxPlugin(extracted) else None
    if plugins.nonEmpty || self.isDefined then
      val expected = ZipxCatalog.renderPlugins(plugins, self)
      val file     = root / ZipxCatalog.PluginsPath
      if write then
        IO.write(file, expected)
        log.info(s"zipx wrote ${file.getPath}")
      else
        val actual = if file.exists then IO.read(file) else ""
        if actual != expected then
          sys.error(s"${file.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result.")
        log.info(s"zipx: ${file.getPath} is up to date.")
    end if
    sbtVer.foreach { ver =>
      val expected = ZipxCatalog.renderBuildProperties(ver)
      val file     = root / ZipxCatalog.BuildPropertiesPath
      if write then
        IO.write(file, expected)
        log.info(s"zipx wrote ${file.getPath}")
      else
        val actual = if file.exists then IO.read(file) else ""
        if actual != expected then
          sys.error(s"${file.getPath} is out of date. Run 'sbt zipxWorkflowGenerate' and commit the result.")
        log.info(s"zipx: ${file.getPath} is up to date.")
    }
  end syncCatalogFiles

  private def loadedZipxPlugin(extracted: Extracted): Option[Plugin] =
    if !readBuildSetting(extracted, zipxEmitSelf, true) then None
    else
      val ver =
        readBuildSetting(extracted, zipxPluginVersion, None)
          .orElse(sys.props.get("plugin.version"))
          .orElse(Option(getClass.getPackage).flatMap(p => Option(p.getImplementationVersion)))
          .filter(_.nonEmpty)
      ver match
        case Some(v) =>
          Some(zipx.core.Plugin(GroupId("rocks.earlyeffect"), ArtifactId("sbt-zipx"), DepVersion.unsafeMake(v), Nil))
        case None =>
          sys.error(
            "zipx: zipxEmitSelf is true but the sbt-zipx version is unknown. Set zipxPluginVersion, or zipxEmitSelf := false when dogfooding from source."
          )

  private def declaredScalaVersion(extracted: Extracted): String =
    extracted.getOpt(ThisBuild / scalaVersion).orElse(extracted.getOpt(LocalRootProject / scalaVersion)).getOrElse("")

  private def declaredGavs(extracted: Extracted): List[DeclaredGav] =
    extracted.structure.allProjectRefs.toList
      .flatMap(ref => extracted.getOpt(ref / libraryDependencies).toList.flatten)
      .filterNot(isIgnoredDeclared)
      .map(m => DeclaredGav(m.organization, m.name, m.revision))
      .distinct

  private def isIgnoredDeclared(m: ModuleID): Boolean =
    isSbtPluginModule(m) ||
      ZipxCatalog.isAutoPlatform(m.organization, m.name) ||
      m.organization == "org.scala-sbt" ||
      m.configurations.exists { c =>
        val x = c.toLowerCase
        x == "provided" || x.contains("plugin")
      }

  private def isSbtPluginModule(m: ModuleID): Boolean =
    m.extraAttributes.keys.exists(k => k.contains("sbtVersion")) ||
      // sbt 2 `addSbtPlugin` uses CrossVersion.binaryWith("sbt2_", "") instead of extraAttributes.
      (m.crossVersion match
        case b: _root_.sbt.librarymanagement.Binary => b.prefix.startsWith("sbt")
        case _                                      => false)

  private def depUpdateTask: Def.Initialize[InputTask[Unit]] =
    Def.inputTask {
      val arg       = sbt.complete.DefaultParsers.trimmed(sbt.complete.DefaultParsers.any.*.string).parsed.trim
      val extracted = Project.extract(state.value)
      val coords    = readBuildSetting(extracted, zipxVersions, Seq.empty)
      val log       = streams.value.log
      if coords.isEmpty then log.info("zipx: zipxVersions is empty; nothing to update")
      else
        val scalaBin = (LocalRootProject / scalaBinaryVersion).value
        val sbtBin   = sbtBinaryVersion.value
        val bumps    = orFail(ZipxCatalog.outdated(coords, c => MavenMetadata.latest(c, scalaBin, sbtBin)))
        log.info(s"zipx dep update:\n${ZipxCatalog.formatBumps(bumps)}")
        if bumps.isEmpty then ()
        else
          val applyNow = arg match
            case "yes" | "--yes"         => true
            case "dry-run" | "--dry-run" => false
            case ""                      =>
              confirmPinUpdates(bumps.size) match
                case None =>
                  log.info("zipx: no TTY; pass 'yes' to apply, or 'dry-run' to list only.")
                  false
                case Some(ok) => ok
            case other =>
              sys.error(s"zipx: unknown zipxDepUpdate argument '$other' (yes or dry-run)")
          if applyNow then
            val rel  = readBuildSetting(extracted, zipxVersionsFile, ZipxCatalog.DefaultVersionsFile)
            val file = (LocalRootProject / baseDirectory).value / rel
            if !file.exists then sys.error(s"zipx: catalog file ${file.getPath} is missing")
            val next = orFail(ZipxCatalog.applyBumps(IO.read(file), bumps))
            IO.write(file, next)
            log.info(s"zipx: wrote ${file.getPath}")
          else log.info("zipx: no catalog updates applied")
        end if
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
