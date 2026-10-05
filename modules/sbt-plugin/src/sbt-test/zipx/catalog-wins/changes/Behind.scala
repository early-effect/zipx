import zipx.*

// new-client was built against a newer core line than the catalog states.
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val core                = Lib("com.example.cw", "core", "1.1.0-aaaaaaaaaaaa-SNAPSHOT")
  val newClient           = Lib("com.example.cw", "new-client", "1.0.0")

  def clients: Seq[Lib] = Seq(newClient)
end MyVersions
