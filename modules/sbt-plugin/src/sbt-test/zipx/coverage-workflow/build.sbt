scalaVersion   := "3.8.4"
version        := "1.0.0-ci"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-ci")
zipxVerify     := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

lazy val core = project

lazy val root = (project in file("."))
  .aggregate(core)
  .settings(publish / skip := true)

val assertCoverageWorkflow = taskKey[Unit]("the companion holds one restore-only coverage job, and ci.yml none")
assertCoverageWorkflow := {
  val root     = (LocalRootProject / baseDirectory).value
  val coverage = IO.read(root / ".github" / "workflows" / "zipx-coverage.yml")
  val ci       = IO.read(root / ".github" / "workflows" / "ci.yml")
  assert(coverage.contains("sbt 'coverage; testFull; coverageAggregate'"), "companion should run the coverage session")
  assert(coverage.contains("cache-mode: restore"), "companion should restore the build snapshot")
  assert(!coverage.contains("cache-mode: save"), "companion must never save the build snapshot")
  assert(coverage.contains("- labeled"), "a PR label trigger should listen for labeled")
  assert(coverage.contains("workflow_dispatch"), "Dispatch should add workflow_dispatch")
  assert(!ci.contains("coverage"), "ci.yml must not measure coverage")
}
