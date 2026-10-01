package zipx.github

import zipx.core.*
import zipx.core.EnvValue.{plain, secret}

/** GitHub Packages paved path for zipx.
  *
  * [[releases]] is the Ship path: `zipxReleaseWorkflow` publishes to `maven.pkg.github.com/<owner>/<repo>`, snapshots
  * and releases at that same root, unsigned. The token is the password and `owner` is the username.
  *
  * [[sameRepo]] and [[sharedRegistry]] stay CI wiring for a build that publishes without ships. The build keeps
  * `publishTo` and Credentials on that path. What they generate is `packages: write`, a token in `GITHUB_TOKEN`, and
  * [[PublishFlagEnv]].
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

  val DefaultName: CapabilityName = CapabilityName("github-packages")

  val packagesPermissions: Map[String, String] =
    Map("contents" -> "read", "packages" -> "write")

  val PublishFlagEnv: String = "PUBLISH_GITHUB_PACKAGES"

  /** Ship rows. The token is exported under its own name. Lookup and publish both authenticate with it. The username
    * sent to Packages is `owner`.
    *
    * {{{
    * zipxReleaseWorkflow := Some(
    *   ZipxGitHubPackages.releases("iterable", "maven-packages", token = secret"GH_PACKAGES_TOKEN")
    * )
    * }}}
    */
  def releases(
      owner: String,
      repo: String,
      token: EnvValue = secret"GH_PACKAGES_TOKEN",
  ): ReleaseWorkflow =
    ReleaseWorkflow(
      registry = ArtifactRegistry.GitHubPackages(owner, repo),
      credentials = RegistryCredentials.UserPassword(EnvValue.plain(owner), token),
    )

  /** Publishes to this repository's own Packages registry, using the workflow's injected token. A fork gate is a
    * [[zipx.core.JobCondition]] like any other: `condition = Some(JobCondition.repositoryIs("acme/my-fork"))`.
    */
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

  /** Publishes to another repository's or org's registry. `token` is an [[zipx.core.EnvValue]] rather than a secret
    * name, so the name is validated where it is written: `secret"GH_PACKAGES_TOKEN"` does not compile if malformed.
    */
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
