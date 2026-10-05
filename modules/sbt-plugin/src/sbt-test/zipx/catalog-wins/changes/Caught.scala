import sbt.{ModuleID, Setting}
import zipx.*

// The catalog moved to the line new-client needs.
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val core                = Lib("com.example.cw", "core", "1.2.0")
  val newClient           = Lib("com.example.cw", "new-client", "1.0.0")

  def clients: Seq[Lib] = Seq(newClient)
end MyVersions

object Scenario:
  def catalog: Seq[Setting[?]] = MyVersions.settings
  def clients: Seq[ModuleID]   = MyVersions.deps(MyVersions.clients*)
