scalaVersion   := "3.8.4"
version        := "1.0.0-ci"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-ci")
zipxVerify     := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

// Each module's test task leaves a marker, so the test reads exactly which modules a session tested.
val recordTested = taskKey[Unit]("marks this module as tested")

def recording = Seq(
  recordTested := Def.uncached(
    IO.touch((LocalRootProject / baseDirectory).value / "target" / "tested" / thisProject.value.id)
  ),
  zipxTestTask := zipxTasks.of(recordTested),
)

lazy val core = project.settings(recording)
lazy val app  = project.dependsOn(core).settings(recording)
// In the graph, but outside the root aggregate: a root test never reaches it, so neither may an affected one.
lazy val side = project.settings(recording)

lazy val root = (project in file("."))
  .aggregate(core, app)
  .settings(recording, publish / skip := true)

val assertTestCommand = taskKey[Unit]("the builtin test job runs zipxTestAffected with the PR base")
assertTestCommand := {
  val ci = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml")
  assert(
    ci.contains("sbt 'zipxTestAffected ${{ github.event.pull_request.base.sha }}'"),
    "test should run zipxTestAffected with the PR base",
  )
}
