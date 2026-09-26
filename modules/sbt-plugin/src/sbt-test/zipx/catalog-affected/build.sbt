MyVersions.settings
version        := "1.0.0-ci"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-ci")

lazy val models = project.settings(libraryDependencies ++= MyVersions.deps(MyVersions.upickle))
lazy val svcA   = project.dependsOn(models)
lazy val svcB   = project.dependsOn(models).settings(libraryDependencies ++= MyVersions.deps(MyVersions.fansi))

lazy val root = (project in file("."))
  .aggregate(models, svcA, svcB)
  .settings(publish / skip := true)

// `assertAffected svcB` passes when the last zipxAffectedModules wrote exactly those module ids.
val assertAffected = inputKey[Unit]("the last zipxAffectedModules run affected exactly these modules")
assertAffected := {
  val expected = sbt.complete.DefaultParsers.spaceDelimited("<module>").parsed.toSet
  val written  = IO.read((LocalRootProject / baseDirectory).value / "target" / "zipx-affected.json")
  val got      = "\"([^\"]+)\"".r.findAllMatchIn(written).map(_.group(1)).toSet -- Set("root")
  assert(got == expected, s"affected $got, expected $expected")
}
