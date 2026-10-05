// The catalog states only ascent's app, so what the demo resolves for the app's dependencies is what the published
// POMs say.
MyVersions.settings
Fixture.settings
organization := "com.example.demo"
zipxVerify   := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

lazy val demo = (projectMatrix in file("demo"))
  .settings(name := "demo", Fixture.resolve, MyVersions.library(MyVersions.mcpApp))
  .jsPlatform(scalaVersions = Seq("3.9.0"))

lazy val root = (project in file("."))
  .aggregate(demo.projectRefs*)
  .settings(publish / skip := true)

val assertDemoSelects = inputKey[Unit]("the demo resolves <artifact> at a release or a recorded commit")
Fixture.selects(assertDemoSelects, LocalProject("demoJS"))
