import zipx.*

// left and right were built against two commits of shared's 0.3.0 line, and the catalog states no shared.
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val left                = Lib("com.example.cw", "left", "1.0.0")
  val right               = Lib("com.example.cw", "right", "1.0.0")

  def clients: Seq[Lib] = Seq(left, right)
end MyVersions
