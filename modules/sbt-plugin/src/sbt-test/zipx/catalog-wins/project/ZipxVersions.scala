import sbt.{ModuleID, Setting}
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

/** What build.sbt reads. sbt does not recompile an inline `settings` expansion when the catalog gains or loses a row,
  * so the expansion lives in the file each scenario swaps.
  */
object Scenario:
  def catalog: Seq[Setting[?]] = MyVersions.settings
  def clients: Seq[ModuleID]   = MyVersions.deps(MyVersions.clients*)
