// Meta-build source mirror of workflow → core → central → plugin.
// Compiles modules/*/src/main/scala into project/meta-* targets (no publishLocal for root dogfood).
// After changing those sources: reload. Shared deps: Dependencies.scala.

ThisBuild / scalaVersion := Dependencies.scala3Version

lazy val metaWorkflow = project
  .in(file("meta-workflow"))
  .settings(
    name           := "meta-zipx-workflow",
    publish / skip := true,
    scalacOptions ++= Dependencies.commonScalacOptions,
    libraryDependencies ++= Dependencies.zioDeps ++ Dependencies.workflowLibraryDeps,
  )
  .settings(Dogfood.mirrorMainScala("workflow"))

lazy val metaCore = project
  .in(file("meta-core"))
  .dependsOn(metaWorkflow)
  .settings(
    name           := "meta-zipx-core",
    publish / skip := true,
    scalacOptions ++= Dependencies.commonScalacOptions,
    libraryDependencies ++= Dependencies.zioDeps,
    // Same embedded pin file as the main `core` module (dogfood classpath).
    Compile / resourceGenerators += Def.task {
      val repo = (LocalRootProject / baseDirectory).value.getParentFile
      val src  = repo / ".github" / "zipx" / "action-pins.yml"
      val out  = (Compile / resourceManaged).value / "zipx" / "action-pins.yml"
      if (!src.exists) sys.error(s"Missing action pin file: ${src.getPath}")
      IO.copyFile(src, out)
      Seq(out)
    }.taskValue,
  )
  .settings(Dogfood.mirrorMainScala("core"))

lazy val metaCentral = project
  .in(file("meta-central"))
  .dependsOn(metaCore)
  .settings(
    name           := "meta-zipx-central",
    publish / skip := true,
    scalacOptions ++= Dependencies.commonScalacOptions,
    libraryDependencies ++= Dependencies.zioDeps,
  )
  .settings(Dogfood.mirrorMainScala("central"))

lazy val metaPlugin = project
  .in(file("meta-plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(metaCore, metaCentral)
  .settings(
    name           := "meta-sbt-zipx",
    publish / skip := true,
    scalacOptions ++= Dependencies.commonScalacOptions,
    addSbtPlugin(Dependencies.remoteCachePlugin),
    libraryDependencySchemes ++= Dependencies.pluginLibraryDependencySchemes,
  )
  .settings(Dogfood.mirrorMainScala("sbt-plugin"))

lazy val metaRoot = (project in file(".")).dependsOn(metaPlugin)
