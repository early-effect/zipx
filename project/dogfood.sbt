// Compiles modules/*/src/main/scala into project/meta-* so the root build dogfoods zipx without a publishLocal.
// After changing those sources, reload.

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
    libraryDependencies ++= Dependencies.zioDeps :+ Dependencies.zioJson,
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

lazy val metaCli = project
  .in(file("meta-cli"))
  .dependsOn(metaSyntax)
  .settings(
    name           := "meta-zipx-cli",
    publish / skip := true,
    scalacOptions ++= Dependencies.commonScalacOptions,
    libraryDependencies ++= Dependencies.zioDeps ++ Dependencies.scala3Compiler,
  )
  .settings(Dogfood.mirrorMainScala("cli"))

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
    libraryDependencies ++= Seq(Dependencies.mimaCore, Dependencies.coursier),
    addSbtPlugin(Dependencies.remoteCachePlugin),
  )
  .settings(Dogfood.mirrorMainScala("sbt-plugin"))

lazy val metaRoot = (project in file(".")).dependsOn(metaPlugin)
