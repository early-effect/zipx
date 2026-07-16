val scala3Version   = "3.8.4"
val zioVersion      = "2.1.26"
val zioBlocksVersion = "0.0.47"

ThisBuild / scalaVersion  := scala3Version
ThisBuild / organization  := "rocks.earlyeffect"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / version       := "0.1.0-SNAPSHOT"

val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all",
)

val zioDeps = Seq(
  "dev.zio" %% "zio"          % zioVersion,
  "dev.zio" %% "zio-test"     % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
)

val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  libraryDependencies ++= zioDeps,
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
)

lazy val root = (project in file("."))
  .aggregate(workflow, core, plugin)
  .settings(
    name           := "zipx",
    publish / skip := true,
  )

// Scala 3. GitHub Actions AST + deterministic YAML renderer.
// Uses zio-blocks' Yaml AST + YamlWriter for rendering; the GHA-AST -> Yaml
// mapping is hand-built so trigger keys (on:), if:, and kebab keys stay exact.
lazy val workflow = (project in file("modules/workflow"))
  .settings(commonSettings)
  .settings(
    name := "zipx-workflow",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-blocks-schema"      % zioBlocksVersion,
      "dev.zio" %% "zio-blocks-schema-yaml" % zioBlocksVersion,
    ),
  )

// Scala 3. Module-graph model, toposort, capabilities, and the planner.
lazy val core = (project in file("modules/core"))
  .dependsOn(workflow)
  .settings(commonSettings)
  .settings(
    name := "zipx-core"
  )

// The sbt 2.x AutoPlugin — the only module that touches sbt.*.
lazy val plugin = (project in file("modules/sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core)
  .settings(
    name := "zipx-sbt",
    scalacOptions ++= commonScalacOptions,
    // Bundle the remote-cache transport so consumers need one addSbtPlugin line — RemoteCachePlugin triggers on
    // AllRequirements but is a no-op until Global/remoteCache is set (which zipx does only from the CI env).
    addSbtPlugin("org.scala-sbt" % "sbt-remote-cache" % "2.0.1"),
    // compiler-interface is versioned on the sbt (2.x) scheme via the sbt stack but on the zinc (1.x) scheme via the
    // Scala 3 compiler; they're compatible, so treat it as always-compatible to avoid a false eviction error.
    libraryDependencySchemes += "org.scala-sbt" % "compiler-interface" % "always",
    scriptedLaunchOpts ++= Seq("-Xmx1024m", s"-Dplugin.version=${version.value}"),
    scriptedBufferLog := false,
  )
