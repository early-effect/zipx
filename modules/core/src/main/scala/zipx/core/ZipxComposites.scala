package zipx.core

import neotype.unwrap
import zipx.workflow.*

import scala.collection.immutable.ListMap

/** Generated in-repo composite actions that factor repeated CI step bundles.
  *
  * Written next to `ci.yml` by `zipxWorkflowGenerate` / checked by `zipxWorkflowCheck`. Nested third-party actions stay
  * SHA-pinned via [[ActionPins]]; the local `uses: ./.github/actions/…` refs need no pin.
  */
object ZipxComposites:

  val ActionsDir: String = ".github/actions"

  val SbtSetupName: String = "zipx-sbt-setup"
  val AwsLoginName: String = "zipx-aws-login"

  val SbtSetupPath: String = s"$ActionsDir/$SbtSetupName/action.yml"
  val AwsLoginPath: String = s"$ActionsDir/$AwsLoginName/action.yml"

  val SbtSetupRef: ActionRef = ActionRef("./.github/actions/zipx-sbt-setup")
  val AwsLoginRef: ActionRef = ActionRef("./.github/actions/zipx-aws-login")

  /** Input expression `${{ inputs.<name> }}` for composite `with:` / `run:` templates. */
  private def input(name: String): String = s"$${{ inputs.$name }}"

  /** All composite `action.yml` files to write, relative to the build root. */
  def artifacts(
      pins: ActionPins,
      cacheEpoch: CacheEpoch = CacheEpoch.GitTags(),
  ): Either[String, ListMap[String, String]] =
    for
      setup <- renderSbtSetup(pins, cacheEpoch)
      aws   <- renderAwsLogin(pins)
    yield ListMap(SbtSetupPath -> setup, AwsLoginPath -> aws)

  def renderSbtSetup(pins: ActionPins, cacheEpoch: CacheEpoch = CacheEpoch.GitTags()): Either[String, String] =
    Render.renderComposite(sbtSetup(pins, cacheEpoch)).map(ActionPinFile.annotateUses(_, pins))

  def renderAwsLogin(pins: ActionPins): Either[String, String] =
    Render.renderComposite(awsLogin(pins)).map(ActionPinFile.annotateUses(_, pins))

  /** Checkout + JDK + sbt (+ optional Node) + optional LocalDir cache, parameterized for every sbt job. */
  def sbtSetup(pins: ActionPins, cacheEpoch: CacheEpoch = CacheEpoch.GitTags()): CompositeAction =
    val resolveScript = cacheEpoch match
      case CacheEpoch.GitTags(tagMatch) => CacheEpoch.gitTagsResolveScript(tagMatch)
      case CacheEpoch.Script(run, _)    => run
      case CacheEpoch.Fixed(_)          => CacheEpoch.gitTagsResolveScript()
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

    val steps: List[Step] = List(
      Step(
        uses = Some(pins.checkout),
        `with` = ListMap(
          "fetch-depth" -> input("fetch-depth"),
          "fetch-tags"  -> input("fetch-tags"),
        ),
      ),
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
        name = Some("Setup Node"),
        `if` = Some("inputs.node-version != ''"),
        uses = Some(pins.setupNode),
        `with` = ListMap("node-version" -> input("node-version")),
      ),
      Step(
        id = Some(resolveId),
        name = Some("Resolve cache epoch"),
        `if` = Some("inputs.local-cache == 'true' && inputs.cache-epoch == ''"),
        run = Some(resolveScript),
        shell = Some("bash"),
      ),
      Step(
        name = Some("Cache sbt"),
        `if` = Some("inputs.local-cache == 'true' && inputs.cache-epoch == ''"),
        uses = Some(pins.cache),
        `with` = ListMap(
          "path"         -> cachePaths,
          "key"          -> s"$prefix$epochOut-$runId-$keySuffix",
          "restore-keys" -> List(
            s"$prefix$epochOut-$runId-",
            s"$prefix$epochOut-",
            s"$prefix$releaseOut-",
            prefix,
          ).mkString("\n"),
        ),
      ),
      Step(
        name = Some("Cache sbt"),
        `if` = Some("inputs.local-cache == 'true' && inputs.cache-epoch != ''"),
        uses = Some(pins.cache),
        `with` = ListMap(
          "path"         -> cachePaths,
          "key"          -> s"$prefix$fixedEpoch-$runId-$keySuffix",
          "restore-keys" -> List(
            s"$prefix$fixedEpoch-$runId-",
            s"$prefix$fixedEpoch-",
            // Prior-release fallback is resolved at generate time when the caller passes a Fixed epoch; for a runtime
            // input we cannot strip -ci here, so callers that need it pass the release epoch as a separate restore via
            // regenerate. The common GitTags path above already restores from steps.cache-epoch.outputs.release.
            prefix,
          ).mkString("\n"),
        ),
      ),
    )

    CompositeAction(
      name = "zipx sbt setup",
      description =
        "Checkout, JDK, sbt, optional Node, and LocalDir sbt cache. Generated by zipx; do not edit by hand.",
      inputs = ListMap(
        "java-version"     -> CompositeInput("Temurin JDK version", required = true),
        "runner-os"        -> CompositeInput("Runner OS label used in the cache key prefix", required = true),
        "cache-key-suffix" -> CompositeInput(
          "Per-job suffix so same-run jobs do not race on one cache key",
          required = true,
        ),
        "node-version"   -> CompositeInput("Optional Node version; empty skips setup-node", default = Some("")),
        "sbt-disk-cache" -> CompositeInput("Passed to sbt/setup-sbt disk-cache", default = Some("false")),
        "fetch-depth"    -> CompositeInput("actions/checkout fetch-depth", default = Some("0")),
        "fetch-tags"     -> CompositeInput("actions/checkout fetch-tags", default = Some("true")),
        "local-cache"    -> CompositeInput(
          "When true, resolve epoch and restore/save the LocalDir sbt cache",
          default = Some("true"),
        ),
        "cache-epoch" -> CompositeInput(
          "Fixed cache epoch; when non-empty skips git-tag resolve and keys the cache with this value",
          default = Some(""),
        ),
      ),
      steps = steps,
    )
  end sbtSetup

  /** OIDC assume-role plus optional ECR docker login, reading role/region/account from job env (or alternate keys). */
  def awsLogin(pins: ActionPins): CompositeAction =
    val credentials = pins.extraRef(CredentialsPinKey).getOrElse(DefaultCredentials)
    val ecrLogin    = pins.extraRef(EcrLoginPinKey).getOrElse(DefaultEcrLogin)
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
  val CredentialsPinKey: String = "configureAwsCredentials"
  val EcrLoginPinKey: String    = "amazonEcrLogin"

  private val DefaultCredentials: ActionRef =
    ActionRef("aws-actions/configure-aws-credentials@e6de054238d6b7531b4efff3b6587d9aade6a06c")
  private val DefaultEcrLogin: ActionRef =
    ActionRef("aws-actions/amazon-ecr-login@d539f0932e70871a027e9d5a9d8fc38589180a64")

  /** One workflow step that invokes [[SbtSetupRef]] with the planner's LocalDir (or no-cache) settings. */
  def sbtSetupStep(
      config: PlanConfig,
      jobSuffix: JobId,
      nodeVersion: Option[NodeVersion],
      localCache: Boolean,
  ): Step =
    val fixedEpoch = config.cacheEpoch match
      case CacheEpoch.Fixed(value) => value
      case _                       => ""
    val diskCache =
      if config.cache == CacheBackend.LocalDir then "false"
      else "true"
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
          "local-cache"      -> (if localCache then "true" else "false"),
          "cache-epoch"      -> fixedEpoch,
        )
      )
      .build
  end sbtSetupStep

  /** One workflow step that invokes [[AwsLoginRef]]. */
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
