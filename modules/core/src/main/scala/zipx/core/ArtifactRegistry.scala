package zipx.core

/** Where a publish reads and writes Maven coordinates.
  *
  * [[ArtifactRegistry.MavenCentral]] stages releases locally for `sonaRelease`. [[ArtifactRegistry.GitHubPackages]] and
  * [[ArtifactRegistry.Url]] use one root for snapshots and releases. [[ArtifactRegistry.Maven]] names the two
  * repositories Artifactory, CodeArtifact, and Nexus keep apart.
  */
enum ArtifactRegistry:
  case MavenCentral
  case GitHubPackages(owner: String, repo: String)
  case Url(base: String)
  case Maven(snapshots: String, releases: String)

  def pomUrl(gav: Gav): String = fileUrl(gav, "pom")

  def jarUrl(gav: Gav): String = fileUrl(gav, "jar")

  private def fileUrl(gav: Gav, extension: String): String =
    s"$root/${ArtifactRegistry.groupPath(gav.organization)}/${gav.artifact}/${gav.version}/${gav.artifact}-${gav.version}.$extension"

  def metadataUrl(organization: String, artifact: String): String =
    s"$root/${ArtifactRegistry.groupPath(organization)}/$artifact/maven-metadata.xml"

  def snapshotRepository: String = this match
    case MavenCentral        => "https://central.sonatype.com/repository/maven-snapshots/"
    case Maven(snapshots, _) => ArtifactRegistry.withSlash(snapshots)
    case _                   => s"$root/"

  /** `None` for Central, whose releases stage locally for `sonaRelease`. */
  def releaseRepository: Option[String] = this match
    case MavenCentral       => None
    case Maven(_, releases) => Some(ArtifactRegistry.withSlash(releases))
    case _                  => Some(s"$root/")

  /** The host a snapshot publish authenticates to. A `file:` registry has none. */
  def credentialHost: Option[String] = ArtifactRegistry.httpHost(snapshotRepository)

  /** Every HTTP host a publish to this registry authenticates to. Snapshot and release hosts both, when they differ. */
  def publishHosts: List[String] =
    (credentialHost.toList ++ releaseRepository.toList.flatMap(ArtifactRegistry.httpHost)).distinct

  def usesGithubToken: Boolean = this match
    case GitHubPackages(_, _) => true
    case _                    => false

  /** Released coordinates. Central's public repo, or the release root of a two-URL registry. */
  private def root: String = this match
    case MavenCentral             => "https://repo1.maven.org/maven2"
    case GitHubPackages(owner, r) => s"https://maven.pkg.github.com/$owner/$r"
    case Url(base)                => base.stripSuffix("/")
    case Maven(_, releases)       => releases.trim.stripSuffix("/")
end ArtifactRegistry

object ArtifactRegistry:
  private def groupPath(organization: String): String = organization.replace('.', '/')

  def httpHost(url: String): Option[String] =
    try
      val uri = java.net.URI.create(url)
      Option(uri.getScheme).filter(_.startsWith("http")).flatMap(_ => Option(uri.getHost)).filter(_.nonEmpty)
    catch case scala.util.control.NonFatal(_) => None

  private def withSlash(url: String): String =
    val stripped = url.trim.stripSuffix("/")
    if stripped.isEmpty then url else s"$stripped/"
end ArtifactRegistry
