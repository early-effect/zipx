package zipx.plugin

import sbt.librarymanagement.ModuleID
import zio.test.*

object AutoPlatformSpec extends ZIOSpecDefault:

  private def mid(group: String, artifact: String): ModuleID = ModuleID(group, artifact, "0")

  def spec = suite("AutoPlatform")(
    suite("ignores toolchain jars sbt / Scala.js / Native inject")(
      test("ScalaArtifacts library ids, including the JS-rewritten scala3-library") {
        assertTrue(
          AutoPlatform.ignore(mid("org.scala-lang", "scala-library")),
          AutoPlatform.ignore(mid("org.scala-lang", "scala3-library")),
          AutoPlatform.ignore(mid("org.scala-lang", "scala3-library_sjs1_3")),
          AutoPlatform.ignore(mid("org.scala-lang", "scala-reflect")),
          AutoPlatform.ignore(mid("org.scala-lang", "scala-compiler")),
        )
      },
      test("Scala.js stems, bare and already-crossed") {
        assertTrue(
          AutoPlatform.ignore(mid("org.scala-js", "scalajs-library")),
          AutoPlatform.ignore(mid("org.scala-js", "scalajs-library_2.13")),
          AutoPlatform.ignore(mid("org.scala-js", "scalajs-scalalib_2.13")),
          AutoPlatform.ignore(mid("org.scala-js", "scalajs-test-bridge_2.13")),
          AutoPlatform.ignore(mid("org.scala-js", "scalajs-compiler_3.8.4")),
          AutoPlatform.ignore(mid("org.scala-js", "scalajs-junit-test-runtime_2.13")),
        )
      },
      test("Native standard libraries plus scalalib / test-interface") {
        assertTrue(
          AutoPlatform.ignore(mid("org.scala-native", "nativelib")),
          AutoPlatform.ignore(mid("org.scala-native", "nativelib_native0.5_3")),
          AutoPlatform.ignore(mid("org.scala-native", "clib")),
          AutoPlatform.ignore(mid("org.scala-native", "posixlib")),
          AutoPlatform.ignore(mid("org.scala-native", "windowslib")),
          AutoPlatform.ignore(mid("org.scala-native", "javalib")),
          AutoPlatform.ignore(mid("org.scala-native", "auxlib")),
          AutoPlatform.ignore(mid("org.scala-native", "scala3lib")),
          AutoPlatform.ignore(mid("org.scala-native", "scalalib")),
          AutoPlatform.ignore(mid("org.scala-native", "test-interface")),
          AutoPlatform.ignore(mid("org.scala-native", "junit-runtime")),
        )
      },
    ),
    suite("does not ignore user catalog rows")(
      test("a %% library, a JS user lib under org.scala-js, and Native bindgen") {
        assertTrue(
          !AutoPlatform.ignore(mid("dev.zio", "zio")),
          !AutoPlatform.ignore(mid("org.scala-js", "scalajs-dom_sjs1_3")),
          !AutoPlatform.ignore(mid("org.scala-native", "bindgen")),
          !AutoPlatform.ignore(mid("org.scala-native", "bindgen_native0.5_3")),
          !AutoPlatform.ignore(mid("org.slf4j", "slf4j-simple")),
        )
      },
      test("stem is id or id_*, not startsWith(id)") {
        assertTrue(
          !AutoPlatform.ignore(mid("org.scala-native", "clib-extras")),
          !AutoPlatform.ignore(mid("org.scala-native", "javalib-foo")),
        )
      },
    ),
  )
end AutoPlatformSpec
