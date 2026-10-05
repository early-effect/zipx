import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val lib                 = Lib("com.example.pe", "lib", "1.0.0")
  val pin                 = Lib("com.example.pe", "pin", "0.2.0-cccccccccccc-SNAPSHOT")
  val testPin             = Lib("com.example.pe", "test-pin", "0.4.0-dddddddddddd-SNAPSHOT").test
end MyVersions
