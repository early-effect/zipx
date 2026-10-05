MyVersions.settings
organization := "com.example.zipx.modver"
zipxVerify          := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
// A file: registry this fixture never publishes to. The rows stay unreleased without calling Maven Central.
zipxReleaseWorkflow := Some(ReleaseWorkflow(ArtifactRegistry.Url(file("released").getAbsoluteFile.toURI.toString)))

lazy val models = project.settings(MyVersions.libraries)

lazy val coreLib = (project in file("core-lib"))
  .dependsOn(models)
  .settings(MyVersions.libraries)

lazy val client = project
  .dependsOn(coreLib)
  .settings(MyVersions.libraries)

lazy val service = project
  .dependsOn(coreLib)
  .settings(publishArtifact := false)

lazy val root = (project in file("."))
  .aggregate(models, coreLib, client, service)
  .settings(publish / skip := true)

val assertModverSettings = taskKey[Unit]("Ship-backed version is row-ci; aggregators keep sbt default")
assertModverSettings := Def.uncached {
  val modelsV  = (models / version).value
  val coreV    = (coreLib / version).value
  val clientV  = (client / version).value
  val serviceV = (service / version).value
  val rootV    = (root / version).value
  assert(modelsV == "1.4.2-ci", s"models version, got $modelsV")
  assert(coreV == "1.4.2-ci", s"coreLib version, got $coreV")
  assert(clientV == "0.3.0-ci", s"client version, got $clientV")
  assert(serviceV == "0.1.0-SNAPSHOT", s"unpublished service must not take a Ship version, got $serviceV")
  assert(rootV == "0.0.0", s"a root in no row is 0.0.0, which sonaRelease accepts, got $rootV")
  assert((models / isSnapshot).value, "a development ship is a snapshot")
  assert(zipxCacheEpoch.value == CacheEpoch.ShipCatalog, s"Ship rows key the cache epoch, got ${zipxCacheEpoch.value}")
}

val assertLocalPom = taskKey[Unit]("An unreleased POM names what it built, as its ivy.xml does")
assertLocalPom := {
  val pom = (client / makePom).value
  val xml = IO.read(fileConverter.value.toPath(pom).toFile)
  assert(xml.contains("<version>1.4.2-ci</version>"), s"client POM should name coreLib as built, got $xml")
  assert(xml.contains("<version>0.3.0-ci</version>"), s"client POM should name itself as built, got $xml")
}

/** publishLocal ignores `ivyPaths`, so this fixture's organization lands in the machine's ivy repository. */
def fixtureRepo: File = file(sys.props("user.home")) / ".ivy2" / "local" / "com.example.zipx.modver"

def publishedModels(version: String): Array[Byte] =
  IO.readBytes(fixtureRepo / "models_3" / version / "jars" / "models_3.jar")

// Each of these reads or writes outside sbt's view, so none may be served from sbt's task cache.
val recordPublishedModels = taskKey[Unit]("Remember the models jar publishLocal wrote")
recordPublishedModels := Def.uncached {
  IO.write(target.value / "published-models.sha", Hash.toHex(Hash(publishedModels((models / version).value))))
}

val assertRepublished = taskKey[Unit]("A second publishLocal after a change replaced the jar, not skipped it")
assertRepublished := Def.uncached {
  val v      = (models / version).value
  val before = IO.read(target.value / "published-models.sha")
  assert(before != Hash.toHex(Hash(publishedModels(v))), s"publishLocal kept the first models jar at $v")
}

val forgetFixtureRepo = taskKey[Unit]("Remove this fixture's organization from the machine's ivy repository")
forgetFixtureRepo := Def.uncached(IO.delete(fixtureRepo))

val assertCatalogUntouched = taskKey[Unit]("catalog update leaves Ship constructors")
assertCatalogUntouched := {
  val src = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  assert(src.contains("""ShipGroup("libs", "1.4.2")("models", "coreLib")"""), src)
  assert(src.contains("""Ship("client", "0.3.0")"""), src)
}

val assertBumpedClient = taskKey[Unit]("zipxModverBump rewrote the client Ship to a release number")
assertBumpedClient := {
  val src = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  assert(src.contains("""Ship("client", "0.3.1")"""), src)
  assert(!src.contains("0.3.1-SNAPSHOT"), src)
  assert(src.contains("""ShipGroup("libs", "1.4.2")("models", "coreLib")"""), src)
}

val assertModverWorkflow = taskKey[Unit]("ci.yml checks bumps on PRs and publishes nothing; releases are zipx-release.yml")
assertModverWorkflow := {
  val content = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml")
  assert(content.contains("modver-check:"), "missing injected modver-check")
  assert(content.contains("modver-suggest:"), "missing injected modver-suggest")
  assert(!content.contains("modver:"), "a merge must not plan a modver release job")
  assert(!content.contains("  publish:") && !content.contains("publish-"), "a merge must not publish Ship rows")
  assert(!content.contains("SONATYPE_") && !content.contains("PGP_"), "ci.yml must not carry release secrets")
}
