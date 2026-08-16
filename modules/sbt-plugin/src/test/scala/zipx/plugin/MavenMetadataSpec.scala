package zipx.plugin

import zipx.core.PreRelease
import zio.test.*

object MavenMetadataSpec extends ZIOSpecDefault:

  private val xml =
    """<metadata>
      |  <versioning>
      |    <latest>2.1.0-alpha1</latest>
      |    <release>2.0.18</release>
      |  </versioning>
      |</metadata>""".stripMargin

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
  )
end MavenMetadataSpec
