import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M2")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  val upstream            = Lib("com.example.zipx.pins", "upstream", "1.0.0-SNAPSHOT")
  def pinned              = library(upstream)
end MyVersions
