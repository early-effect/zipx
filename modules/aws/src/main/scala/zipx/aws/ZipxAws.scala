package zipx.aws

import neotype.unwrap
import zipx.core.*
import zipx.workflow.{ActionRef, EnvName}

/** Holds no credentials or account numbers of its own. The login bundle reads its role and region from the job's
  * `env:`, so one bundle serves every destination: a per-target `env` ([[zipx.core.Target.env]]) changes which account
  * the same steps log into.
  */
object ZipxAws:

  val Role: EnvName = EnvName("AWS_ROLE_TO_ASSUME")

  /** Named as the AWS CLI names it, so a `run:` step in the same job picks it up with no extra wiring. */
  val Region: EnvName = EnvName("AWS_REGION")

  val Registry: EnvName = EnvName("AWS_ECR_REGISTRY")

  /** `amazon-ecr-login` reads this as `registries:`. */
  val Account: EnvName = EnvName("AWS_ACCOUNT_ID")

  // As plain strings, for a `build.sbt` writing its own `env:` block.
  val RoleEnv: String     = Role.unwrap
  val RegionEnv: String   = Region.unwrap
  val RegistryEnv: String = Registry.unwrap
  val AccountEnv: String  = Account.unwrap

  /** OIDC needs `id-token: write`; `contents: read` is what the checkout still needs once permissions are declared
    * explicitly, since naming any permission drops the default set.
    */
  val oidcPermissions: Map[String, String] = Map("id-token" -> "write", "contents" -> "read")

  /** An extra pin rather than a typed `ActionPins.Field`: zipx's own planner never emits this step, so pinning it must
    * not require a zipx release. An extra pin's ref is checked for being pinned, not for naming this action.
    */
  val CredentialsPinKey: String = "aws-actions/configure-aws-credentials"

  /** The fallback when the consumer's catalog has no `Action` row for [[CredentialsPinKey]]; add one to own the
    * version.
    */
  val DefaultCredentialsAction: ActionRef =
    ActionRef("aws-actions/configure-aws-credentials@e6de054238d6b7531b4efff3b6587d9aade6a06c")

  def credentialsAction(pins: ActionPins): ActionRef =
    pins.extraByPrefix(CredentialsPinKey).getOrElse(DefaultCredentialsAction)

  def withCredentialsPin(pins: ActionPins, ref: ActionRef, version: Option[String] = None): ActionPins =
    pins.withExtra(CredentialsPinKey, ref, version)

  /** Always passes `aws-region` with `role-to-assume`: without it `configure-aws-credentials` fails on the runner with
    * a credentials error that sends the reader to the role and trust policy instead of the missing input.
    */
  val oidcLoginSteps: Steps = Steps.one("aws-oidc-login") { _ =>
    ZipxComposites.awsLoginStep(loginEcr = false)
  }

  /** The pin key and fallback for `aws-actions/amazon-ecr-login`, on the same terms as [[CredentialsPinKey]]. */
  val EcrLoginPinKey: String = "aws-actions/amazon-ecr-login"

  val DefaultEcrLoginAction: ActionRef =
    ActionRef("aws-actions/amazon-ecr-login@d539f0932e70871a027e9d5a9d8fc38589180a64")

  def ecrLoginAction(pins: ActionPins): ActionRef =
    pins.extraByPrefix(EcrLoginPinKey).getOrElse(DefaultEcrLoginAction)

  /** Required for any push through the docker CLI, including sbt-native-packager's `Docker / publish`. `registries:`
    * comes from [[AccountEnv]], so a multi-account job gets one credential entry per host rather than relying on
    * whichever role was assumed last.
    */
  val ecrLoginSteps: Steps = Steps.one("aws-ecr-login") { _ =>
    ZipxComposites.awsLoginStep(loginEcr = true)
  }

  /** The `env:` a job needs for [[ecrLoginSteps]]. */
  def registryEnv(registry: EcrRegistry, role: EnvValue): Map[String, EnvValue] = Map(
    RoleEnv     -> role,
    RegionEnv   -> EnvValue.plain(registry.region),
    RegistryEnv -> EnvValue.plain(registry.host),
    AccountEnv  -> EnvValue.plain(registry.accountId),
  )

  /** For a job whose registry is one repository rather than a whole account: [[RegistryEnv]] carries the image URI. */
  def imageEnv(image: EcrImage, role: EnvValue): Map[String, EnvValue] = Map(
    RoleEnv     -> role,
    RegionEnv   -> EnvValue.plain(image.registry.region),
    RegistryEnv -> EnvValue.plain(image.uri),
    AccountEnv  -> EnvValue.plain(image.registry.accountId),
  )

  /** For a capability that really wants a job per registry (separate accounts with separate approvals, say). For an
    * ordinary multi-registry push that is one job per registry per module, each rebuilding the same image; use
    * [[dockerPublishAll]] instead.
    */
  def registryTargets(registries: List[(TargetName, EcrRegistry, EnvValue)]): List[Target] =
    registries.map((name, registry, role) => Target(name = name, env = registryEnv(registry, role)))

  /** Once per destination, for a [[zipx.core.TargetFanOut.SharedJob]] capability. The last `configure-aws-credentials`
    * wins ambient AWS credentials; each `amazon-ecr-login` adds a `~/.docker/config.json` entry for its own host.
    */
  val sharedLoginSteps: Steps = Steps("aws-ecr-login-per-destination") { ctx =>
    ctx.destinations.map { target =>
      ZipxComposites.awsLoginStep(
        roleEnv = target.envName(Role).unwrap,
        regionEnv = target.envName(Region).unwrap,
        accountEnv = target.envName(Account).unwrap,
        loginEcr = true,
        nameSuffix = target.name: String,
      )
    }
  }

  def dockerPublish(
      registry: EcrRegistry,
      role: EnvValue,
      name: CapabilityName = Capability.DockerName,
      scope: CapabilityScope = CapabilityScope.Aggregate,
      condition: Option[JobCondition] = None,
  ): Capability =
    val base = scope match
      case CapabilityScope.Layer => Capability.dockerLayers
      case CapabilityScope.Graph => Capability.dockerGraph
      case _                     => Capability.docker
    base.copy(
      name = name,
      permissions = oidcPermissions,
      env = registryEnv(registry, role),
      extraSteps = ecrLoginSteps,
      condition = condition,
    )
  end dockerPublish

  /** [[dockerPublish]] over several registries in one job. One build is pushed, so the registries hold identical bytes.
    * Build `dockerAliases` with [[EcrImage.taggedAll]] from the same [[EcrRegistry]] values so the two sides cannot
    * drift.
    */
  def dockerPublishAll(
      registries: List[(TargetName, EcrRegistry, EnvValue)],
      name: CapabilityName = Capability.DockerName,
      scope: CapabilityScope = CapabilityScope.Aggregate,
      condition: Option[JobCondition] = None,
  ): Capability =
    val base = scope match
      case CapabilityScope.Layer => Capability.dockerLayers
      case CapabilityScope.Graph => Capability.dockerGraph
      case _                     => Capability.docker
    base
      .copy(
        name = name,
        permissions = oidcPermissions,
        extraSteps = sharedLoginSteps,
        condition = condition,
      )
      .withSharedTargets(registryTargets(registries))
  end dockerPublishAll

end ZipxAws
