package zipx.maven

import zipx.core.*
import zipx.core.EnvValue.secret
import zio.test.*

object ZipxMavenSpec extends ZIOSpecDefault:

  private val config = PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.2.3-SNAPSHOT"))

  private val snapshots = "https://acme.artifactory.example/maven-snapshots"
  private val releases  = "https://acme.artifactory.example/maven-releases"

  private def yml(workflow: ReleaseWorkflow): String =
    ReleaseWorkflow.render(workflow, config, TagScheme.PerRow).fold(identity, identity)

  def spec = suite("ZipxMaven")(
    test("username and password publish both repositories, unsigned, with ships default all") {
      val workflow = ZipxMaven.releases(
        snapshots,
        releases,
        username = secret"MAVEN_USERNAME",
        password = secret"MAVEN_PASSWORD",
      )
      val rendered = yml(workflow)
      assertTrue(
        workflow.registry == ArtifactRegistry.Maven(snapshots, releases),
        workflow.registry.snapshotRepository == s"$snapshots/",
        workflow.registry.releaseRepository.contains(s"$releases/"),
        rendered.contains("ships:"),
        rendered.contains("default: all"),
        rendered.contains("MAVEN_USERNAME: ${{ secrets.MAVEN_USERNAME }}"),
        rendered.contains("MAVEN_PASSWORD: ${{ secrets.MAVEN_PASSWORD }}"),
        !rendered.contains("sonaRelease"),
        !rendered.contains("PGP_"),
        !rendered.contains("Import signing key"),
        !rendered.contains("packages: write"),
      )
    },
    test("a bearer token is the only secret, and the workflow still names ships") {
      val rendered = yml(ZipxMaven.releases(snapshots, releases, token = secret"CODEARTIFACT_TOKEN"))
      assertTrue(
        rendered.contains("ships:"),
        rendered.contains("default: all"),
        rendered.contains("CODEARTIFACT_TOKEN: ${{ secrets.CODEARTIFACT_TOKEN }}"),
        !rendered.contains("MAVEN_USERNAME"),
        !rendered.contains("sonaRelease"),
        !rendered.contains("PGP_"),
      )
    },
  )
end ZipxMavenSpec
