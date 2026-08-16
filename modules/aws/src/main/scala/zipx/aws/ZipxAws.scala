package zipx.aws

import neotype.unwrap
import zipx.core.*
import zipx.workflow.{ActionRef, EnvName}

/** AWS paved path for zipx: OIDC role assumption, ECR registries whose region cannot be forgotten, and the tag set an
  * image is pushed under.
  *
  * This pack holds no AWS credentials and no account numbers of its own. A role is an [[zipx.core.EnvValue]], so the
  * secret *name* is checked where it is written and the value stays in GitHub; an account id and a region are the
  * consumer's, passed as validated literals.
  *
  * {{{
  * val registry = EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1"))
  *
  * zipxCapabilities += Capability.docker.copy(
  *   permissions = ZipxAws.oidcPermissions,
  *   env         = ZipxAws.registryEnv(registry, secret"DEPLOY_ROLE"),
  *   extraSteps  = ZipxAws.oidcLoginSteps,
  * )
  * }}}
  *
  * The login bundle reads its role and region from the job's `env:`, which is what makes one bundle serve every
  * destination: a per-target `env` block ([[zipx.core.Target.env]]) changes which account the same steps log into.
  */
object ZipxAws:

  /** The `env:` key the login step reads the role ARN from. */
  val Role: EnvName = EnvName("AWS_ROLE_TO_ASSUME")

  /** The `env:` key the login step reads the region from. Named as the AWS CLI names it, so a `run:` step in the same
    * job picks it up with no extra wiring.
    */
  val Region: EnvName = EnvName("AWS_REGION")

  /** The `env:` key holding `<host>/<repository>`, for a build whose `dockerRepository` reads it. */
  val Registry: EnvName = EnvName("AWS_ECR_REGISTRY")

  /** The `env:` key holding the 12-digit account id `amazon-ecr-login` needs as `registries:`. */
  val Account: EnvName = EnvName("AWS_ACCOUNT_ID")

  // The same keys as plain strings, since an `env:` map is keyed by `String`. Named `*Env` because that is what a
  // `build.sbt` writing its own env block reaches for.
  val RoleEnv: String     = Role.unwrap
  val RegionEnv: String   = Region.unwrap
  val RegistryEnv: String = Registry.unwrap
  val AccountEnv: String  = Account.unwrap

  /** OIDC needs `id-token: write`; `contents: read` is what the checkout still needs once permissions are declared
    * explicitly, since naming any permission drops the default set.
    */
  val oidcPermissions: Map[String, String] = Map("id-token" -> "write", "contents" -> "read")

  /** The [[zipx.core.ActionPins.extra]] key this pack's action is pinned under.
    *
    * An extra pin rather than a typed `ActionPins.Field` because zipx's own planner never emits this step: it arrives
    * through a pack, so pinning it must not require a zipx release. The cost is stated in `ActionPins`: an extra pin's
    * ref is checked for being pinned, not for naming this particular action.
    */
  val CredentialsPinKey: String = "aws-actions/configure-aws-credentials"

  /** Used when the consumer has no catalog `Action` row for [[CredentialsPinKey]]. A literal, so its shape is checked
    * while this file compiles, and a SHA rather than a floating tag so the fallback is not itself an unpinned action.
    *
    * Add an `Action` catalog row for [[CredentialsPinKey]] to ZipxVersions to take ownership of the version.
    */
  val DefaultCredentialsAction: ActionRef =
    ActionRef("aws-actions/configure-aws-credentials@e6de054238d6b7531b4efff3b6587d9aade6a06c")

  /** The pin to use for the login step: the consumer's [[CredentialsPinKey]] pin when present, else
    * [[DefaultCredentialsAction]].
    */
  def credentialsAction(pins: ActionPins): ActionRef =
    pins.extraByPrefix(CredentialsPinKey).getOrElse(DefaultCredentialsAction)

  /** Catalog overlay with this pack's action pinned. */
  def withCredentialsPin(pins: ActionPins, ref: ActionRef, version: Option[String] = None): ActionPins =
    pins.withExtra(CredentialsPinKey, ref, version)

  /** Assumes an AWS role by OIDC, passing **both** `role-to-assume` and `aws-region`.
    *
    * Both, always. `aws-region` is required by `configure-aws-credentials`, and omitting it is #65: the action fails on
    * the runner reporting a credentials problem, which sends the reader looking at the role and the trust policy rather
    * than at the missing input. Because the region reaches this bundle through [[RegionEnv]] and an [[EcrRegistry]]
    * cannot be built without one, there is no path here that produces a step with no region.
    *
    * A named [[zipx.core.Steps]] rather than a lambda, so it composes with `++`, gates with `when`, and names itself in
    * the generate-time raw-fragment warning.
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

  /** [[oidcLoginSteps]] followed by an ECR docker login. Required for any push that uses the docker CLI, including
    * sbt-native-packager's `Docker / publish`.
    *
    * `registries:` is the account id from [[AccountEnv]], so multi-account shared jobs accumulate one credential entry
    * per host rather than relying on whichever role was assumed last.
    */
  val ecrLoginSteps: Steps = Steps.one("aws-ecr-login") { _ =>
    ZipxComposites.awsLoginStep(loginEcr = true)
  }

  /** The `env:` a job needs for [[ecrLoginSteps]]: role, region, registry host, and account id for `registries:`.
    *
    * `role` is an [[zipx.core.EnvValue]] rather than a `String`, so `secret"DEPLOY_ROLE"` is checked where it is
    * written and a name assembled at runtime has to go through `EnvValue.secretMake`.
    */
  def registryEnv(registry: EcrRegistry, role: EnvValue): Map[String, EnvValue] = Map(
    RoleEnv     -> role,
    RegionEnv   -> EnvValue.plain(registry.region),
    RegistryEnv -> EnvValue.plain(registry.host),
    AccountEnv  -> EnvValue.plain(registry.accountId),
  )

  /** The same, for a job whose registry is one repository rather than a whole account: [[RegistryEnv]] carries
    * `<host>/<repository>`.
    */
  def imageEnv(image: EcrImage, role: EnvValue): Map[String, EnvValue] = Map(
    RoleEnv     -> role,
    RegionEnv   -> EnvValue.plain(image.registry.region),
    RegistryEnv -> EnvValue.plain(image.uri),
    AccountEnv  -> EnvValue.plain(image.registry.accountId),
  )

  /** One [[zipx.core.Target]] per registry, for a capability that really does want a job each: separate accounts with
    * separate approvals, say.
    *
    * For the ordinary multi-registry image push this is the **wrong** shape, and the cost is multiplicative: N
    * registries times M modules is N*M jobs, each rebuilding the same image. `Docker / publish` pushes every
    * `dockerAliases` entry from one build, so one job with [[EcrImage.taggedAll]] over several registries is both
    * cheaper and what actually guarantees the registries hold identical bytes. Pass these to
    * [[zipx.core.Capability.withSharedTargets]] and use [[sharedLoginSteps]] to get that shape.
    *
    * `name` is a [[zipx.core.TargetName]], so it is validated where it is written: it becomes part of a `jobs.<job_id>`
    * key under `JobPerTarget`, and the `env:`-key prefix under `SharedJob`.
    */
  def registryTargets(registries: List[(TargetName, EcrRegistry, EnvValue)]): List[Target] =
    registries.map((name, registry, role) => Target(name = name, env = registryEnv(registry, role)))

  /** OIDC assume-role then ECR docker login, once **per destination**, for a [[zipx.core.TargetFanOut.SharedJob]]
    * capability: one build, N credential entries, N pushes.
    *
    * Each pair reads its own destination's role, region, and account id under [[zipx.core.Target.envName]] keys. The
    * last `configure-aws-credentials` wins ambient AWS credentials; `amazon-ecr-login` with that destination's account
    * id is what accumulates a `~/.docker/config.json` entry per registry host.
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

  /** A docker publish capability that assumes a role and logs into ECR before pushing, with `id-token: write` already
    * declared.
    *
    * One job for the whole push, which is the shape to prefer: point the build's `dockerAliases` at every registry and
    * let one `Docker / publish` push them all.
    */
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

  /** [[dockerPublish]] over **several** registries in one job: one image build, OIDC then ECR login per destination,
    * one push per `dockerAliases` entry.
    *
    * This is the shape #71 asked for, and the reason it is a factory rather than a documented recipe is the arithmetic:
    * 6 registries × 8 images is 48 jobs under `JobPerTarget` and 8 here, and only this shape can guarantee the
    * registries hold identical bytes, since there is one build to push.
    *
    * The build's `dockerAliases` is what enumerates the destinations on the sbt side; this sets up the credentials for
    * each. `EcrImage.taggedAll` builds that alias list from the same [[EcrRegistry]] values, so the two sides cannot
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
