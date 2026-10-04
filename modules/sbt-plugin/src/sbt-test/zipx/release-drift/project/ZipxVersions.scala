import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")

  val libs = ShipGroup("libs", "1.0.0")("models")
  val side = Ship("side", "0.2.0")
end MyVersions
