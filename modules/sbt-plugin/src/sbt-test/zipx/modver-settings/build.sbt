MyVersions.settings
organization := "com.example"
zipxCacheEpoch := CacheEpoch.Fixed("1.4.2-ci")
zipxVerify     := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

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
assertModverSettings := {
  val modelsV  = (models / version).value
  val coreV    = (coreLib / version).value
  val clientV  = (client / version).value
  val serviceV = (service / version).value
  val rootV    = (root / version).value
  assert(modelsV == "1.4.2-ci", s"models version, got $modelsV")
  assert(coreV == "1.4.2-ci", s"coreLib version, got $coreV")
  assert(clientV == "0.3.0-ci", s"client version, got $clientV")
  assert(serviceV == "0.1.0-SNAPSHOT", s"unpublished service must not take a Ship version, got $serviceV")
  assert(rootV == "0.1.0-SNAPSHOT", s"root aggregator must not take a Ship version, got $rootV")
}

val assertClientPom = taskKey[Unit]("POM sibling revisions are catalog numbers, not -ci")
assertClientPom := {
  val pom = (client / makePom).value
  val xml = IO.read(fileConverter.value.toPath(pom).toFile)
  assert(!xml.contains("-ci"), s"POM must not emit -ci, got $xml")
  assert(xml.contains("<version>1.4.2</version>"), s"client POM should depend on coreLib 1.4.2, got $xml")
}

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
