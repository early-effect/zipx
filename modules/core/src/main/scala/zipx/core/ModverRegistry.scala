package zipx.core

/** Where zipx looks up outbound POMs for version-moved skip. Topology is [[ZipxModver]], not this type. */
enum ModverRegistry:
  case MavenCentral
  case GitHubPackages(owner: String, repo: String)
  case Url(base: String)

  def pomUrl(gav: Gav): String =
    val group = gav.organization.replace('.', '/')
    val path  = s"$group/${gav.artifact}/${gav.version}/${gav.artifact}-${gav.version}.pom"
    this match
      case MavenCentral             => s"https://repo1.maven.org/maven2/$path"
      case GitHubPackages(owner, r) => s"https://maven.pkg.github.com/$owner/$r/$path"
      case Url(base)                => s"${base.stripSuffix("/")}/$path"

  def encode: String = this match
    case MavenCentral             => "central"
    case GitHubPackages(owner, r) => s"ghpkg:$owner/$r"
    case Url(base)                => s"url:$base"

  def usesGithubToken: Boolean = this match
    case GitHubPackages(_, _) => true
    case _                    => false
end ModverRegistry

object ModverRegistry:
  val EnvKey: String = "ZIPX_MODVER_REGISTRY"

  def decode(raw: String): Either[String, ModverRegistry] =
    raw match
      case "central"                     => Right(MavenCentral)
      case s"ghpkg:$owner/$repo"         => Right(GitHubPackages(owner, repo))
      case s"url:$base" if base.nonEmpty => Right(Url(base))
      case other                         => Left(s"unknown ModverRegistry encoding '$other'")
end ModverRegistry

/** Independent-versioning topology: Graph library publish on default-branch push. Not a registry pack. */
object ZipxModver:

  def publish(
      command: SbtCommand,
      registry: ModverRegistry = ModverRegistry.MavenCentral,
  ): Capability =
    val _ = registry
    Capability.publishGraph
      .copy(gate = Gate.OnDefaultPush)
      .withMatrixCollapse(MatrixCollapse.Off)
      .runningEachCross(command)
end ZipxModver
