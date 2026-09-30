MyVersions.settings
organization        := "com.example.zipx.release"
zipxCacheEpoch      := CacheEpoch.ShipCatalog
zipxVerify          := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
zipxReleaseWorkflow := Some(ReleaseWorkflow(ArtifactRegistry.Url("https://repo1.maven.org/maven2")))
zipxCapabilities += Capability.snapshots()

val released = file("released").getAbsoluteFile

val toFixtureRepo = Seq(publishTo := Some(Resolver.file("fixture", released)))

lazy val models = project.settings(toFixtureRepo)

lazy val coreLib = (project in file("core-lib")).dependsOn(models).settings(toFixtureRepo)

lazy val client = project.dependsOn(coreLib).settings(toFixtureRepo)

lazy val root = (project in file(".")).aggregate(models, coreLib, client).settings(publish / skip := true)

val assertReleaseWorkflow = taskKey[Unit]("zipx-release.yml runs zipxRelease on a tag or a default-branch dispatch")
assertReleaseWorkflow := {
  val yml = IO.read((LocalRootProject / baseDirectory).value / ".github/workflows/zipx-release.yml")
  assert(yml.contains("sbt \"zipxRelease $ZIPX_RELEASE_REF\""), yml)
  assert(yml.contains("- \"*/v*\"") && !yml.contains("- v*"), yml)
  assert(yml.contains("workflow_dispatch"), yml)
  assert(yml.contains("environment: zipx-release"), yml)
  assert(yml.contains("cache-mode: restore") && !yml.contains("cache-mode: save"), yml)
}

val assertSnapshotsJob = taskKey[Unit]("ci.yml publishes snapshots on a default-branch push from a restored cache")
assertSnapshotsJob := {
  val yml  = IO.read((LocalRootProject / baseDirectory).value / ".github/workflows/ci.yml")
  val body = yml.linesIterator.dropWhile(_ != "  snapshots:").takeWhile(l => l == "  snapshots:" || !l.matches("  [a-z-]+:")).mkString("\n")
  assert(body.contains("zipxSnapshotPublish"), yml)
  assert(body.contains("cache-mode: restore"), body)
  assert(body.contains("needs.test.result != 'failure'"), body)
}

val assertSnapshotsPublished = taskKey[Unit]("every unreleased row is published at <row>-SNAPSHOT, without scaladoc")
assertSnapshotsPublished := {
  val base = released / "com" / "example" / "zipx" / "release"
  for (artifact, version) <- List("models_3" -> "1.4.2", "corelib_3" -> "1.4.2", "client_3" -> "0.3.0") do
    val dir = base / artifact / s"$version-SNAPSHOT"
    assert((dir / s"$artifact-$version-SNAPSHOT.jar").exists, s"$dir has no jar: ${Option(dir.list).map(_.toList)}")
    assert((dir / s"$artifact-$version-SNAPSHOT-sources.jar").exists, s"$dir has no sources jar")
    assert(!(dir / s"$artifact-$version-SNAPSHOT-javadoc.jar").exists, s"$dir has a scaladoc jar")
}

val assertSnapshotVersions = taskKey[Unit]("outside a release every row member is <row>-SNAPSHOT")
assertSnapshotVersions := {
  assert((models / version).value == "1.4.2-SNAPSHOT", (models / version).value)
  assert((client / version).value == "0.3.0-SNAPSHOT", (client / version).value)
}

val assertReleased = taskKey[Unit]("client and its unreleased upstream row published at their catalog numbers")
assertReleased := {
  val base      = released / "com" / "example" / "zipx" / "release"
  val clientPom = IO.read(base / "client_3" / "0.3.0" / "client_3-0.3.0.pom")
  assert(clientPom.contains("<version>1.4.2</version>"), clientPom)
  assert(!clientPom.contains("SNAPSHOT"), clientPom)
  assert((base / "models_3" / "1.4.2" / "models_3-1.4.2.jar").exists, "models 1.4.2 was not released")
  assert((base / "models_3" / "1.4.2" / "models_3-1.4.2-javadoc.jar").exists, "a release carries scaladoc")
  assert(!(base / "models_3" / "1.4.2" / "models_3-1.4.2-tests-javadoc.jar").exists, "a release publishes no test docs")
  val tags = IO.readLines((LocalRootProject / baseDirectory).value / "target" / "zipx-release-tags.txt")
  assert(tags == List("libs/v1.4.2", "client/v0.3.0"), tags.toString)
}

val assertReleaseSession = taskKey[Unit]("the release session keeps catalog numbers, across a Scala version switch")
assertReleaseSession := {
  assert((models / version).value == "1.4.2", (models / version).value)
  assert((client / version).value == "0.3.0", (client / version).value)
}
