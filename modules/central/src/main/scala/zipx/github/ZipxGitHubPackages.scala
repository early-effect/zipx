package zipx.github

import zipx.core.*
import zipx.core.EnvValue.{plain, secret}

/** GitHub Packages paved path for zipx: CI wiring only, so the build keeps ownership of `publishTo` and Credentials.
  * What this generates is `packages: write`, a token in `GITHUB_TOKEN`, and [[PublishFlagEnv]] for the build to branch
  * on.
  *
  * The default capability name differs from [[zipx.central.ZipxCentral.release]]'s `publish`, so the two coexist rather
  * than one replacing the other by name.
  *
  * {{{
  * zipxCapabilities ++= Seq(
  *   ZipxCentral.release,
  *   ZipxGitHubPackages.sameRepo(condition = Some(JobCondition.repositoryIs("acme/my-fork"))),
  * )
  * }}}
  */
object ZipxGitHubPackages:

  val DefaultName: String = "github-packages"

  val packagesPermissions: Map[String, String] =
    Map("contents" -> "read", "packages" -> "write")

  val PublishFlagEnv: String = "PUBLISH_GITHUB_PACKAGES"

  /** Publishes to this repository's own Packages registry, using the workflow's injected token. A fork gate is a
    * [[zipx.core.JobCondition]] like any other: `condition = Some(JobCondition.repositoryIs("acme/my-fork"))`.
    */
  def sameRepo(
      name: String = DefaultName,
      scope: CapabilityScope = CapabilityScope.Aggregate,
      condition: Option[JobCondition] = None,
  ): Capability =
    publishCap(
      name = name,
      scope = scope,
      token = EnvValue.githubToken,
      condition = condition,
      extraEnv = Map.empty,
    )

  /** Publishes to another repository's or org's registry. `token` is an [[zipx.core.EnvValue]] rather than a secret
    * name, so the name is validated where it is written: `secret"GH_PACKAGES_TOKEN"` does not compile if malformed.
    */
  def sharedRegistry(
      token: EnvValue = secret"GH_PACKAGES_TOKEN",
      name: String = DefaultName,
      scope: CapabilityScope = CapabilityScope.Aggregate,
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
      token = token,
      condition = condition,
      extraEnv = extras,
    )
  end sharedRegistry

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
