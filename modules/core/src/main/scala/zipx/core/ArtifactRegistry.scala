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
