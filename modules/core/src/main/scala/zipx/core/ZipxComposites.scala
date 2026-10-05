package zipx.core

import neotype.unwrap
import zipx.workflow.*

import scala.collection.immutable.ListMap

/** Generated in-repo composite actions. Nested third-party actions stay SHA-pinned via [[ActionPins]]; the local
  * `uses: ./.github/actions/…` refs need no pin.
  */
object ZipxComposites:

  val ActionsDir: String = ".github/actions"

  val SbtSetupName: String = "zipx-sbt-setup"
  val AwsLoginName: String = "zipx-aws-login"

  val SbtSetupPath: String = s"$ActionsDir/$SbtSetupName/action.yml"
  val AwsLoginPath: String = s"$ActionsDir/$AwsLoginName/action.yml"

  val SbtSetupRef: ActionRef = ActionRef("./.github/actions/zipx-sbt-setup")
  val AwsLoginRef: ActionRef = ActionRef("./.github/actions/zipx-aws-login")

  private def input(name: String): String = s"$${{ inputs.$name }}"

  /** Paths are relative to the build root. `includeAwsLogin` defaults off so a build without ZipxAws commits no ECR
    * login composite.
    */
  def artifacts(
      pins: ActionPins,
      cacheEpoch: CacheEpoch = CacheEpoch.GitTags(),
      includeAwsLogin: Boolean = false,
  ): Either[String, ListMap[String, String]] =
    for
      setup <- renderSbtSetup(pins, cacheEpoch)
      files <-
        if includeAwsLogin then renderAwsLogin(pins).map(aws => ListMap(SbtSetupPath -> setup, AwsLoginPath -> aws))
        else Right(ListMap(SbtSetupPath -> setup))
    yield files

  def usesAwsLogin(wf: Workflow): Boolean =
    wf.jobs.values.exists(_.steps.exists(_.uses.contains(AwsLoginRef)))

  def leftoverAwsLoginMessage: String =
    s"zipx: $AwsLoginPath is unused. This workflow does not use zipx-aws-login. Run sbt zipxWorkflowGenerate and commit the deletion."

  def renderSbtSetup(pins: ActionPins, cacheEpoch: CacheEpoch = CacheEpoch.GitTags()): Either[String, String] =
    Render.renderComposite(sbtSetup(pins, cacheEpoch)).map(ActionPinFile.annotateUses(_, pins))

  def renderAwsLogin(pins: ActionPins): Either[String, String] =
    Render.renderComposite(awsLogin(pins)).map(ActionPinFile.annotateUses(_, pins))

  /** No checkout inside: GitHub resolves `uses: ./.github/actions/…` from the workspace before the composite runs, so
    * checkout must be a prior workflow step ([[Planner.checkoutThenSbtSetup]]).
    */
  def sbtSetup(pins: ActionPins, cacheEpoch: CacheEpoch = CacheEpoch.GitTags()): CompositeAction =
    val resolveScript = cacheEpoch match
      case CacheEpoch.GitTags(tagMatch)                 => CacheEpoch.gitTagsResolveScript(tagMatch)
      case CacheEpoch.Script(run, _)                    => run
      case CacheEpoch.Fixed(_) | CacheEpoch.ShipCatalog => CacheEpoch.gitTagsResolveScript()
    val resolveId = cacheEpoch match
      case CacheEpoch.Script(_, stepId) => stepId.unwrap
      case _                            => CacheEpoch.GitTagsStepId.unwrap

    val cachePaths = List("~/.sbt", "~/.cache/sbt", "~/.cache/coursier", "target").mkString("\n")
    val prefix     = s"${input("runner-os")}-jdk${input("java-version")}-sbt-"
    val epochOut   = s"$${{ steps.$resolveId.outputs.epoch }}"
    val releaseOut = s"$${{ steps.$resolveId.outputs.release }}"
    val fixedEpoch = input("cache-epoch")
    val keySuffix  = input("cache-key-suffix")
    val runId      = "${{ github.run_id }}"

    // Only `build` snapshots are saved, so a restore never picks up another job's partial one. Save and restore share
    // keys, so an earlier Layer wave's save warms the next wave through the same-run key.
    //
    // Under `purge` the primary key includes the run id, so a save with no restore-keys misses and still writes a fresh
    // snapshot. A restore job skips its cache step, so it cannot pull the old entry while that save is in flight.
    def cacheStep(mode: LocalCacheMode, resolved: Boolean, purge: Boolean): Step =
      val epoch = if resolved then epochOut else fixedEpoch
      val build = s"$prefix$epoch-build-"
      // A Fixed epoch is baked at generate time, so there is no runtime release output to fall back to.
      val older     = if resolved then List(s"$prefix$releaseOut-build-", prefix) else List(prefix)
      val whenPurge = if purge then "inputs.purge == 'true'" else "inputs.purge != 'true'"
      val epochOp   = if resolved then "==" else "!="
      val cached    = ListMap(
        "path" -> cachePaths,
        "key"  -> s"$build$runId-$keySuffix",
      )
      Step(
        name = Some(
          if mode == LocalCacheMode.Save && purge then "Save sbt cache"
          else if mode == LocalCacheMode.Save then "Cache sbt"
          else "Restore sbt cache"
        ),
        `if` = Some(s"inputs.cache-mode == '${mode.input}' && $whenPurge && inputs.cache-epoch $epochOp ''"),
        uses = Some(if mode == LocalCacheMode.Save then pins.cache else pins.cacheRestore),
        `with` =
          if purge then cached
          else cached + ("restore-keys" -> (s"$build$runId-" :: build :: older).mkString("\n")),
      )
    end cacheStep

    val steps: List[Step] = List(
      Step(
        name = Some("Setup JDK"),
        uses = Some(pins.setupJava),
        `with` = ListMap(
          "distribution" -> "temurin",
          "java-version" -> input("java-version"),
        ),
      ),
      Step(
        uses = Some(pins.setupSbt),
        `with` = ListMap("disk-cache" -> input("sbt-disk-cache")),
      ),
      Step(
        name = Some("Setup Coursier"),
        `if` = Some("inputs.coursier == 'true'"),
        uses = Some(pins.extraByPrefix(CoursierSetupPinKey).getOrElse(DefaultCoursierSetup)),
        `with` = ListMap("apps" -> "cs"),
      ),
      Step(
        name = Some("Setup Node"),
        `if` = Some("inputs.node-version != ''"),
        uses = Some(pins.setupNode),
        `with` = ListMap("node-version" -> input("node-version")),
      ),
      Step(
        id = Some(resolveId),
        name = Some("Resolve cache epoch"),
        // A purged restore has nothing to key. A purged save still needs the epoch in its primary key.
        `if` = Some(
          "inputs.cache-epoch == '' && inputs.cache-mode != 'off' && " +
            "(inputs.cache-mode == 'save' || inputs.purge != 'true')"
        ),
        run = Some(resolveScript),
        shell = Some("bash"),
      ),
      cacheStep(LocalCacheMode.Save, resolved = true, purge = false),
      cacheStep(LocalCacheMode.Save, resolved = false, purge = false),
      cacheStep(LocalCacheMode.Save, resolved = true, purge = true),
      cacheStep(LocalCacheMode.Save, resolved = false, purge = true),
      cacheStep(LocalCacheMode.Restore, resolved = true, purge = false),
      cacheStep(LocalCacheMode.Restore, resolved = false, purge = false),
    )

    CompositeAction(
      name = "zipx sbt setup",
      description =
        "JDK, sbt, optional Node, and LocalDir sbt cache (checkout is a prior workflow step). Generated by zipx; do not edit by hand.",
      inputs = ListMap(
        "java-version"     -> CompositeInput("Temurin JDK version", required = true),
        "runner-os"        -> CompositeInput("Runner OS label used in the cache key prefix", required = true),
        "cache-key-suffix" -> CompositeInput(
          "Per-job suffix so same-run saves (one per Layer wave) do not race on one cache key",
          required = true,
        ),
        "node-version"   -> CompositeInput("Optional Node version; empty skips setup-node", default = Some("")),
        "sbt-disk-cache" -> CompositeInput("Passed to sbt/setup-sbt disk-cache", default = Some("false")),
        "cache-mode"     -> CompositeInput(
          "LocalDir sbt cache: save (restore, then save this job's build snapshot), restore (restore only), or off",
          default = Some(LocalCacheMode.Restore.input),
        ),
        "purge" -> CompositeInput(
          "When true, skip LocalDir restore. A save owner still saves a fresh snapshot; a restore job does nothing",
          default = Some("false"),
        ),
        "cache-epoch" -> CompositeInput(
          "Fixed cache epoch; when non-empty skips git-tag resolve and keys the cache with this value",
          default = Some(""),
        ),
        "coursier" -> CompositeInput(
          "When true, install cs via coursier/setup-action so the job can launch zipx-cli",
          default = Some("false"),
        ),
      ),
      steps = steps,
    )
  end sbtSetup

  def awsLogin(pins: ActionPins): CompositeAction =
    val credentials = pins.extraByPrefix(CredentialsPinKey).getOrElse(DefaultCredentials)
    val ecrLogin    = pins.extraByPrefix(EcrLoginPinKey).getOrElse(DefaultEcrLogin)
    CompositeAction(
      name = "zipx AWS login",
      description = "Assume an AWS role via OIDC and optionally log in to ECR. Generated by zipx; do not edit by hand.",
      inputs = ListMap(
        "role-env"    -> CompositeInput("Env var holding the role ARN", default = Some("AWS_ROLE_TO_ASSUME")),
        "region-env"  -> CompositeInput("Env var holding the AWS region", default = Some("AWS_REGION")),
        "account-env" -> CompositeInput(
          "Env var holding the 12-digit account id for ECR",
          default = Some("AWS_ACCOUNT_ID"),
        ),
        "login-ecr"   -> CompositeInput("When true, also run amazon-ecr-login", default = Some("true")),
        "name-suffix" -> CompositeInput("Optional label suffix for step names", default = Some("")),
      ),
      steps = List(
        Step(
          name = Some("Assume AWS role (OIDC)"),
          uses = Some(credentials),
          `with` = ListMap(
            "role-to-assume" -> s"$${{ env[inputs.role-env] }}",
            "aws-region"     -> s"$${{ env[inputs.region-env] }}",
          ),
        ),
        Step(
          name = Some("Log in to ECR"),
          `if` = Some("inputs.login-ecr == 'true'"),
          uses = Some(ecrLogin),
          `with` = ListMap("registries" -> s"$${{ env[inputs.account-env] }}"),
        ),
      ),
    )
  end awsLogin

  // Pin keys mirrored from zipx-aws so core does not depend on that module. Keep the strings identical.
  val CredentialsPinKey: String   = "aws-actions/configure-aws-credentials"
  val EcrLoginPinKey: String      = "aws-actions/amazon-ecr-login"
  val CoursierSetupPinKey: String = "coursier/setup-action"

  private val DefaultCredentials: ActionRef =
    ActionRef("aws-actions/configure-aws-credentials@e6de054238d6b7531b4efff3b6587d9aade6a06c")
  private val DefaultEcrLogin: ActionRef =
    ActionRef("aws-actions/amazon-ecr-login@d539f0932e70871a027e9d5a9d8fc38589180a64")
  private val DefaultCoursierSetup: ActionRef =
    ActionRef("coursier/setup-action@9b7939bf01fd1185ce2babe16135168361bf2c62")

  def sbtSetupStep(
      config: PlanConfig,
      jobSuffix: JobId,
      nodeVersion: Option[NodeVersion],
      cacheMode: LocalCacheMode,
  ): Step =
    val fixedEpoch = config.cacheEpoch match
      case CacheEpoch.Fixed(value) => value
      case CacheEpoch.ShipCatalog  => config.shipEpochHash.getOrElse("")
      case _                       => ""
    val diskCache =
      if config.cache == CacheBackend.LocalDir then "false"
      else "true"
    val purge = cachePurgeInput(config, cacheMode)
    Step
      .usesRef(SbtSetupRef)
      .named("zipx sbt setup")
      .withInputs(
        ListMap(
          "java-version"     -> config.javaVersion,
          "runner-os"        -> config.runnerOs,
          "cache-key-suffix" -> (jobSuffix: String),
          "node-version"     -> nodeVersion.getOrElse(""),
          "sbt-disk-cache"   -> diskCache,
          "cache-mode"       -> cacheMode.input,
        ) ++ purge.fold(ListMap.empty[String, String])(value => ListMap("purge" -> value)) ++ ListMap(
          "cache-epoch" -> fixedEpoch
        )
      )
      .build
  end sbtSetupStep

  /** Omitted when this job has no LocalDir restore to skip. */
  private def cachePurgeInput(config: PlanConfig, cacheMode: LocalCacheMode): Option[String] =
    config.cachePurgeLabel match
      case Some(label) if config.cache == CacheBackend.LocalDir && cacheMode != LocalCacheMode.Off =>
        Some(PlanConfig.pullRequestHasLabel(label).render)
      case _ =>
        None

  def awsLoginStep(
      roleEnv: String = "AWS_ROLE_TO_ASSUME",
      regionEnv: String = "AWS_REGION",
      accountEnv: String = "AWS_ACCOUNT_ID",
      loginEcr: Boolean = true,
      nameSuffix: String = "",
  ): Step =
    val named =
      if nameSuffix.isEmpty then "zipx AWS login"
      else s"zipx AWS login ($nameSuffix)"
    Step
      .usesRef(AwsLoginRef)
      .named(named)
      .withInputs(
        ListMap(
          "role-env"    -> roleEnv,
          "region-env"  -> regionEnv,
          "account-env" -> accountEnv,
          "login-ecr"   -> (if loginEcr then "true" else "false"),
          "name-suffix" -> nameSuffix,
        )
      )
      .build
  end awsLoginStep

end ZipxComposites
