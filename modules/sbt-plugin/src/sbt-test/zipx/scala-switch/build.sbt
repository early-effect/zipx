ThisBuild / organization  := "com.example.sw"
ThisBuild / version       := "0.1.0"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalaVersion  := "3.9.0"
zipxVerify                := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
resolvers += "scala-switch" at file("registry").getAbsoluteFile.toURI.toString

lazy val models = project.settings(crossScalaVersions := Seq("3.9.0", "2.13.16"))

lazy val app = project
  .dependsOn(models)
  .settings(
    scalaVersion := "3.9.0",
    libraryDependencies += "com.example.sw" %% "lib" % "1.0.0",
  )

lazy val legacy = project
  .dependsOn(models)
  .settings(
    scalaVersion := "2.13.16",
    libraryDependencies += "com.example.sw" %% "lib" % "1.0.0",
  )

lazy val root = (project in file("."))
  .aggregate(models, app, legacy)
  .settings(publish / skip := true)

val assertLegacy = inputKey[Unit]("in legacy's POM, <artifact> excludes exactly <excluded>...")
val assertApp    = inputKey[Unit]("in app's POM, <artifact> excludes exactly <excluded>...")
Fixture.excludes(assertLegacy, LocalProject("legacy"))
Fixture.excludes(assertApp, LocalProject("app"))
