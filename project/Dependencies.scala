import sbt.*

/** Shared versions and dependency lists for the main build and the meta-build dogfood mirror.
  *
  * Keep library deps used by modules workflow/core/central/sbt-plugin here so project/dogfood.sbt and build.sbt cannot
  * drift.
  *
  * Layering: project/ .sbt files sit one level above project/ .scala files, so dogfood cannot import this object by
  * default. project/project/build.sbt adds this file (and Dogfood.scala) via unmanagedSources (same source, no
  * symlink). Docs/Specular-only deps stay in build.sbt.
  */
object Dependencies:

  val scala3Version      = "3.8.4"
  val zioVersion         = "2.1.26"
  val specularVersion    = "0.11.0"
  val zioBlocksVersion   = "0.0.51"
  val remoteCacheVersion = "2.0.4"
  val neotypeVersion     = "0.7.0"

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

  // Compile-time-validated newtypes for the shell AST. Compile scope is neotype-internal + comptime +
  // scala3-library only (zio-test is test-only), so it stays light on consumer meta-build classpaths.
  val shellLibraryDeps: Seq[ModuleID] = Seq(
    "io.github.kitlangton" %% "neotype" % neotypeVersion
  )

  /** Bundled so consumers need one `addSbtPlugin` line for zipx.
    *
    * Drop `org.scala-sbt` transitives: the published POM re-lists `sbt` as a compile dependency, which pulls
    * `compiler-interface` into every consumer meta-build and collides with zinc 1.x schemes from other plugins. Host
    * sbt already provides that stack; remote-cache only needs its own jar plus shaded-remoteapis.
    */
  val remoteCachePlugin: ModuleID =
    ("org.scala-sbt" % "sbt-remote-cache" % remoteCacheVersion)
      .excludeAll(ExclusionRule(organization = "org.scala-sbt"))

  val testcontainersVersion = "2.0.5"

  val testcontainersDeps: Seq[ModuleID] = Seq(
    "org.testcontainers" % "testcontainers" % testcontainersVersion % Test
  )

end Dependencies
