package zipx.plugin

import zipx.core.{Lib, Plugin, PreRelease}
import zio.test.*

object MavenMetadataSpec extends ZIOSpecDefault:

  private val xml =
    """<metadata>
      |  <versioning>
      |    <latest>2.1.0-alpha1</latest>
      |    <release>2.0.18</release>
      |  </versioning>
      |</metadata>""".stripMargin

  private val zio      = Lib("dev.zio", "zio", "2.1.26")
  private val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")

  def spec = suite("MavenMetadata")(
    test("Skip prefers a stable <release> over an alpha <latest>") {
      assertTrue(MavenMetadata.parseLatest(xml, PreRelease.Skip) == Some("2.0.18"))
    },
    test("Include prefers <latest> even when it is a pre-release") {
      assertTrue(MavenMetadata.parseLatest(xml, PreRelease.Include) == Some("2.1.0-alpha1"))
    },
    test("Skip drops a pre-release when it is the only version") {
      val onlyAlpha =
        """<metadata>
          |  <versioning>
          |    <latest>2.1.0-alpha1</latest>
          |    <release>2.1.0-alpha1</release>
          |  </versioning>
          |</metadata>""".stripMargin
      assertTrue(MavenMetadata.parseLatest(onlyAlpha, PreRelease.Skip).isEmpty)
    },
    test("Lib metadata is Maven Central only") {
      val urls = MavenMetadata.metadataUrls(zio, "3", "2.0.0")
      assertTrue(
        urls == List("https://repo1.maven.org/maven2/dev/zio/zio_3/maven-metadata.xml")
      )
    },
    test("Plugin metadata is Central then the sbt plugin repo") {
      val urls = MavenMetadata.metadataUrls(scalafmt, "3", "2.0.0")
      assertTrue(
        urls.head.contains("repo1.maven.org"),
        urls.lift(1).exists(_.contains("repo.scala-sbt.org")),
        urls.length == 2,
      )
    },
    test("Plugin lookup skips the plugin repo after a Central hit") {
      var seen: List[String] = Nil
      val fetch              = (url: String) =>
        seen = seen :+ url
        Right(Some("2.6.3"))
      val out = MavenMetadata.latest(scalafmt, "3", "2.0.0", fetch)
      assertTrue(out == Right(Some("2.6.3")), seen.length == 1, seen.head.contains("repo1.maven.org"))
    },
    test("Plugin lookup tries the sbt repo after a Central miss") {
      var seen: List[String] = Nil
      val fetch              = (url: String) =>
        seen = seen :+ url
        if url.contains("repo1.maven.org") then Right(None) else Right(Some("2.6.3"))
      val out = MavenMetadata.latest(scalafmt, "3", "2.0.0", fetch)
      assertTrue(out == Right(Some("2.6.3")), seen.length == 2, seen(1).contains("repo.scala-sbt.org"))
    },
    test("a Central error does not fall through to the plugin repo") {
      var seen: List[String] = Nil
      val fetch              = (url: String) =>
        seen = seen :+ url
        Left("HTTP 503")
      val out = MavenMetadata.latest(scalafmt, "3", "2.0.0", fetch)
      assertTrue(out == Left("HTTP 503"), seen.length == 1)
    },
  )
end MavenMetadataSpec
