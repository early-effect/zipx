MyVersions.settings
organization := "com.example.zipx.drift"
zipxVerify   := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

LocalRootProject / zipxReleaseWorkflow :=
  Some(ReleaseWorkflow(ArtifactRegistry.Url(file("released").getAbsoluteFile.toURI.toString)))

lazy val models = project
lazy val extra  = project.settings(if (file("v2").exists) Nil else Seq(publish / skip := true))
lazy val side   = project

lazy val root = (project in file(".")).aggregate(models, extra, side).settings(publish / skip := true)

def ivyLocalRepo: File = file(sys.props("user.home")) / ".ivy2" / "local" / "com.example.zipx.drift"

def headSha(root: File): String =
  scala.sys.process.Process(Seq("git", "rev-parse", "HEAD"), root).!!.trim.toLowerCase

val forgetIvyLocal = taskKey[Unit]("Remove this fixture's organization from the machine's ivy repository")
forgetIvyLocal := Def.uncached(IO.delete(ivyLocalRepo))

val assertDrift = taskKey[Unit]("A released row with changes since its tag is reported, naming the tag")
assertDrift := Def.uncached {
  val drift = zipxReleaseDrift.value
  assert(drift == Seq("""ShipGroup("libs") 1.0.0 since libs/v1.0.0"""), drift.toString)
}

val assertOpenRowPublished = taskKey[Unit]("an unreleased sibling publishes its commit id, and the released row publishes nothing")
assertOpenRowPublished := Def.uncached {
  val abbrev  = headSha((LocalRootProject / baseDirectory).value).take(12)
  val sideJar = ivyLocalRepo / "side_3" / s"0.2.0-$abbrev-SNAPSHOT" / "jars" / "side_3.jar"
  val models  = ivyLocalRepo / "models_3"
  assert(!models.exists, s"published a released row at $models")
  assert(sideJar.isFile, s"no open-row snapshot at $sideJar")
}

val assertNotShadowed = taskKey[Unit]("the released row was not published, as a commit id or as the pointer")
assertNotShadowed := Def.uncached {
  val models = ivyLocalRepo / "models_3"
  assert(!models.exists, s"published a released row at $models")
}

val assertBumped = taskKey[Unit]("no-arg zipxModverBump patches the released row and leaves the unreleased sibling")
assertBumped := Def.uncached {
  val src = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  assert(src.contains("""ShipGroup("libs", "1.0.1")("models")"""), src)
  assert(src.contains("""Ship("side", "0.2.0")"""), src)
}

val assertOpened = taskKey[Unit]("the bumped row publishes its new snapshot id")
assertOpened := Def.uncached {
  val abbrev = headSha((LocalRootProject / baseDirectory).value).take(12)
  val prefix = s"1.0.1-$abbrev"
  val models = ivyLocalRepo / "models_3"
  val names  = Option(models.list).map(_.toList).getOrElse(Nil)
  names.filter(_.startsWith(prefix)) match
    case only :: Nil =>
      assert((models / only / "jars" / "models_3.jar").isFile, s"no opened snapshot at $only")
    case other =>
      assert(false, s"opened snapshots under $prefix: $other from $names")
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
