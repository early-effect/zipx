import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.6")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  val libA                = Pin("cdn", "lib-a", "1.2.3", sha256 = "deadbeef", purl = "pkg:npm/lib-a@1.2.3")
end MyVersions
