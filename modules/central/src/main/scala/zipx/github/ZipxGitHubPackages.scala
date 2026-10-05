package zipx.github

import zipx.core.*
import zipx.core.EnvValue.{plain, secret}

/** [[releases]] is the Ship path: snapshots and releases at one `maven.pkg.github.com/<owner>/<repo>` root, unsigned,
  * with `owner` as the username and the token as the password.
  *
  * [[sameRepo]] and [[sharedRegistry]] are CI wiring for a build that publishes without ships and keeps its own
  * `publishTo` and credentials. Their default name differs from [[zipx.central.ZipxCentral.release]]'s `publish`, so
  * the two coexist rather than one replacing the other.
  */
object ZipxGitHubPackages:

  val DefaultName: CapabilityName = CapabilityName("github-packages")

  val packagesPermissions: Map[String, String] =
    Map("contents" -> "read", "packages" -> "write")

  val PublishFlagEnv: String = "PUBLISH_GITHUB_PACKAGES"

  /** The token is exported under its own name; lookup and publish both authenticate with it. */
  def releases(
      owner: String,
      repo: String,
      token: EnvValue = secret"GH_PACKAGES_TOKEN",
  ): ReleaseWorkflow =
    ReleaseWorkflow(
      registry = ArtifactRegistry.GitHubPackages(owner, repo),
      credentials = RegistryCredentials.UserPassword(EnvValue.plain(owner), token),
    )

  /** Publishes to this repository's own Packages registry with the workflow's injected token. */
  def sameRepo(
      name: CapabilityName = DefaultName,
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

  /** Publishes to another repository's or org's registry. */
  def sharedRegistry(
      token: EnvValue = secret"GH_PACKAGES_TOKEN",
      name: CapabilityName = DefaultName,
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
      name: CapabilityName,
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
          command = ModuleNode.DefaultPublishTask,
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
