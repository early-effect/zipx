import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  val libs                = ShipGroup("libs", "1.4.2")("models", "coreLib")
  val client              = Ship("client", "0.3.0")
end MyVersions
