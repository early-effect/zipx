package zipx.maven

import zipx.core.*

/** A Maven repository with its own snapshot URL and release URL: Artifactory, CodeArtifact, Nexus, or any other layout
  * that splits them. Unsigned `publish`. Signing stays on [[zipx.central.ZipxCentral.releases]] unless a build adds it.
  *
  * Minting a CodeArtifact token is a workflow step the build adds, the same way Central imports a signing key. This
  * registry does not learn AWS. A registry that wants a fixed username (`aws`) uses the username/password constructor
  * with `username = EnvValue.plain("aws")`.
  *
  * {{{
  * zipxReleaseWorkflow := Some(
  *   ZipxMaven.releases(
  *     snapshots = "https://acme.artifactory.example/maven-snapshots",
  *     releases  = "https://acme.artifactory.example/maven-releases",
  *     username  = secret"MAVEN_USERNAME",
  *     password  = secret"MAVEN_PASSWORD",
  *   )
  * )
  * }}}
  */
object ZipxMaven:

  def releases(
      snapshots: String,
      releases: String,
      username: EnvValue,
      password: EnvValue,
  ): ReleaseWorkflow =
    workflow(snapshots, releases, RegistryCredentials.UserPassword(username, password))

  def releases(
      snapshots: String,
      releases: String,
      token: EnvValue,
  ): ReleaseWorkflow =
    workflow(snapshots, releases, RegistryCredentials.Bearer(token))

  private def workflow(snapshots: String, releases: String, credentials: RegistryCredentials): ReleaseWorkflow =
    ReleaseWorkflow(
      registry = ArtifactRegistry.Maven(snapshots, releases),
      credentials = credentials,
    )
end ZipxMaven
