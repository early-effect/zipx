import sbt.{ModuleID, Setting}
import zipx.*

// The catalog pins one of the two commits.
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val left                = Lib("com.example.cw", "left", "1.0.0")
  val right               = Lib("com.example.cw", "right", "1.0.0")
  val shared              = Lib("com.example.cw", "shared", "0.3.0-aaaaaaaaaaaa-SNAPSHOT")

  def clients: Seq[Lib] = Seq(left, right)
end MyVersions

object Scenario:
  def catalog: Seq[Setting[?]] = MyVersions.settings
  def clients: Seq[ModuleID]   = MyVersions.deps(MyVersions.clients*)
