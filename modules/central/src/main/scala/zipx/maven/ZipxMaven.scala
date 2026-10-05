package zipx.maven

import zipx.core.*

/** Any registry that splits snapshot and release URLs (Artifactory, CodeArtifact, Nexus). Publishes unsigned.
  *
  * A CodeArtifact token is minted by a workflow step the build adds; this registry does not learn AWS. A fixed username
  * such as `aws` goes through `username = EnvValue.plain("aws")`.
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
