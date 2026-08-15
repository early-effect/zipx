// Meta-meta build: make project/Dependencies.scala and Dogfood.scala visible to project/ .sbt files.
// Without this, those .sbt files sit one sbt layer above project/ .scala files and cannot import them.
// ZipxVersions.scala stays off this classpath: it imports zipx types the meta-meta layer does not have.
Compile / unmanagedSources ++= {
  val projectDir = baseDirectory.value.getParentFile
  Seq(projectDir / "Dependencies.scala", projectDir / "Dogfood.scala")
}
