package zipx.plugin

import sbt.librarymanagement.{CrossVersion, ModuleID, ScalaArtifacts, ScalaModuleInfo}

/** Toolchain jars sbt, Scala.js, and Scala Native inject into `libraryDependencies`; they are not catalog rows.
  *
  * `zipxCheckDeps` sees the composed setting, so this subtracts them: a JS row need not catalog `scalajs-library`.
  */
private[plugin] object AutoPlatform:

  def ignore(m: ModuleID): Boolean = ignore(m.organization, m.name)

  def ignore(group: String, artifact: String): Boolean =
    scalaLang(group, artifact) || scalaJs(group, artifact) || scalaNative(group, artifact)

  /** The Scala.js or Scala Native runtime a platform plugin injects at its own version. sbt already pins Scala's. */
  def platformRuntime(m: ModuleID): Boolean = scalaJs(m.organization, m.name) || scalaNative(m.organization, m.name)

  /** Forces each platform runtime in `modules` at the revision its plugin injected. A library built against an older
    * runtime (zio-test-sbt's Native test-interface) then cannot pick the runtime that links or the test runner.
    */
  def overrides(modules: Seq[ModuleID], scala: Option[ScalaModuleInfo]): Seq[ModuleID] =
    modules.filter(platformRuntime).map { module =>
      ModuleID(module.organization, PublishedModule.artifactId(module, scala), module.revision)
        .withCrossVersion(CrossVersion.disabled)
    }

  /** Not `startsWith(id)`: `clib-extras` is not `clib`. */
  private def stem(name: String, id: String): Boolean =
    name == id || name.startsWith(id + "_")

  private def scalaLang(group: String, name: String): Boolean =
    group == ScalaArtifacts.Organization && (
      ScalaArtifacts.Artifacts.exists(id => stem(name, id)) ||
        ScalaArtifacts.isScala3Artifact(name) ||
        stem(name, ScalaArtifacts.Scala3LibraryID) ||
        stem(name, ScalaArtifacts.Scala3CompilerID)
    )

  private def scalaJs(group: String, name: String): Boolean =
    group == "org.scala-js" && ScalaJsStems.exists(stem(name, _))

  private def scalaNative(group: String, name: String): Boolean =
    group == "org.scala-native" && ScalaNativeStems.exists(stem(name, _))

  /** Artifact ids `ScalaJSPluginInternal` and `ScalaJSJUnitPlugin` inject; Scala.js publishes no table of them. */
  private val ScalaJsStems: List[String] = List(
    "scalajs-library",
    "scalajs-scalalib",
    "scalajs-test-bridge",
    "scalajs-compiler",
    "scalajs-junit-test-plugin",
    "scalajs-junit-test-runtime",
  )

  /** sbt-scala-native's `nativeStandardLibraries` plus the scalalib, test, compiler-plugin, and JUnit ids. */
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
