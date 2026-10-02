MyVersions.settings
organization        := "com.example.zipx.release"
zipxCacheEpoch      := CacheEpoch.ShipCatalog
zipxVerify          := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
LocalRootProject / zipxReleaseWorkflow := Some(ReleaseWorkflow(ArtifactRegistry.Url(file("released").getAbsoluteFile.toURI.toString)))
zipxCapabilities ++= Seq(Capability.snapshots(), ZipxCentral.pullRequestSnapshots("snapshots"))

val released = file("released").getAbsoluteFile

val toFixtureRepo = Seq.empty[Setting[?]]

lazy val models = project.settings(toFixtureRepo)

lazy val coreLib = (project in file("core-lib")).dependsOn(models).settings(toFixtureRepo)

lazy val client = project.dependsOn(coreLib).settings(toFixtureRepo)

ThisBuild / versionScheme := Some("early-semver")

// Built against the released client, as a library from another repo would be. early-semver compares 0.y.0 and x.0.0
// exactly, -SNAPSHOT included, so sbt alone rejects the in-repo 0.3.0-SNAPSHOT against it.
def usesClient = Seq(
  libraryDependencies += "com.example.ext" %% "uses-client" % "1.0.0",
  resolvers += "fixture" at released.toURI.toString,
)

// Does not publish, like a docs site: its own build is the proof, so a conflict is a warning.
lazy val consumer = project
  .dependsOn(client)
  .settings(publish / skip := true, if (file("ext").exists) usesClient else Nil)

// Publishes, so a conflict would ship in its POM and fails.
lazy val downstream = project.dependsOn(client).settings(if (file("ext").exists) usesClient else Nil)

lazy val root = (project in file(".")).aggregate(models, coreLib, client).settings(publish / skip := true)

val writeExternalLib = taskKey[Unit]("An external library in the registry, built against client 0.3.0")
writeExternalLib := Def.uncached {
  val dir = released / "com" / "example" / "ext" / "uses-client_3" / "1.0.0"
  IO.write(
    dir / "uses-client_3-1.0.0.pom",
    """<project><modelVersion>4.0.0</modelVersion><groupId>com.example.ext</groupId>
      |<artifactId>uses-client_3</artifactId><version>1.0.0</version>
      |<dependencies><dependency><groupId>com.example.zipx.release</groupId><artifactId>client_3</artifactId>
      |<version>0.3.0</version></dependency></dependencies></project>""".stripMargin,
  )
  IO.zip(Seq.empty, dir / "uses-client_3-1.0.0.jar", None)
}

val assertDeploymentName = taskKey[Unit]("A Central deployment is named after the rows it releases")
assertDeploymentName := Def.uncached {
  val name = sonaDeploymentName.value
  assert(name == "com.example.zipx.release libs 1.4.2, client 0.3.0", name)
}

val assertReleaseWorkflow = taskKey[Unit]("zipx-release.yml runs zipxRelease on a tag or a default-branch dispatch")
assertReleaseWorkflow := {
  val yml = IO.read((LocalRootProject / baseDirectory).value / ".github/workflows/zipx-release.yml")
  assert(yml.contains("sbt \"zipxRelease $ZIPX_RELEASE_REF\""), yml)
  assert(yml.contains("Open the next snapshot"), yml)
  assert(yml.contains("GITHUB_STEP_SUMMARY"), yml)
  assert(yml.contains("target/zipx-release-tags.txt"), yml)
  assert(yml.contains("sbt zipxModverBump"), yml)
  assert(yml.contains("- \"*/v*\"") && !yml.contains("- v*"), yml)
  assert(yml.contains("workflow_dispatch"), yml)
  assert(yml.contains("ships:"), yml)
  assert(yml.contains("default: all"), yml)
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

val assertPrSnapshotsPublished = taskKey[Unit]("a PR snapshot publishes <row>-pr<N>-SNAPSHOT, and builds what it tested")
assertPrSnapshotsPublished := Def.uncached {
  val base = released / "com" / "example" / "zipx" / "release"
  val pom  = IO.read(base / "client_3" / "0.3.0-pr42-SNAPSHOT" / "client_3-0.3.0-pr42-SNAPSHOT.pom")
  assert(pom.contains("<version>1.4.2-pr42-SNAPSHOT</version>"), pom)
  assert((base / "models_3" / "1.4.2-pr42-SNAPSHOT" / "models_3-1.4.2-pr42-SNAPSHOT.jar").exists, "models has no PR jar")
  assert((client / version).value == "0.3.0-SNAPSHOT", (client / version).value)
  val yml = IO.read((LocalRootProject / baseDirectory).value / ".github/workflows/ci.yml")
  assert(yml.contains("  snapshots-pr:") && yml.contains("zipxSnapshotPublish pr"), yml)
}

def ivyLocalRepo: File = file(sys.props("user.home")) / ".ivy2" / "local" / "com.example.zipx.release"

val forgetIvyLocal = taskKey[Unit]("Remove this fixture's organization from the machine's ivy repository")
forgetIvyLocal := Def.uncached(IO.delete(ivyLocalRepo))

val assertLocalSnapshots = taskKey[Unit]("zipxSnapshotPublish local publishes unreleased rows to ivy-local, and the shell is a development session again")
assertLocalSnapshots := Def.uncached {
  val models = ivyLocalRepo / "models_3" / "1.4.2-SNAPSHOT"
  assert((models / "jars" / "models_3.jar").exists, s"no local models jar under $models")
  assert(!(models / "docs").exists, "a local snapshot publish carries no scaladoc")
  assert(!sys.props.contains("zipx.session"), s"session left at ${sys.props.get("zipx.session")}")
  assert((client / Compile / packageDoc / publishArtifact).value, "a development session publishes docs again")
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
