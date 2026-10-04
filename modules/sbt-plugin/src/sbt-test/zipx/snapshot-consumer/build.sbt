MyVersions.settings
organization := "com.example.zipx.pins"
// A qualifier snapshot stays changing. `<line>-SNAPSHOT` is the pointer, and update refuses it.
version      := "1.0.0-RC1-SNAPSHOT"
zipxVerify   := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

lazy val helper   = project
lazy val upstream = project.settings(
  if (file("v2").exists) MyVersions.withHelper else Nil,
  allDependencies ++= Def.uncached {
    if (file("v3").exists) Seq("org.slf4j" % "slf4j-api" % "2.0.18") else Nil
  },
)
lazy val consumer = project.settings(MyVersions.pinned)
lazy val other    = project

lazy val root = (project in file(".")).aggregate(helper, upstream, consumer, other).settings(publish / skip := true)

/** sbt 2's publishLocal ignores `ivyPaths`, so this is the machine's ivy repository. */
def fixtureRepo: File = file(sys.props("user.home")) / ".ivy2" / "local" / "com.example.zipx.pins"

val forgetFixtureRepo = taskKey[Unit]("Remove this fixture's organization from the machine's ivy repository")
forgetFixtureRepo := Def.uncached(IO.delete(fixtureRepo))

val assertFreshnessScoped = taskKey[Unit]("only a project that depends on a snapshot re-resolves every session")
assertFreshnessScoped := Def.uncached {
  val zero = Some(scala.concurrent.duration.Duration.Zero)
  assert((consumer / forceUpdatePeriod).value == zero, (consumer / forceUpdatePeriod).value.toString)
  assert((other / forceUpdatePeriod).value.isEmpty, (other / forceUpdatePeriod).value.toString)
  assert((consumer / resolvers).value.exists(_.name == "central-snapshots"), (consumer / resolvers).value.toString)
}

val assertCiPins = taskKey[Unit]("ci.yml revalidates snapshots and names the pins on every run")
assertCiPins := Def.uncached {
  val yml = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml")
  assert(yml.contains("COURSIER_TTL: \"0s\""), yml)
  val note = yml.linesIterator.find(_.contains("::warning title=zipx snapshots::pinned snapshots (")).getOrElse("")
  assert(note.contains("com.example.zipx.pins:upstream:1.0.0-RC1-SNAPSHOT"), yml)
  assert(note.contains("com.example.zipx.pins:helper:1.0.0-RC1-SNAPSHOT"), yml)
}
