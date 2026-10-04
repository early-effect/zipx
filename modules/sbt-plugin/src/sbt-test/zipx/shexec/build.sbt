scalaVersion   := "3.9.0"
version        := "1.0.0-SNAPSHOT"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-SNAPSHOT")
zipxVerify     := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

// autoImport must leave sbt.Exec unambiguous; shell steps use ShExec.
val shexecProof = taskKey[Unit]("Exec in autoImport scope is sbt.Exec")
shexecProof := {
  val ev: Exec =:= sbt.Exec = summon
  val _                     = ev
  ()
}

zipxCapabilities += Capability.test.withExtraSteps(
  Steps.built("noop")(Step.run(Script(ShExec("true"))).named("noop"))
)

lazy val root = (project in file("."))
  .settings(publish / skip := true)

val assertNoopStep = taskKey[Unit]("generated CI contains the ShExec extra step")
assertNoopStep := {
  val yml = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml")
  assert(yml.contains("noop"), s"expected extra step named noop in ci.yml, got:\n$yml")
}
