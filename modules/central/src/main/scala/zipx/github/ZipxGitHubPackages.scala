package zipx.github

import zipx.core.*
import zipx.core.EnvValue.{plain, secret}

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
  *   ZipxGitHubPackages.sameRepo(condition = Some(JobCondition.repositoryIs("acme/my-fork"))),
  * )
  * }}}
  */
object ZipxGitHubPackages:

  val DefaultName: String = "github-packages"

  val packagesPermissions: Map[String, String] =
    Map("contents" -> "read", "packages" -> "write")

  /** Env flag builds use to opt `publishTo` into GitHub Packages (work / mechanoid convention). */
  val PublishFlagEnv: String = "PUBLISH_GITHUB_PACKAGES"

  /** Same-repo Packages: default `GITHUB_TOKEN` from `${{ github.token }}`.
    *
    * A fork gate is a [[zipx.core.JobCondition]] like any other, so it goes in `condition`. There used to be a
    * `repository: Option[String]` shortcut here; it took an owner/repo slug as an unvalidated string and threw on a
    * malformed one, where `JobCondition.repositoryIs("acme/fork")` is checked while the build compiles.
    *
    * {{{
    * ZipxGitHubPackages.sameRepo(condition = Some(JobCondition.repositoryIs("acme/my-fork")))
    * }}}
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

  /** Shared / cross-repo Packages: token from a repository or org secret.
    *
    * `token` is an [[zipx.core.EnvValue]] rather than a secret *name*, so the name is validated where it is written:
    * `secret"GH_PACKAGES_TOKEN"` is an `inline` constructor and a malformed name does not compile.
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
