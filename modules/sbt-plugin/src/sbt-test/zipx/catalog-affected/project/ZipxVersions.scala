import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M2")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  val fansi               = Lib("com.lihaoyi", "fansi", "0.5.1")
  val upickle             = Lib("com.lihaoyi", "upickle", "4.4.2")
end MyVersions
