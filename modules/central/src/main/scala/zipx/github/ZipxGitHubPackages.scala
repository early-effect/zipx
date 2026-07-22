package zipx.github

import zipx.core.*
import zipx.core.EnvValue.{expr, plain, secret}

/** GitHub Packages paved path for zipx.
  *
  * Thin CI wiring only: `packages: write`, a token in `GITHUB_TOKEN`, and `PUBLISH_GITHUB_PACKAGES=true` so the
  * **build** can switch `publishTo` / Credentials. zipx does not generate sbt publish settings.
  *
  * Capability name defaults to `"github-packages"` so it coexists with [[zipx.central.ZipxCentral.release]]
  * (`"publish"`) without replace-by-name.
  *
  * {{{
  * zipxCapabilities ++= Seq(
  *   ZipxCentral.release,
  *   ZipxGitHubPackages.sameRepo(repository = Some("acme/my-fork")),
  * )
  * }}}
  */
object ZipxGitHubPackages:

  val DefaultName: String = "github-packages"

  val packagesPermissions: Map[String, String] =
    Map("contents" -> "read", "packages" -> "write")

  /** Env flag builds use to opt `publishTo` into GitHub Packages (work / mechanoid convention). */
  val PublishFlagEnv: String = "PUBLISH_GITHUB_PACKAGES"

  /** Same-repo Packages: default `GITHUB_TOKEN` from `${{ github.token }}`. */
  def sameRepo(
      name: String = DefaultName,
      scope: CapabilityScope = CapabilityScope.Aggregate,
      repository: Option[String] = None,
      condition: Option[JobCondition] = None,
  ): Capability =
    publishCap(
      name = name,
      scope = scope,
      token = expr("${{ github.token }}"),
      condition = resolveCondition(repository, condition),
      extraEnv = Map.empty,
    )

  /** Shared / cross-repo Packages: token from a repository or org secret (e.g. `GH_PACKAGES_TOKEN`). */
  def sharedRegistry(
      tokenSecret: String = "GH_PACKAGES_TOKEN",
      name: String = DefaultName,
      scope: CapabilityScope = CapabilityScope.Aggregate,
      repository: Option[String] = None,
      condition: Option[JobCondition] = None,
      packagesRepo: Option[String] = None,
      publishOrg: Option[String] = None,
      publishOrgName: Option[String] = None,
  ): Capability =
    val extras = List(
      packagesRepo.map("PUBLISH_PACKAGES_REPO" -> plain(_)),
      publishOrg.map("PUBLISH_ORG" -> plain(_)),
      publishOrgName.map("PUBLISH_ORG_NAME" -> plain(_)),
    ).flatten.toMap
    publishCap(
      name = name,
      scope = scope,
      token = secret(tokenSecret),
      condition = resolveCondition(repository, condition),
      extraEnv = extras,
    )
  end sharedRegistry

  private def resolveCondition(
      repository: Option[String],
      condition: Option[JobCondition],
  ): Option[JobCondition] =
    (repository, condition) match
      case (Some(repo), Some(c)) => Some(JobCondition.and(JobCondition.repositoryIs(repo), c))
      case (Some(repo), None)    => Some(JobCondition.repositoryIs(repo))
      case (None, c)             => c

  private def publishCap(
      name: String,
      scope: CapabilityScope,
      token: EnvValue,
      condition: Option[JobCondition],
      extraEnv: Map[String, EnvValue],
  ): Capability =
    val base = scope match
      case CapabilityScope.Aggregate => Capability.publish
      case CapabilityScope.Layer     => Capability.publishLayers
      case CapabilityScope.Graph     => Capability.publishGraph
      case CapabilityScope.Once      =>
        Capability.once(
          name = name,
          command = "publish",
          phase = Phase.Publish,
          gate = Gate.OnReleaseTag,
        )
    base.copy(
      name = name,
      permissions = packagesPermissions,
      env = Map(
        "GITHUB_TOKEN" -> token,
        PublishFlagEnv -> plain("true"),
      ) ++ extraEnv,
      condition = condition,
    )
  end publishCap

end ZipxGitHubPackages
