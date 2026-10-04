import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  // Not `<line>-SNAPSHOT`: that coordinate is the pointer and `update` refuses it.
  val upstream            = Lib("com.example.zipx.pins", "upstream", "1.0.0-RC1-SNAPSHOT")
  val helper              = Lib("com.example.zipx.pins", "helper", "1.0.0-RC1-SNAPSHOT")
  def pinned              = library(upstream)
  def withHelper          = library(helper)
end MyVersions
