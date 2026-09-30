import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.8.4")

  private val grown = new java.io.File("v2").exists

  val libs: ShipGroup =
    if grown then ShipGroup("libs", "1.1.0")("models", "extra") else ShipGroup("libs", "1.0.0")("models")
end MyVersions
