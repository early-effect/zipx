import zipx.*

// The demo's catalog: ascent's app, pinned at a commit once ascent publishes one.
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val mcpApp              = Lib("com.example.ee", "ascent-mcp-app", "0.10.0")
end MyVersions
