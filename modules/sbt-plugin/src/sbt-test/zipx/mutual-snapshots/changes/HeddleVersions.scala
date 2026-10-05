import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val heddleShip          = ShipGroup("heddle", "0.9.0")("heddleJS", "heddleMcpAppsJS")
  val facade              = Lib("com.example.ee", "ascent-dom-facade", "0.10.0")
end MyVersions
