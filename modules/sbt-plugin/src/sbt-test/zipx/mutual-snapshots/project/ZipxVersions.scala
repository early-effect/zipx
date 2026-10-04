import zipx.*

// Ascent's catalog. The heddle row is a placeholder until `use-heddle` makes mcpApp depend on it.
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val dom                 = ShipGroup("dom", "0.10.0")("domFacadeJS")
  val jsShip              = Ship("jsJS", "0.10.0")
  val mcpAppShip          = Ship("mcpAppJS", "0.10.0")
  val heddle              = Lib("com.example.ee", "heddle", "0.9.0")
  val heddleMcpApps       = heddle.mod("heddle-mcp-apps")
end MyVersions
