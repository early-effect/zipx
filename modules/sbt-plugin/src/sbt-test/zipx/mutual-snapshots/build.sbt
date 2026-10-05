// Ascent: a JS-only facade in its own ship, and an app that also depends on heddle, which is built against that
// facade. Heddle's build is changes/heddle.sbt; the test swaps the two in place, one directory, one git history.
// Ascent pins heddle only at commits and names no snapshot repository: zipx adds it for the commit pin.
MyVersions.settings
Fixture.settings
organization                           := Fixture.Organization
zipxVerify                             := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
ThisBuild / versionScheme              := Some("early-semver")
LocalRootProject / zipxReleaseWorkflow := Some(Fixture.releaseWorkflow)

val scala3 = "3.9.0"

lazy val domFacade = (projectMatrix in file("dom-facade"))
  .settings(name := "ascent-dom-facade")
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val js = (projectMatrix in file("js"))
  .dependsOn(domFacade)
  .settings(name := "ascent-js")
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val mcpApp = (projectMatrix in file("mcp-app"))
  .dependsOn(js)
  .settings(
    name := "ascent-mcp-app",
    if (file("use-heddle").exists) MyVersions.library(MyVersions.heddleMcpApps) else Nil,
  )
  .jsPlatform(scalaVersions = Seq(scala3))

// Publishes nothing, like ascent's docs site, and later takes a docs framework built against heddle, like specular.
lazy val docs = (projectMatrix in file("docs"))
  .dependsOn(mcpApp)
  .settings(
    name           := "ascent-docs",
    publish / skip := true,
    if (file("use-docs-framework").exists) MyVersions.library(MyVersions.docsFramework) else Nil,
  )
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val root = (project in file("."))
  .aggregate((domFacade.projectRefs ++ js.projectRefs ++ mcpApp.projectRefs ++ docs.projectRefs) *)
  .settings(publish / skip := true)

val assertFacadeInRepo = inputKey[Unit]("mcpApp compiles the in-repo facade, whatever heddle was built against")
Fixture.inRepoWins(assertFacadeInRepo, LocalProject("mcpAppJS"), LocalProject("domFacadeJS"))

val assertDocsFacadeInRepo = inputKey[Unit]("the docs site compiles the in-repo facade too")
Fixture.inRepoWins(assertDocsFacadeInRepo, LocalProject("docsJS"), LocalProject("domFacadeJS"))

val assertDocsSelects = inputKey[Unit]("the docs site resolves <artifact> at a release or a recorded commit")
Fixture.selects(assertDocsSelects, LocalProject("docsJS"))

val assertAppExcludes = inputKey[Unit]("the app's POM at a recorded commit keeps heddle-mcp-apps from <excluded>...")
Fixture.pomExcludes(assertAppExcludes, "ascent-mcp-app_sjs1_3", "0.10.0", "heddle-mcp-apps_sjs1_3")

val assertZipxResolver = taskKey[Unit]("a commit pin in the catalog brings the registry's snapshot repository")
assertZipxResolver / aggregate := false
assertZipxResolver := Def.uncached {
  val expected = zipx.core.SnapshotPins.resolverName(Fixture.releaseWorkflow.registry)
  val names    = (LocalProject("mcpAppJS") / resolvers).value.map(_.name)
  assert(names.contains(expected), s"no $expected in $names")
}
