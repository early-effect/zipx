package zipx.core

import zio.test.*

import scala.collection.mutable.ListBuffer

object MavenMetadataSpec extends ZIOSpecDefault:

  private val xml =
    """<metadata>
      |  <versioning>
      |    <latest>2.1.0-alpha1</latest>
      |    <release>2.0.18</release>
      |    <versions>
      |      <version>2.0.17</version>
      |      <version>2.0.18</version>
      |      <version>2.1.0-alpha1</version>
      |    </versions>
      |  </versioning>
      |</metadata>""".stripMargin

  private val zio      = Lib("dev.zio", "zio", "2.1.26")
  private val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")

  def spec = suite("MavenMetadata")(
    test("every listed version is read, in order, pre-releases included") {
      assertTrue(MavenMetadata.versionsIn(xml) == List("2.0.17", "2.0.18", "2.1.0-alpha1"))
    },
    test("a Lib's metadata is Maven Central only, under the name it is asked for") {
      assertTrue(
        MavenMetadata.metadataUrls(zio, "zio_sjs1_3") ==
          List("https://repo1.maven.org/maven2/dev/zio/zio_sjs1_3/maven-metadata.xml")
      )
    },
    test("a Plugin's metadata is Central, then the sbt plugin repo") {
      val urls = MavenMetadata.metadataUrls(scalafmt, "sbt-scalafmt_sbt2_3")
      assertTrue(
        urls.length == 2,
        urls.headOption.exists(_.contains("repo1.maven.org")),
        urls.lift(1).exists(_.contains("repo.scala-sbt.org")),
      )
    },
    test("a Central hit never reaches the plugin repo, and a Central miss does") {
      val seen    = ListBuffer.empty[String]
      val hit     = MavenMetadata.lookup { url => seen += url; Right(Some(List("2.6.3"))) }
      val hitOut  = hit.versions(scalafmt, "sbt-scalafmt_sbt2_3")
      val hitSeen = seen.toList
      seen.clear()
      val miss = MavenMetadata.lookup { url =>
        seen += url
        if url.contains("repo1.maven.org") then Right(None) else Right(Some(List("2.6.3")))
      }
      val missOut = miss.versions(scalafmt, "sbt-scalafmt_sbt2_3")
      assertTrue(
        hitOut == Right(Some(List("2.6.3"))),
        hitSeen.length == 1,
        missOut == Right(Some(List("2.6.3"))),
        seen.lift(1).exists(_.contains("repo.scala-sbt.org")),
      )
    },
    test("a Central error does not fall through to the plugin repo") {
      val seen = ListBuffer.empty[String]
      val out  = MavenMetadata.lookup { url => seen += url; Left("HTTP 503") }.versions(scalafmt, "sbt-scalafmt_sbt2_3")
      assertTrue(out == Left("HTTP 503"), seen.length == 1)
    },
  )
end MavenMetadataSpec
