package zipx.plugin

import sbt.librarymanagement.{CrossVersion, ModuleID, ScalaModuleInfo}
import zio.test.*

object PublishedModuleSpec extends ZIOSpecDefault:

  private val scala3 =
    ScalaModuleInfo(
      "3.9.0",
      "3",
      Vector.empty,
      checkExplicit = true,
      filterImplicit = false,
      overrideScalaVersion = true,
    )

  private def module(name: String, platform: String): ModuleID =
    ModuleID("rocks.earlyeffect", name, "0.9.0")
      .withCrossVersion(CrossVersion.binary)
      .withPlatformOpt(Some(platform))

  def spec = suite("PublishedModule")(
    test("a JVM module is name_scalaBin") {
      assertTrue(PublishedModule.artifactId(module("ascent-preview", "jvm"), Some(scala3)) == "ascent-preview_3")
    },
    test("a Scala.js module keeps the platform suffix ahead of the Scala binary") {
      assertTrue(PublishedModule.artifactId(module("ascent-mcp-app", "sjs1"), Some(scala3)) == "ascent-mcp-app_sjs1_3")
    },
    test("a project's platform names the module when the module carries none") {
      val js = scala3.withPlatform(Some("sjs1"))
      val id = ModuleID("rocks.earlyeffect", "heddle", "0.9.0").withCrossVersion(CrossVersion.binary)
      assertTrue(PublishedModule.artifactId(id, Some(js)) == "heddle_sjs1_3")
    },
    test("a Native module keeps the platform suffix ahead of the Scala binary") {
      assertTrue(
        PublishedModule.artifactId(module("ascent-dom-types", "native0.5"), Some(scala3)) ==
          "ascent-dom-types_native0.5_3"
      )
    },
    test("an sbt 2 plugin keeps the sbt cross prefix") {
      val plugin = module("sbt-ascent-preview", "jvm").cross(CrossVersion.binaryWith("sbt2_", ""))
      assertTrue(PublishedModule.artifactId(plugin, Some(scala3)) == "sbt-ascent-preview_sbt2_3")
    },
    test("a disabled cross version, or no Scala at all, publishes the module name") {
      val plain = ModuleID("rocks.earlyeffect", "plain", "1.0.0").withCrossVersion(CrossVersion.disabled)
      assertTrue(
        PublishedModule.artifactId(plain, Some(scala3)) == "plain",
        PublishedModule.artifactId(module("java-only", "jvm"), None) == "java-only",
      )
    },
  )
end PublishedModuleSpec
