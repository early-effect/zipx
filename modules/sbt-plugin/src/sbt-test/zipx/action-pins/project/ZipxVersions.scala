import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.8")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val checkout            =
    Action("actions/checkout", "v0.0.1", sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
end MyVersions
