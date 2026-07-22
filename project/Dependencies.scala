import sbt.*

/** Shared versions and dependency lists for the main build and the meta-build dogfood mirror.
  *
  * Keep library deps used by modules workflow/core/central/sbt-plugin here so project/dogfood.sbt and build.sbt cannot
  * drift.
  *
  * Layering note: .sbt files under project/ are the meta-meta build and cannot see .scala files under project/
  * directly. project/project/Dependencies.scala is a symlink to this file so dogfood.sbt can use it. Docs/Specular-only
  * deps stay in build.sbt.
  */
object Dependencies:

  val scala3Version      = "3.8.4"
  val zioVersion         = "2.1.26"
  val zioBlocksVersion   = "0.017"
  val specularVersion    = "0.4.0"
  val remoteCacheVersion = "2.0.1"

  val commonScalacOptions: Seq[String] = Seq(
    "-deprecation",
    "-feature",
    "-Wunused:all",
  )

  val zioDeps: Seq[ModuleID] = Seq(
    "dev.zio" %% "zio"          % zioVersion,
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
  )

  val workflowLibraryDeps: Seq[ModuleID] = Seq(
    "dev.zio" %% "zio-blocks-schema"      % zioBlocksVersion,
    "dev.zio" %% "zio-blocks-schema-yaml" % zioBlocksVersion,
  )

  /** Bundled so consumers need one `addSbtPlugin` line for zipx. */
  val remoteCachePlugin: ModuleID =
    "org.scala-sbt" % "sbt-remote-cache" % remoteCacheVersion

  /** compiler-interface is on both the sbt-2.x and zinc-1.x schemes; treat as always-compatible. */
  val pluginLibraryDependencySchemes: Seq[ModuleID] = Seq(
    "org.scala-sbt" % "compiler-interface" % "always"
  )

end Dependencies
