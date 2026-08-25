// Meta-build source mirror of shell → workflow → core → (central, aws) → plugin.
// Compiles modules/*/src/main/scala into project/meta-* targets (no publishLocal for root dogfood).
// After changing those sources: reload. Shared deps: project/Dependencies.scala
// (on this classpath via project/project/build.sbt unmanagedSources). Typed catalog:
// project/ZipxVersions.scala (main build only; zipx types are not on this layer).

ThisBuild / scalaVersion := Dependencies.scala3Version

lazy val metaShell = project
  .in(file("meta-shell"))
  .settings(
    name           := "meta-zipx-shell",
    publish / skip := true,
    scalacOptions ++= Dependencies.commonScalacOptions,
    // No workflowLibraryDeps: zipx-shell has no zio-blocks dependency, by design.
    libraryDependencies ++= Dependencies.zioDeps ++ Dependencies.shellLibraryDeps,
  )
  .settings(Dogfood.mirrorMainScala("shell"))

lazy val metaWorkflow = project
  .in(file("meta-workflow"))
  .dependsOn(metaShell)
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
  )
  .settings(Dogfood.mirrorMainScala("core"))

lazy val metaSyntax = project
  .in(file("meta-syntax"))
  .dependsOn(metaCore)
  .settings(
    name           := "meta-zipx-syntax",
    publish / skip := true,
    scalacOptions ++= Dependencies.commonScalacOptions,
    libraryDependencies ++= Dependencies.zioDeps ++ Dependencies.scala3Compiler,
  )
  .settings(Dogfood.mirrorMainScala("syntax"))

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

lazy val metaAws = project
  .in(file("meta-aws"))
  .dependsOn(metaCore)
  .settings(
    name           := "meta-zipx-aws",
    publish / skip := true,
    scalacOptions ++= Dependencies.commonScalacOptions,
    libraryDependencies ++= Dependencies.zioDeps,
  )
  .settings(Dogfood.mirrorMainScala("aws"))

lazy val metaPlugin = project
  .in(file("meta-plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(metaCore, metaSyntax, metaCentral, metaAws)
  .settings(
    name           := "meta-sbt-zipx",
    publish / skip := true,
    scalacOptions ++= Dependencies.commonScalacOptions,
    addSbtPlugin(Dependencies.remoteCachePlugin),
  )
  .settings(Dogfood.mirrorMainScala("sbt-plugin"))

lazy val metaRoot = (project in file(".")).dependsOn(metaPlugin)
