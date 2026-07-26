// Meta-meta build: make project/Dependencies.scala and Dogfood.scala visible to project/ .sbt files.
// Without this, those .sbt files sit one sbt layer above project/ .scala files and cannot import them.
// Prefer this over symlinks so project/project/ can stay free of duplicated sources (Metals still
// writes metals.sbt / target here; those stay gitignored).
Compile / unmanagedSources ++= {
  val projectDir = baseDirectory.value.getParentFile
  Seq(projectDir / "Dependencies.scala", projectDir / "Dogfood.scala")
}
