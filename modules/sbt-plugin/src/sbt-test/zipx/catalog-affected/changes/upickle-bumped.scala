import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val fansi               = Lib("com.lihaoyi", "fansi", "0.5.2")
  val upickle             = Lib("com.lihaoyi", "upickle", "4.4.3")
end MyVersions
