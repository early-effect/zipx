// The registry is static POMs, so update needs no jars and no network. Scenarios swap only the catalog.
MyVersions.settings
zipxVerify := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
ThisBuild / resolvers += "catalog-wins" at file("registry").getAbsoluteFile.toURI.toString

lazy val app = (projectMatrix in file("app"))
  .settings(libraryDependencies ++= MyVersions.deps(MyVersions.clients*))
  .jvmPlatform(scalaVersions = Seq("3.9.0"))
  .jsPlatform(scalaVersions = Seq("3.9.0"))

lazy val root = (project in file("."))
  .aggregate(app.projectRefs*)
  .settings(publish / skip := true)

val assertJvm = inputKey[Unit]("the JVM row selects <artifact> at <revision>")
Fixture.selects(assertJvm, LocalProject("app"))

val assertJs = inputKey[Unit]("the Scala.js row selects <artifact> at <revision>")
Fixture.selects(assertJs, LocalProject("appJS"))
