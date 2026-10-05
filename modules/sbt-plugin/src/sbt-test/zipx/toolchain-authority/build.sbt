// sbt-scala-native injects its own test-interface into every Native project. A test library built against an older
// one must not decide which runtime the plugin's test runner talks to, and sbt's strict eviction check must not see it.
MyVersions.settings
zipxVerify := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
ThisBuild / resolvers += "toolchain-authority" at file("registry").getAbsoluteFile.toURI.toString

lazy val app = (projectMatrix in file("app"))
  .settings(MyVersions.library(MyVersions.testkit))
  .nativePlatform(scalaVersions = Seq("3.9.0"))

lazy val root = (project in file("."))
  .aggregate(app.projectRefs*)
  .settings(publish / skip := true)

val assertNativeRuntime = taskKey[Unit]("the Native test runtime is the one sbt-scala-native injects")
assertNativeRuntime / aggregate := false
assertNativeRuntime := Def.uncached {
  val selected = (LocalProject("appNative") / updateFull).value.configurations
    .filter(_.configuration.name == Test.name)
    .flatMap(_.details)
    .filter(d => d.organization == "org.scala-native" && d.name == "test-interface_native0.5_3")
    .flatMap(_.modules)
    .filterNot(_.evicted)
    .map(_.module.revision)
    .distinct
  assert(selected == Vector("0.5.12"), s"test-interface selected $selected, the plugin injects 0.5.12")
}
