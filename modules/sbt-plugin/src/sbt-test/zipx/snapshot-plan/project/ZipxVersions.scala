import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")

  val widgets = Lib("com.example.zipx.plan", "widgets", "1.4.2-aaaaaaaaaaaa")
  val local   = Lib("com.example.zipx.plan", "localpin", "1.4.2-bbbbbbbbbbbb+20140707-1030")
  val client  = Ship("client", "0.3.0")
end MyVersions
