// Ascent: a JS-only facade in its own ship, and an app that also depends on heddle, which is built against that
// facade. Heddle's build is changes/heddle.sbt; the test swaps the two in place, one directory, one git history.
MyVersions.settings
Fixture.settings
organization                           := Fixture.Organization
zipxVerify                             := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
ThisBuild / versionScheme              := Some("early-semver")
LocalRootProject / zipxReleaseWorkflow := Some(Fixture.releaseWorkflow)

val scala3 = "3.9.0"

lazy val domFacade = (projectMatrix in file("dom-facade"))
  .settings(name := "ascent-dom-facade", Fixture.resolve)
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val js = (projectMatrix in file("js"))
  .dependsOn(domFacade)
  .settings(name := "ascent-js", Fixture.resolve)
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val mcpApp = (projectMatrix in file("mcp-app"))
  .dependsOn(js)
  .settings(
    name := "ascent-mcp-app",
    Fixture.resolve,
    if (file("use-heddle").exists) MyVersions.library(MyVersions.heddleMcpApps) else Nil,
  )
  .jsPlatform(scalaVersions = Seq(scala3))

// Publishes nothing, like ascent's docs site.
lazy val docs = (projectMatrix in file("docs"))
  .dependsOn(mcpApp)
  .settings(name := "ascent-docs", Fixture.resolve, publish / skip := true)
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val root = (project in file("."))
  .aggregate((domFacade.projectRefs ++ js.projectRefs ++ mcpApp.projectRefs ++ docs.projectRefs) *)
  .settings(publish / skip := true)

val assertFacadeInRepo = inputKey[Unit]("mcpApp compiles the in-repo facade, whatever heddle was built against")
Fixture.inRepoWins(assertFacadeInRepo, LocalProject("mcpAppJS"), LocalProject("domFacadeJS"))

val assertDocsFacadeInRepo = inputKey[Unit]("the docs site compiles the in-repo facade too")
Fixture.inRepoWins(assertDocsFacadeInRepo, LocalProject("docsJS"), LocalProject("domFacadeJS"))
