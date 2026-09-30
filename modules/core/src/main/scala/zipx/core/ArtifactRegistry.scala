package zipx.core

enum ArtifactRegistry:
  case MavenCentral
  case GitHubPackages(owner: String, repo: String)
  case Url(base: String)

  def pomUrl(gav: Gav): String = fileUrl(gav, "pom")

  def jarUrl(gav: Gav): String = fileUrl(gav, "jar")

  private def fileUrl(gav: Gav, extension: String): String =
    s"$root/${ArtifactRegistry.groupPath(gav.organization)}/${gav.artifact}/${gav.version}/${gav.artifact}-${gav.version}.$extension"

  def metadataUrl(organization: String, artifact: String): String =
    s"$root/${ArtifactRegistry.groupPath(organization)}/$artifact/maven-metadata.xml"

  def snapshotRepository: String = this match
    case MavenCentral => "https://central.sonatype.com/repository/maven-snapshots/"
    case _            => s"$root/"

  /** `None` for Central, whose releases stage locally for `sonaRelease`. */
  def releaseRepository: Option[String] = this match
    case MavenCentral => None
    case _            => Some(s"$root/")

  /** The host a publish authenticates to; a `file:` registry needs none. */
  def credentialHost: Option[String] =
    Option(java.net.URI.create(snapshotRepository)).filter(_.getScheme.startsWith("http")).map(_.getHost)

  def usesGithubToken: Boolean = this match
    case GitHubPackages(_, _) => true
    case _                    => false

  private def root: String = this match
    case MavenCentral             => "https://repo1.maven.org/maven2"
    case GitHubPackages(owner, r) => s"https://maven.pkg.github.com/$owner/$r"
    case Url(base)                => base.stripSuffix("/")
end ArtifactRegistry

object ArtifactRegistry:
  private def groupPath(organization: String): String = organization.replace('.', '/')
