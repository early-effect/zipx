import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.6")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  val zio                 = Lib("dev.zio", "zio", "2.1.26")
  val scalafmt            = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
end MyVersions
