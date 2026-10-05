import zipx.*

// core is stated at one commit. old-client was built against an older line and peer-client against another commit of
// core's line; neither changes what this build resolves.
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val core                = Lib("com.example.cw", "core", "1.1.0-aaaaaaaaaaaa-SNAPSHOT")
  val oldClient           = Lib("com.example.cw", "old-client", "1.0.0")
  val peerClient          = Lib("com.example.cw", "peer-client", "1.0.0")

  def clients: Seq[Lib] = Seq(oldClient, peerClient)
end MyVersions
