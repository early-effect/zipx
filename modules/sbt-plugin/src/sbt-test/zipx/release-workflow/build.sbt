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
// exactly, so sbt alone rejects the in-repo `-ci` client against it. The in-repo client is what compiles either way.
def usesClient = Seq(
  libraryDependencies += "com.example.ext" %% "uses-client" % "1.0.0",
  resolvers += "fixture" at released.toURI.toString,
)

lazy val consumer = project
  .dependsOn(client)
  .settings(publish / skip := true, if (file("ext").exists) usesClient else Nil)

lazy val downstream = project.dependsOn(client).settings(if (file("ext").exists) usesClient else Nil)

lazy val root = (project in file(".")).aggregate(models, coreLib, client).settings(publish / skip := true)

// Pins the models commit as every registry stores it (`<line>-<sha>-SNAPSHOT`). Not aggregated: its update is the
// proof.
lazy val pinned = project.settings(
  publish / skip := true,
  libraryDependencies ++= commitPins((LocalRootProject / baseDirectory).value),
  resolvers += "fixture" at released.toURI.toString,
)

// The pointer coordinate, added once `want-pointer` exists so workflow generate does not see an uncatalogued dep.
lazy val badpin = project.settings(
  publish / skip := true,
  libraryDependencies ++= {
    if file("want-pointer").exists then Seq("com.example.zipx.release" %% "models" % "1.4.2-SNAPSHOT") else Nil
  },
  resolvers += "fixture" at released.toURI.toString,
)

def commitPins(root: File): Seq[ModuleID] =
  val out  = new StringBuilder
  val code = scala.sys.process
    .Process(Seq("git", "rev-parse", "HEAD"), root)
    .!(scala.sys.process.ProcessLogger(out ++= _, _ => ()))
  if code != 0 then Nil
  else
    val full = out.toString.trim.toLowerCase
    if full.length != 40 then Nil
    else Seq("com.example.zipx.release" %% "models" % s"1.4.2-${full.take(12)}-SNAPSHOT")

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
  assert(body.contains("needs.verify.result != 'failure'"), body)
  assert(!body.contains("needs.test.result"), body)
}

def headSha(root: File): String =
  scala.sys.process.Process(Seq("git", "rev-parse", "HEAD"), root).!!.trim.toLowerCase

val assertSnapshotsPublished = taskKey[Unit]("a clean commit publishes <line>-<sha>-SNAPSHOT and a pointer POM, without scaladoc")
assertSnapshotsPublished := {
  val root   = (LocalRootProject / baseDirectory).value
  val base   = released / "com" / "example" / "zipx" / "release"
  val full   = headSha(root)
  val abbrev = full.take(12)
  for (artifact, version) <- List("models_3" -> "1.4.2", "corelib_3" -> "1.4.2", "client_3" -> "0.3.0") do
    val id  = s"$version-$abbrev-SNAPSHOT"
    val dir = base / artifact / id
    assert((dir / s"$artifact-$id.jar").exists, s"$dir has no jar: ${Option(dir.list).map(_.toList)}")
    assert((dir / s"$artifact-$id-sources.jar").exists, s"$dir has no sources jar")
    assert(!(dir / s"$artifact-$id-javadoc.jar").exists, s"$dir has a scaladoc jar")
    val pointer = IO.read(base / artifact / s"$version-SNAPSHOT" / s"$artifact-$version-SNAPSHOT.pom")
    assert(pointer.contains(full), pointer)
}

val assertPrSnapshotsPublished = taskKey[Unit]("a PR publishes its own sha and leaves the pointer on the first commit")
assertPrSnapshotsPublished := Def.uncached {
  val root    = (LocalRootProject / baseDirectory).value
  val base    = released / "com" / "example" / "zipx" / "release"
  val pointer = IO.read(base / "client_3" / "0.3.0-SNAPSHOT" / "client_3-0.3.0-SNAPSHOT.pom")
  val full    = headSha(root)
  val first   = scala.sys.process
    .Process(Seq("git", "rev-parse", "HEAD~1"), root)
    .!!.trim
    .toLowerCase
  assert(pointer.contains(first), pointer)
  assert(!pointer.contains(full), pointer)
  val id = s"0.3.0-${full.take(12)}-SNAPSHOT"
  assert((base / "client_3" / id / s"client_3-$id.jar").exists, s"client has no jar at $id")
  assert((client / version).value == "0.3.0-ci", (client / version).value)
  val yml = IO.read((LocalRootProject / baseDirectory).value / ".github/workflows/ci.yml")
  assert(yml.contains("  snapshots-pr:") && yml.contains("zipxSnapshotPublish pr"), yml)
}

