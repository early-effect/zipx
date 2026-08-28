import sbt.ModuleID
import zipx.*

/** Typed catalog: every library and sbt plugin this build may use. `zipxDepUpdate` rewrites version literals here.
  *
  * Shared with the main `build.sbt` (not the meta-build). Dogfood ModuleIDs stay in `project/Dependencies.scala`; keep
  * those version literals in sync when a catalog row used by `project/dogfood.sbt` moves.
  */
object ZipxVersions extends zipx.ZipxVersions:

  val sbt: SbtVersion     = SbtVersion("2.0.7")
  val scala: ScalaVersion = ScalaVersion("3.8.4")

  val zio: Lib            = Lib("dev.zio", "zio", "2.1.26")
  val zioTest: Lib        = zio.mod("zio-test").test
  val zioTestSbt: Lib     = zio.mod("zio-test-sbt").test
  val zioJson: Lib        = Lib("dev.zio", "zio-json", "0.10.0")
  val mimaCore: Lib       = Lib("com.typesafe", "mima-core", "1.1.5")
  val zioBlocks: Lib      = Lib("dev.zio", "zio-blocks-schema", "0.0.51")
  val zioBlocksYaml: Lib  = zioBlocks.mod("zio-blocks-schema-yaml")
  val neotype: Lib        = Lib("io.github.kitlangton", "neotype", "0.7.0")
  val testcontainers: Lib = Lib("org.testcontainers", "testcontainers", "2.0.5").java.test
  val slf4jSimple: Lib    = Lib("org.slf4j", "slf4j-simple", "2.0.18").java.test

  val specular: Lib        = Lib("rocks.earlyeffect", "specular-core", "0.14.1")
  val specularMermoid: Lib = specular.mod("specular-mermoid")
  val specularZioTest: Lib = specular.mod("specular-zio-test").test
  val specularSite: Lib    = specular.mod("specular-site").test
  val specularTheme: Lib   = specular.mod("early-effect-docs-theme").test

  val dynverCi: Plugin       = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.3")
  val scalafmt: Plugin       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val pgp: Plugin            = Plugin("com.github.sbt", "sbt-pgp", "2.3.2")
  val specularPlugin: Plugin = Plugin("rocks.earlyeffect", "sbt-specular", "0.14.1")
  val sbtReload: Plugin      = Plugin("com.jamesward", "sbt-reload", "0.0.7")
  val scalajs: Plugin        = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val remoteCache: Plugin    =
    Plugin("org.scala-sbt", "sbt-remote-cache", "2.0.7").excluding(ZipxExclude.org("org.scala-sbt"))

  val checkout: Action =
    Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
  val setupJava: Action =
    Action("actions/setup-java", "v5.7.0", sha = "b6effb05e454b25005698d916606bdc6ffcbf961")
  val setupSbt: Action =
    Action("sbt/setup-sbt", "v1.5.8", sha = "c7d2d6258b4bd0d3ec5129e6b3453199d3c79729")
  val setupNode: Action =
    Action("actions/setup-node", "v7.0.0", sha = "820762786026740c76f36085b0efc47a31fe5020")
  val cache: Action =
    Action("actions/cache", "v6.1.0", sha = "55cc8345863c7cc4c66a329aec7e433d2d1c52a9")
  val uploadArtifact: Action =
    Action("actions/upload-artifact", "v7.0.1", sha = "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a")
  val downloadArtifact: Action =
    Action("actions/download-artifact", "v8.0.1", sha = "3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c")
  val awsCredentials: Action =
    Action(
      "aws-actions/configure-aws-credentials",
      "v6.2.3",
      sha = "e6de054238d6b7531b4efff3b6587d9aade6a06c",
    )
  val ecrLogin: Action =
    Action("aws-actions/amazon-ecr-login", "v2.1.7", sha = "03f1aad4c6c7ffd436567f42f9384779290529bd")
  val setupCoursier: Action =
    Action("coursier/setup-action", "v3.0.2", sha = "9b7939bf01fd1185ce2babe16135168361bf2c62")

  val commonScalacOptions: Seq[String] = Seq(
    "-deprecation",
    "-feature",
    "-Wunused:all",
  )

  def zioDeps: Seq[ModuleID]             = deps(zio, zioTest, zioTestSbt)
  def shellLibraryDeps: Seq[ModuleID]    = deps(neotype)
  def workflowLibraryDeps: Seq[ModuleID] = deps(zioBlocks, zioBlocksYaml)
  def testcontainersDeps: Seq[ModuleID]  = deps(testcontainers, slf4jSimple)
  def remoteCachePlugin: ModuleID        = moduleID(remoteCache)
end ZipxVersions
