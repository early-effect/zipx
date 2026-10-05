// Puts Dependencies.scala and Dogfood.scala on the classpath of project/ .sbt files, which cannot import them otherwise
// (they sit one sbt layer above project/ .scala files).
// ZipxVersions.scala stays off: it imports zipx types this layer does not have.
Compile / unmanagedSources ++= {
  val projectDir = baseDirectory.value.getParentFile
  Seq(projectDir / "Dependencies.scala", projectDir / "Dogfood.scala")
}