def ivyLocalRepo: File = file(sys.props("user.home")) / ".ivy2" / "local" / "com.example.zipx.release"

def git(root: File, args: String*): Unit =
  val err  = new StringBuilder
  val code = scala.sys.process.Process("git" +: args, root).!(scala.sys.process.ProcessLogger(_ => (), line => err ++= line))
  if code != 0 then sys.error(s"git ${args.mkString(" ")} exited $code: $err")

val initGit = taskKey[Unit]("Commit the fixture so a snapshot publish has a sha")
initGit / aggregate := false
initGit := Def.uncached {
  val root = (LocalRootProject / baseDirectory).value
  IO.write(root / ".gitignore", "target/\nreleased/\nsha-digest\n.bsp/\n.bloop/\nglobal/\n")
  git(root, "init")
  git(root, "config", "user.email", "zipx@example.com")
  git(root, "config", "user.name", "zipx")
  git(root, "add", ".")
  git(root, "commit", "-m", "init")
}

val commitAll = taskKey[Unit]("Commit the dirty tree as a second sha")
commitAll / aggregate := false
commitAll := Def.uncached {
  val root = (LocalRootProject / baseDirectory).value
  git(root, "add", ".")
  git(root, "commit", "-m", "second")
}

val forgetIvyLocal = taskKey[Unit]("Remove this fixture's organization from the machine's ivy repository")
forgetIvyLocal := Def.uncached(IO.delete(ivyLocalRepo))

val assertLocalSnapshots = taskKey[Unit]("zipxSnapshotPublish local publishes the stored commit to ivy-local, and the shell is a development session again")
assertLocalSnapshots := Def.uncached {
  val models = ivyLocalRepo / "models_3" / s"1.4.2-${headSha((LocalRootProject / baseDirectory).value).take(12)}-SNAPSHOT"
  assert((models / "jars" / "models_3.jar").exists, s"no local models jar under $models")
  assert(!(ivyLocalRepo / "models_3" / "1.4.2-SNAPSHOT").exists, "local publish must not occupy the pointer")
  assert(!(models / "docs").exists, "a local snapshot publish carries no scaladoc")
  assert(!sys.props.contains("zipx.session"), s"session left at ${sys.props.get("zipx.session")}")
  assert((client / Compile / packageDoc / publishArtifact).value, "a development session publishes docs again")
}

val assertLocalPinResolves = taskKey[Unit]("the pin a registry would hold resolves from ivy-local after a local publish")
assertLocalPinResolves / aggregate := false
assertLocalPinResolves := Def.uncached {
  val jars = (pinned / updateFull).value.allFiles.filter(_.getName.startsWith("models_3")).toList
  jars match
    case one :: _ => assert(one.getAbsolutePath.contains(".ivy2"), one.toString)
    case Nil      => sys.error("the stored pin did not resolve from ivy-local")
  lmcoursier.internal.SbtCoursierCache.default.clear()
}

val assertSnapshotVersions = taskKey[Unit]("outside a release every row member compiles at <row>-ci")
assertSnapshotVersions := {
  assert((models / version).value == "1.4.2-ci", (models / version).value)
  assert((client / version).value == "0.3.0-ci", (client / version).value)
  assert((models / isSnapshot).value, "a development ship is a snapshot")
}

def versionDirs(artifact: String): List[String] =
  val dir = released / "com" / "example" / "zipx" / "release" / artifact
  Option(dir.listFiles()).toList.flatten.filter(_.isDirectory).map(_.getName).sorted

