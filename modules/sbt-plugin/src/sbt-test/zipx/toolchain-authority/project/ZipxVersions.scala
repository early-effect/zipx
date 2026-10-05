import zipx.*

// A test library built against an older Scala Native test-interface than the plugin injects, as zio-test-sbt can be.
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")
  val testkit             = Lib("com.example.ta", "testkit", "1.0.0").test
end MyVersions
