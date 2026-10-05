// Heddle: a server library built against ascent's facade, plus an MCP apps module ascent's app depends on.
MyVersions.settings
Fixture.settings
organization                           := Fixture.Organization
zipxVerify                             := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
ThisBuild / versionScheme              := Some("early-semver")
LocalRootProject / zipxReleaseWorkflow := Some(Fixture.releaseWorkflow)

val scala3 = "3.9.0"

lazy val heddle = (projectMatrix in file("heddle"))
  .settings(name := "heddle", Fixture.resolve, MyVersions.library(MyVersions.facade))
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val heddleMcpApps = (projectMatrix in file("heddle-mcp-apps"))
  .dependsOn(heddle)
  .settings(name := "heddle-mcp-apps", Fixture.resolve)
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val root = (project in file("."))
  .aggregate((heddle.projectRefs ++ heddleMcpApps.projectRefs) *)
  .settings(publish / skip := true)

val assertHeddleNamesFacade = inputKey[Unit]("heddle's POM at a recorded commit names the facade it was built against")
Fixture.pomNames(assertHeddleNamesFacade, "heddle_sjs1_3", "0.9.0", "ascent-dom-facade_sjs1_3")