val assertOnlyShaAndPointer = taskKey[Unit]("republishing the commit overwrites that sha and the pointer")
assertOnlyShaAndPointer / aggregate := false
assertOnlyShaAndPointer := Def.uncached {
  val root   = (LocalRootProject / baseDirectory).value
  val full   = headSha(root)
  val abbrev = full.take(12)
  for (artifact, version) <- List("models_3" -> "1.4.2", "corelib_3" -> "1.4.2", "client_3" -> "0.3.0") do
    val names = versionDirs(artifact)
    assert(names == List(s"$version-$abbrev-SNAPSHOT", s"$version-SNAPSHOT").sorted, s"$artifact versions: $names")
    val pointer = IO.read(
      released / "com" / "example" / "zipx" / "release" / artifact / s"$version-SNAPSHOT" / s"$artifact-$version-SNAPSHOT.pom"
    )
    assert(pointer.contains(full), pointer)
    assert(pointer.contains("<zipx.snapshot.sha>"), pointer)
}

val assertDirtyLocal = taskKey[Unit]("a dirty local publish writes +YYYYMMDD-HHmm and does not occupy the sha, -ci, or the registry")
assertDirtyLocal / aggregate := false
assertDirtyLocal := Def.uncached {
  val abbrev    = headSha((LocalRootProject / baseDirectory).value).take(12)
  val modelsDir = ivyLocalRepo / "models_3"
  val names     = Option(modelsDir.list()).map(_.toList).getOrElse(Nil)
  val dirty = names.filter(_.startsWith(s"1.4.2-$abbrev+"))
  val stamp = dirty match
    case one :: Nil => one.stripPrefix(s"1.4.2-$abbrev+")
    case other      => sys.error(s"expected one dirty id under $modelsDir, got $other")
  assert(stamp.matches("""\d{8}-\d{4}"""), stamp)
  assert(!names.contains(s"1.4.2-$abbrev"), names.toString)
  assert(!names.contains(s"1.4.2-$abbrev-SNAPSHOT"), names.toString)
  assert(!names.contains("1.4.2-ci"), names.toString)
  assert(!names.contains("1.4.2-SNAPSHOT"), names.toString)
  assert((modelsDir / s"1.4.2-$abbrev+$stamp" / "jars" / "models_3.jar").exists, dirty.toString)
  assert(!versionDirs("models_3").exists(_.contains("+")), versionDirs("models_3").toString)
}

def deleteMavenMetadata(dir: File): Unit =
  Option(dir.listFiles()).foreach(_.foreach { file =>
    if file.getName.startsWith("maven-metadata") then IO.delete(file)
  })

val assertImmutableResolve = taskKey[Unit]("the stored sha pin is not changing, resolves from the file repo, and ignores ivy -ci")
assertImmutableResolve / aggregate := false
assertImmutableResolve := Def.uncached {
  val root = (LocalRootProject / baseDirectory).value
  val rev = s"1.4.2-${headSha(root).take(12)}-SNAPSHOT"
  val mod = (pinned / libraryDependencies).value
    .find(_.revision == rev)
    .getOrElse(sys.error(s"no pin $rev in ${(pinned / libraryDependencies).value}"))
  assert(!mod.isChanging, mod.toString)
  assert((pinned / forceUpdatePeriod).value.isEmpty, (pinned / forceUpdatePeriod).value.toString)
  val jars = (pinned / updateFull).value.allFiles.filter(_.getName.startsWith("models_3")).toList
  val jar = jars match
    case one :: _ => one
    case Nil      => sys.error("no models jar")
  assert(jar.getAbsolutePath.contains("/released/"), jar.toString)
  assert(!jar.getAbsolutePath.contains(".ivy2"), jar.toString)
  IO.write((LocalRootProject / baseDirectory).value / "sha-digest", Hash.toHex(Hash(jar)))
  val artifact = released / "com" / "example" / "zipx" / "release" / "models_3"
  deleteMavenMetadata(artifact)
  deleteMavenMetadata(artifact / rev)
  lmcoursier.internal.SbtCoursierCache.default.clear()
}

val assertShaDigest = taskKey[Unit]("the sha pin resolves again after maven-metadata.xml is gone")
assertShaDigest / aggregate := false
assertShaDigest := Def.uncached {
  val expected = IO.read((LocalRootProject / baseDirectory).value / "sha-digest")
  val jars = (pinned / updateFull).value.allFiles.filter(_.getName.startsWith("models_3")).toList
  val jar = jars match
    case one :: _ => one
    case Nil      => sys.error("second resolve found no models jar")
  assert(Hash.toHex(Hash(jar)) == expected, jar.toString)
  assert(!(released / "com" / "example" / "zipx" / "release" / "models_3" / "maven-metadata.xml").exists)
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
