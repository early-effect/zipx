MyVersions.settings
organization := "com.example.zipx.drift"
zipxVerify   := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

LocalRootProject / zipxReleaseWorkflow :=
  Some(ReleaseWorkflow(ArtifactRegistry.Url(file("released").getAbsoluteFile.toURI.toString)))

lazy val models = project
lazy val extra  = project.settings(if (file("v2").exists) Nil else Seq(publish / skip := true))

lazy val root = (project in file(".")).aggregate(models, extra).settings(publish / skip := true)

val assertDrift = taskKey[Unit]("A released row with changes since its tag is reported, naming the tag")
assertDrift := Def.uncached {
  val drift = zipxReleaseDrift.value
  assert(drift == Seq("""ShipGroup("libs") 1.0.0 since v1.0.0"""), drift.toString)
}

val assertNoDrift = taskKey[Unit]("A row released at its number with no changes since its tag is not reported")
assertNoDrift := Def.uncached {
  val drift = zipxReleaseDrift.value
  assert(drift.isEmpty, drift.toString)
}

val assertMeasuredFromRelease = taskKey[Unit]("modver-check measured the grown group from its real last release")
assertMeasuredFromRelease := Def.uncached {
  val report = IO.read((LocalRootProject / baseDirectory).value / "target" / "zipx-modver-report.json").filterNot(_.isWhitespace)
  assert(report.contains("\"from\":\"1.0.0\""), report)
  assert(report.contains("\"written\":\"1.1.0\""), report)
}
