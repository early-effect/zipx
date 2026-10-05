MyVersions.settings
organization := "com.example.zipx.plan"
zipxVerify   := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

LocalRootProject / zipxReleaseWorkflow :=
  Some(ReleaseWorkflow(ArtifactRegistry.Url(file("released").getAbsoluteFile.toURI.toString)))

lazy val client = project.settings(libraryDependencies ++= MyVersions.deps(MyVersions.widgets))
lazy val root   = (project in file(".")).aggregate(client).settings(publish / skip := true)

def writePom(dir: File, version: String, sha: Option[String]): Unit =
  IO.createDirectory(dir)
  val props = sha.fold("")(value => s"<properties><zipx.snapshot.sha>$value</zipx.snapshot.sha></properties>")
  IO.write(dir / s"widgets_3-$version.pom", s"<project><version>$version</version>$props</project>")

def widgetsRepo(root: File): File =
  root / "released" / "com" / "example" / "zipx" / "plan" / "widgets_3"

val seedPointer = taskKey[Unit]("Write the pointer POM and both commit POMs into the file registry")
seedPointer / aggregate := false
seedPointer := Def.uncached {
  val base = widgetsRepo((LocalRootProject / baseDirectory).value)
  writePom(base / "1.4.2-SNAPSHOT", "1.4.2-SNAPSHOT", Some("9876fedcba09876543210fedcba9876543210abc"))
  writePom(base / "1.4.2-aaaaaaaaaaaa-SNAPSHOT", "1.4.2-aaaaaaaaaaaa-SNAPSHOT", None)
  writePom(base / "1.4.2-9876fedcba09-SNAPSHOT", "1.4.2-9876fedcba09-SNAPSHOT", None)
}

val seedRelease = taskKey[Unit]("Write the release POM for widgets 1.4.2")
seedRelease / aggregate := false
seedRelease := Def.uncached {
  writePom(widgetsRepo((LocalRootProject / baseDirectory).value) / "1.4.2", "1.4.2", None)
}

val assertAdvanced = taskKey[Unit]("advance rewrote the pin to the pointer sha")
assertAdvanced / aggregate := false
assertAdvanced := Def.uncached {
  val text = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  assert(text.contains(""""1.4.2-9876fedcba09-SNAPSHOT""""), text)
  assert(!text.contains("aaaaaaaaaaaa"), text)
}

val assertPinned = taskKey[Unit]("pin release rewrote the pin to the line")
assertPinned / aggregate := false
assertPinned := Def.uncached {
  val text = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  assert(text.contains("""Lib("com.example.zipx.plan", "widgets", "1.4.2")"""), text)
}
