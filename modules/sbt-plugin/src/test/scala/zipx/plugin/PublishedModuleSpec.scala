package zipx.plugin

import sbt.librarymanagement.{CrossVersion, ModuleID}
import zio.test.*

object PublishedModuleSpec extends ZIOSpecDefault:

  private val ScalaFull = "3.8.1"
  private val ScalaBin  = "3"

  private def module(name: String, platform: String): ModuleID =
    ModuleID("rocks.earlyeffect", name, "0.9.0")
      .withCrossVersion(CrossVersion.binary)
      .withPlatformOpt(Some(platform))

  def spec = suite("PublishedModule")(
    test("a JVM module is name_scalaBin") {
      val id = PublishedModule.artifactId(module("ascent-preview", "jvm"), ScalaFull, ScalaBin)
      assertTrue(id == "ascent-preview_3")
    },
    test("a Scala.js module keeps the platform suffix ahead of the Scala binary") {
      val id = PublishedModule.artifactId(module("ascent-mcp-app", "sjs1"), ScalaFull, ScalaBin)
      assertTrue(id == "ascent-mcp-app_sjs1_3")
    },
    test("a Native module keeps the platform suffix ahead of the Scala binary") {
      val id = PublishedModule.artifactId(module("ascent-dom-types", "native0.5"), ScalaFull, ScalaBin)
      assertTrue(id == "ascent-dom-types_native0.5_3")
    },
    test("an sbt 2 plugin keeps the sbt cross prefix") {
      val plugin =
        module("sbt-ascent-preview", "jvm").cross(CrossVersion.binaryWith("sbt2_", ""))
      val id = PublishedModule.artifactId(plugin, ScalaFull, ScalaBin)
      assertTrue(id == "sbt-ascent-preview_sbt2_3")
    },
    test("a disabled cross version publishes the module name") {
      val plain =
        ModuleID("rocks.earlyeffect", "plain", "1.0.0").withCrossVersion(CrossVersion.disabled)
      val id = PublishedModule.artifactId(plain, ScalaFull, ScalaBin)
      assertTrue(id == "plain")
    },
  )
end PublishedModuleSpec
