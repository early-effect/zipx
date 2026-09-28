MyVersions.settings
organization := "com.example.zipx.modver"
zipxCacheEpoch := CacheEpoch.Fixed("1.4.2-ci")
zipxVerify     := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
zipxCapabilities += ZipxModver.publish()

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

val assertModverSettings = taskKey[Unit]("Ship-backed version is row-SNAPSHOT; aggregators keep sbt default")
assertModverSettings := {
  val modelsV  = (models / version).value
  val coreV    = (coreLib / version).value
  val clientV  = (client / version).value
  val serviceV = (service / version).value
  val rootV    = (root / version).value
  assert(modelsV == "1.4.2-SNAPSHOT", s"models version, got $modelsV")
  assert(coreV == "1.4.2-SNAPSHOT", s"coreLib version, got $coreV")
  assert(clientV == "0.3.0-SNAPSHOT", s"client version, got $clientV")
  assert(serviceV == "0.1.0-SNAPSHOT", s"unpublished service must not take a Ship version, got $serviceV")
  assert(rootV == "0.1.0-SNAPSHOT", s"root aggregator must not take a Ship version, got $rootV")
}

val assertLocalPom = taskKey[Unit]("An unreleased POM names what it built, as its ivy.xml does")
assertLocalPom := {
  val pom = (client / makePom).value
  val xml = IO.read(fileConverter.value.toPath(pom).toFile)
  assert(xml.contains("<version>1.4.2-SNAPSHOT</version>"), s"client POM should name coreLib as built, got $xml")
  assert(xml.contains("<version>0.3.0-SNAPSHOT</version>"), s"client POM should name itself as built, got $xml")
}

val assertReleasePom = taskKey[Unit]("A release POM names each unreleased sibling at its catalog number")
assertReleasePom := {
  val pom = (client / makePom).value
  val xml = IO.read(fileConverter.value.toPath(pom).toFile)
  assert(!xml.contains("-SNAPSHOT"), s"a release POM must not name a -SNAPSHOT, got $xml")
  assert(xml.contains("<version>1.4.2</version>"), s"client POM should depend on coreLib 1.4.2, got $xml")
  assert(xml.contains("<version>0.3.0</version>"), s"client POM should name itself 0.3.0, got $xml")
}

/** Where sbt 2's publishLocal writes this fixture's organization. It ignores `ivyPaths`, so this is the machine's. */
def fixtureRepo: File = file(sys.props("user.home")) / ".ivy2" / "local" / "com.example.zipx.modver"

def publishedModels(version: String): Array[Byte] =
  IO.readBytes(fixtureRepo / "models_3" / version / "jars" / "models_3.jar")

// Each of these reads or writes outside sbt's view, so none may be served from sbt 2's task cache.
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

val assertBumpedClient = taskKey[Unit]("zipxModverBump rewrote the client Ship, not -ci")
assertBumpedClient := {
  val src = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  assert(src.contains("""Ship("client", "0.3.1")"""), src)
  assert(!src.contains("0.3.1-ci"), src)
  assert(src.contains("""ShipGroup("libs", "1.4.2")("models", "coreLib")"""), src)
}

val assertModverWorkflow = taskKey[Unit]("generated CI is ZipxModver Graph publish, no Central secrets")
assertModverWorkflow := {
  val content = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml")
  assert(content.contains("modver:"), "missing synthetic modver job")
  assert(content.contains("publish-client:"), "missing publish-client")
  assert(content.contains("publish-models:"), "missing publish-models")
  assert(content.contains("publish-coreLib:"), "missing publish-coreLib")
  assert(content.contains("modver-check:"), "missing injected modver-check")
  assert(content.contains("modver-suggest:"), "missing injected modver-suggest")
  assert(content.contains("workflow_dispatch"), "OnDefaultPush must include workflow_dispatch")
  assert(
    content.contains("contains(fromJson(needs.modver.outputs.modules), 'client')"),
    "publish-client should gate on the compact modver array",
  )
  assert(
    !content.contains("contains(fromJson(needs.modver.outputs.modules), 'all')"),
    "modver JSON must not use the affected all-sentinel",
  )
  assert(!content.contains("SONATYPE_"), "ZipxModver must not require Central secrets")
  assert(!content.contains("PGP_"), "ZipxModver must not require signing secrets")
}
