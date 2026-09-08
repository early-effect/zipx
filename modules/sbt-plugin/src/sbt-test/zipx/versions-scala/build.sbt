// Catalog Scala is not sbt 2.0.8's metabuild default (3.8.4). MyVersions.settings sets the
// project-level common scalaVersion; this file must not also set ThisBuild / scalaVersion.
MyVersions.settings
version        := "1.0.0-ci"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-ci")
zipxVerify := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

libraryDependencies ++= MyVersions.deps(MyVersions.zio)

lazy val root = (project in file("."))
  .settings(publish / skip := true)

val assertScalaAxes = taskKey[Unit]("root scalaVersion is catalog; ThisBuild stays the metabuild default")
assertScalaAxes := {
  val rootSv = (LocalRootProject / scalaVersion).value
  val tbSv   = (ThisBuild / scalaVersion).value
  assert(rootSv == "3.7.3", s"root scalaVersion should be catalog 3.7.3, got $rootSv")
  assert(
    tbSv == "3.8.4",
    s"ThisBuild / scalaVersion should stay sbt 2's metabuild default 3.8.4, got $tbSv",
  )
}
