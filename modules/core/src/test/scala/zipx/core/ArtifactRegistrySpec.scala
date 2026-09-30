package zipx.core

import zio.test.*

object ArtifactRegistrySpec extends ZIOSpecDefault:

  def spec = suite("ArtifactRegistry")(
    test("Central snapshots go to its snapshot repository and releases stage locally for sonaRelease") {
      assertTrue(
        ArtifactRegistry.MavenCentral.snapshotRepository == "https://central.sonatype.com/repository/maven-snapshots/",
        ArtifactRegistry.MavenCentral.releaseRepository.isEmpty,
        ArtifactRegistry.MavenCentral.credentialHost.contains("central.sonatype.com"),
      )
    },
    test("any other registry takes snapshots and releases at its own URL") {
      val packages = ArtifactRegistry.GitHubPackages("early-effect", "zipx")
      val file     = ArtifactRegistry.Url("file:///tmp/zipx-repo")
      assertTrue(
        packages.snapshotRepository == "https://maven.pkg.github.com/early-effect/zipx/",
        packages.releaseRepository.contains("https://maven.pkg.github.com/early-effect/zipx/"),
        packages.credentialHost.contains("maven.pkg.github.com"),
        file.snapshotRepository == "file:///tmp/zipx-repo/",
        file.credentialHost.isEmpty,
      )
    },
    test("a file: registry lists an artifact's releases from its version directories, as metadata would") {
      val artifact = java.nio.file.Files.createTempDirectory("zipx-registry").resolve("models_3")
      for v <- List("1.0.0", "1.1.0") do
        java.nio.file.Files.createDirectories(artifact.resolve(v))
        java.nio.file.Files.writeString(artifact.resolve(v).resolve(s"models_3-$v.pom"), "<project/>")
      java.nio.file.Files.createDirectories(artifact.resolve("scratch"))
      val metadata = HttpLookup.get(artifact.resolve("maven-metadata.xml").toUri.toString)
      val none     = HttpLookup.get(artifact.resolveSibling("absent_3").resolve("maven-metadata.xml").toUri.toString)
      assertTrue(
        metadata.map(_.status) == Right(200),
        metadata.toOption.flatMap(r => MavenMetadata.latestRelease(r.body)).contains(ReleaseVersion("1.1.0")),
        none.exists(_.isMiss),
      )
    },
    test("a file: registry is read from disk: present is 200, absent is a miss") {
      val dir  = java.nio.file.Files.createTempDirectory("zipx-registry")
      val pom  = java.nio.file.Files.writeString(dir.resolve("a.pom"), "<project/>")
      val hit  = HttpLookup.get(pom.toUri.toString)
      val miss = HttpLookup.get(dir.resolve("b.pom").toUri.toString)
      assertTrue(
        hit.map(r => (r.status, r.body)) == Right((200, "<project/>")),
        miss.exists(_.isMiss),
      )
    },
  )
end ArtifactRegistrySpec
