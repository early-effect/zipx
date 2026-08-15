import sbt.ModuleID
import zipx.*

/** Typed catalog: every library and sbt plugin this build may use. `zipxDepUpdate` rewrites version literals here.
  *
  * Shared with the main `build.sbt` (not the meta-build). Dogfood ModuleIDs stay in `project/Dependencies.scala`; keep
  * those version literals in sync when a catalog row used by `project/dogfood.sbt` moves.
  */
object ZipxVersions extends zipx.ZipxVersions:

  val sbt: SbtVersion     = SbtVersion("2.0.6")
  val scala: ScalaVersion = ScalaVersion("3.8.4")

  val zio: Lib            = Lib("dev.zio", "zio", "2.1.26")
  val zioTest: Lib        = zio.mod("zio-test").test
  val zioTestSbt: Lib     = zio.mod("zio-test-sbt").test
  val zioBlocks: Lib      = Lib("dev.zio", "zio-blocks-schema", "0.0.51")
  val zioBlocksYaml: Lib  = zioBlocks.mod("zio-blocks-schema-yaml")
  val neotype: Lib        = Lib("io.github.kitlangton", "neotype", "0.7.0")
  val testcontainers: Lib = Lib("org.testcontainers", "testcontainers", "2.0.5").java.test
  val slf4jSimple: Lib    = Lib("org.slf4j", "slf4j-simple", "2.0.18").java.test

  val specular: Lib        = Lib("rocks.earlyeffect", "specular-core", "0.12.1")
  val specularMermoid: Lib = specular.mod("specular-mermoid")
  val specularZioTest: Lib = specular.mod("specular-zio-test").test
  val specularSite: Lib    = specular.mod("specular-site").test
  val specularTheme: Lib   = specular.mod("early-effect-docs-theme").test
  val ascent: Lib          = Lib("rocks.earlyeffect", "ascent-core", "0.3.1")
  val ascentHtml: Lib      = ascent.mod("ascent-html")
  val ascentJs: Lib        = ascent.mod("ascent-js")
  val ascentCss: Lib       = ascent.mod("ascent-css")

  val dynverCi: Plugin       = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.2")
  val scalafmt: Plugin       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val pgp: Plugin            = Plugin("com.github.sbt", "sbt-pgp", "2.3.1")
  val specularPlugin: Plugin = Plugin("rocks.earlyeffect", "sbt-specular", "0.12.1")
  val sbtReload: Plugin      = Plugin("com.jamesward", "sbt-reload", "0.0.7")
  val scalajs: Plugin        = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val remoteCache: Plugin    =
    Plugin("org.scala-sbt", "sbt-remote-cache", "2.0.5").excluding(ZipxExclude.org("org.scala-sbt"))

  val commonScalacOptions: Seq[String] = Seq(
    "-deprecation",
    "-feature",
    "-Wunused:all",
    "-Xlint:-classpath",
  )

  def zioDeps: Seq[ModuleID]             = deps(zio, zioTest, zioTestSbt)
  def shellLibraryDeps: Seq[ModuleID]    = deps(neotype)
  def workflowLibraryDeps: Seq[ModuleID] = deps(zioBlocks, zioBlocksYaml)
  def testcontainersDeps: Seq[ModuleID]  = deps(testcontainers, slf4jSimple)
  def remoteCachePlugin: ModuleID        = moduleID(remoteCache)
end ZipxVersions
