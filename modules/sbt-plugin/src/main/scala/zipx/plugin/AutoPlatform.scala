package zipx.plugin

import sbt.librarymanagement.{ModuleID, ScalaArtifacts}

/** Toolchain jars sbt / Scala.js / Scala Native inject into `libraryDependencies`. Not user catalog rows.
  *
  * `zipxCheckDeps` sees the composed setting: those plugins `++=` onto the same key the build writes. This subtracts
  * the injected set so a JS row is not required to catalog `scalajs-library_2.13`.
  *
  * JVM names come from [[ScalaArtifacts]]. JS and Native have no public table; the stems are the artifact ids those
  * plugins actually `++=` (cited at each list). Match is `id` or `id_*` so a crossed `nativelib_native0.5_3` is ignored
  * and a user `bindgen` under the same org is not.
  */
private[plugin] object AutoPlatform:

  def ignore(m: ModuleID): Boolean = ignore(m.organization, m.name)

  def ignore(group: String, artifact: String): Boolean =
    scalaLang(group, artifact) || scalaJs(group, artifact) || scalaNative(group, artifact)

  /** `id` or already-crossed `id_2.13` / `id_native0.5_3`. Not `startsWith(id)`: `clib-extras` is not `clib`. */
  private def stem(name: String, id: String): Boolean =
    name == id || name.startsWith(id + "_")

  private def scalaLang(group: String, name: String): Boolean =
    group == ScalaArtifacts.Organization && (
      ScalaArtifacts.Artifacts.exists(id => stem(name, id)) ||
        ScalaArtifacts.isScala3Artifact(name) ||
        stem(name, ScalaArtifacts.Scala3LibraryID)
    )

  private def scalaJs(group: String, name: String): Boolean =
    group == "org.scala-js" && ScalaJsStems.exists(stem(name, _))

  private def scalaNative(group: String, name: String): Boolean =
    group == "org.scala-native" && ScalaNativeStems.exists(stem(name, _))

  /** Artifact ids `ScalaJSPluginInternal` / `ScalaJSJUnitPlugin` inject (sbt-scalajs 1.22.0). */
  private val ScalaJsStems: List[String] = List(
    "scalajs-library",
    "scalajs-scalalib",
    "scalajs-test-bridge",
    "scalajs-compiler",
    "scalajs-junit-test-plugin",
    "scalajs-junit-test-runtime",
  )

  /** `nativeStandardLibraries` plus scalalib / test / nscplugin / JUnit (sbt-scala-native 0.5.12). */
  private val ScalaNativeStems: List[String] = List(
    "nativelib",
    "clib",
    "posixlib",
    "windowslib",
    "javalib",
    "auxlib",
    "scalalib",
    "scala3lib",
    "test-interface",
    "nscplugin",
    "junit-runtime",
    "junit-plugin",
  )

end AutoPlatform
