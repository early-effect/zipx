MyVersions.settings
organization := "com.example.zipx.plan"
zipxVerify   := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

LocalRootProject / zipxReleaseWorkflow :=
  Some(ReleaseWorkflow(ArtifactRegistry.Url(file("released").getAbsoluteFile.toURI.toString)))

lazy val client = project.settings(libraryDependencies ++= MyVersions.deps(MyVersions.widgets))
lazy val root   = (project in file(".")).aggregate(client).settings(publish / skip := true)

val Widgets = "widgets_3"
val Gadgets = "sbt-gadgets_sbt2_3"

def writePom(repo: File, version: String, sha: Option[String]): Unit =
  val dir   = repo / version
  val props = sha.fold("")(value => s"<properties><zipx.snapshot.sha>$value</zipx.snapshot.sha></properties>")
  IO.createDirectory(dir)
  IO.write(dir / s"${repo.getName}-$version.pom", s"<project><version>$version</version>$props</project>")

def repoOf(root: File, artifact: String): File =
  root / "released" / "com" / "example" / "zipx" / "plan" / artifact

def widgetsRepo(root: File): File = repoOf(root, Widgets)

val seedPointer = taskKey[Unit]("Write the pointer POM and both commit POMs into the file registry")
seedPointer / aggregate := false
seedPointer := Def.uncached {
  val repo = widgetsRepo((LocalRootProject / baseDirectory).value)
  writePom(repo, "1.4.2-SNAPSHOT", Some("9876fedcba09876543210fedcba9876543210abc"))
  writePom(repo, "1.4.2-aaaaaaaaaaaa-SNAPSHOT", None)
  writePom(repo, "1.4.2-9876fedcba09-SNAPSHOT", None)
}

val seedUnnamedPointer = taskKey[Unit]("Write the pointer POM a plain publish writes: it names no commit")
seedUnnamedPointer / aggregate := false
seedUnnamedPointer := Def.uncached {
  val repo = widgetsRepo((LocalRootProject / baseDirectory).value)
  writePom(repo, "1.4.2-SNAPSHOT", None)
  writePom(repo, "1.4.2-aaaaaaaaaaaa-SNAPSHOT", None)
}

val seedRelease = taskKey[Unit]("Write the release POM for widgets 1.4.2")
seedRelease / aggregate := false
seedRelease := Def.uncached {
  writePom(widgetsRepo((LocalRootProject / baseDirectory).value), "1.4.2", None)
}

val seedPluginLine = taskKey[Unit]("Write sbt-gadgets' release, its line's pointer, and the commit the pointer names")
seedPluginLine / aggregate := false
seedPluginLine := Def.uncached {
  val repo = repoOf((LocalRootProject / baseDirectory).value, Gadgets)
  writePom(repo, "0.2.0", None)
  writePom(repo, "0.2.0-SNAPSHOT", Some("5555aaaa5555aaaa5555aaaa5555aaaa5555aaaa"))
  writePom(repo, "0.2.0-5555aaaa5555-SNAPSHOT", None)
}

val assertPluginOnCommit = taskKey[Unit]("advance moved the plugin row onto the commit its line's pointer names")
assertPluginOnCommit / aggregate := false
assertPluginOnCommit := Def.uncached {
  val text = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  assert(text.contains("""Plugin("com.example.zipx.plan", "sbt-gadgets", "0.2.0-5555aaaa5555-SNAPSHOT")"""), text)
}

val assertPluginReleased = taskKey[Unit]("pin release moved the plugin row back to its release")
assertPluginReleased / aggregate := false
assertPluginReleased := Def.uncached {
  val text = IO.read((LocalRootProject / baseDirectory).value / "project" / "ZipxVersions.scala")
  assert(text.contains("""Plugin("com.example.zipx.plan", "sbt-gadgets", "0.2.0")"""), text)
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
