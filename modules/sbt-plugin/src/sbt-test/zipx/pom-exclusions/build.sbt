// What a published POM keeps its libraries from bringing: the in-repo modules a consumer inherits through it, and the
// commit pins it compiles against. The registry is static POMs, so nothing is downloaded.
MyVersions.settings
zipxVerify                 := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
ThisBuild / organization   := "com.example.pe"
ThisBuild / version        := "0.1.0"
ThisBuild / versionScheme  := Some("early-semver")
ThisBuild / resolvers      += "pom-exclusions" at file("registry").getAbsoluteFile.toURI.toString

lazy val a = project
lazy val b = project.dependsOn(a)
lazy val t = project
lazy val p = project
lazy val c = project
  .dependsOn(b, t % "test->test", p % "provided->compile")
  .settings(libraryDependencies ++= MyVersions.deps(MyVersions.lib, MyVersions.pin, MyVersions.testPin))

lazy val root = (project in file("."))
  .aggregate(a, b, t, p, c)
  .settings(publish / skip := true)

val assertExcludes = inputKey[Unit]("in c's POM, <artifact> excludes exactly <excluded>...")
Fixture.excludes(assertExcludes, LocalProject("c"))
